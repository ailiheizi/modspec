//! `modspec script` — JS/Lua hook scripting subsystem CLI.
//!
//! Local validation (`validate`) is fully offline. Mutating commands
//! (`deploy`/`enable`/`disable`/`remove`/`reload`) carry a stable `request_id`
//! and are NEVER blindly retried. `run` drives one full PC session: recover
//! transport → deploy → ensure scope → restart hook processes → poll structured
//! `script_*` events until the expected outcome or the deadline (Ctrl-C exits
//! cleanly). `follow` streams the journal with a resumable cursor.

use std::collections::BTreeMap;
use std::time::Duration;

use anyhow::{Context, Result};
use modspec_core::{validate_script_bundle, DeviceStore, ScriptBundle};
use modspec_protocol::{
    run_script_session, InstallFridaGadgetParams, ScriptDeployParams, ScriptDisableParams,
    ScriptEnableParams, ScriptExpect, ScriptFileDto, ScriptListParams, ScriptReloadParams,
    ScriptRemoveParams, ScriptSessionError, ScriptSessionEvent, ScriptSessionParams,
};
use serde_json::{json, Value};
use uuid::Uuid;

use crate::commands::connect_device;
use crate::ScriptAction;

pub async fn run(action: ScriptAction) -> Result<()> {
    match action {
        ScriptAction::Validate { path, json } => validate_script(&path, json),
        ScriptAction::Deploy {
            path,
            device,
            serial,
            no_bootstrap,
            no_activate,
            json,
        } => {
            deploy_script(
                &path,
                device.as_deref(),
                serial.as_deref(),
                no_bootstrap,
                !no_activate,
                json,
            )
            .await
        }
        ScriptAction::Run {
            path,
            device,
            serial,
            no_bootstrap,
            wait,
            expect,
            no_restart,
            json,
        } => {
            run_script(RunOptions {
                path: &path,
                device: device.as_deref(),
                serial: serial.as_deref(),
                no_bootstrap,
                wait_seconds: wait,
                expect: &expect,
                no_restart,
                json,
            })
            .await
        }
        ScriptAction::List {
            device,
            serial,
            no_bootstrap,
            json,
        } => list_scripts(device.as_deref(), serial.as_deref(), no_bootstrap, json).await,
        ScriptAction::Enable {
            script_id,
            device,
            serial,
            no_bootstrap,
            keep_others,
            json,
        } => {
            enable_script(
                &script_id,
                device.as_deref(),
                serial.as_deref(),
                no_bootstrap,
                !keep_others,
                json,
            )
            .await
        }
        ScriptAction::Disable {
            script_id,
            device,
            serial,
            no_bootstrap,
            json,
        } => {
            disable_script(
                &script_id,
                device.as_deref(),
                serial.as_deref(),
                no_bootstrap,
                json,
            )
            .await
        }
        ScriptAction::Remove {
            script_id,
            device,
            serial,
            no_bootstrap,
            json,
        } => {
            remove_script(
                &script_id,
                device.as_deref(),
                serial.as_deref(),
                no_bootstrap,
                json,
            )
            .await
        }
        ScriptAction::Reload {
            script_id,
            device,
            serial,
            no_bootstrap,
            restart,
            json,
        } => {
            reload_script(
                &script_id,
                device.as_deref(),
                serial.as_deref(),
                no_bootstrap,
                restart,
                json,
            )
            .await
        }
        ScriptAction::Follow {
            device,
            serial,
            no_bootstrap,
            script,
            cursor,
            json,
        } => {
            follow_events(
                device.as_deref(),
                serial.as_deref(),
                no_bootstrap,
                script.as_deref(),
                cursor,
                json,
            )
            .await
        }
    }
}

/// Load and validate a script bundle from a directory (offline).
fn load_bundle(path: &str) -> Result<(ScriptBundle, String)> {
    let bundle = ScriptBundle::from_dir(std::path::Path::new(path))
        .with_context(|| format!("read script package {path}"))?;
    validate_script_bundle(&bundle).with_context(|| format!("validate script package {path}"))?;
    let hash = bundle.content_hash();
    Ok((bundle, hash))
}

fn to_dto(bundle: &ScriptBundle) -> Vec<ScriptFileDto> {
    bundle
        .files
        .iter()
        .map(|file| ScriptFileDto {
            name: file.name.clone(),
            content: file.content.clone(),
        })
        .collect()
}

fn validate_script(path: &str, json: bool) -> Result<()> {
    match load_bundle(path) {
        Ok((bundle, hash)) => {
            if json {
                println!(
                    "{}",
                    serde_json::to_string(&json!({
                        "ok": true,
                        "script_id": bundle.manifest.meta.id,
                        "engine": bundle.manifest.engine.runtime,
                        "entrypoint": bundle.entrypoint(),
                        "packages": bundle.manifest.compatible.packages,
                        "target_packages": bundle.manifest.compatible.target_packages,
                        "content_hash": hash,
                    }))?
                );
            } else {
                println!(
                    "OK script {} (engine={}, entrypoint={}, packages={})",
                    bundle.manifest.meta.id,
                    bundle.manifest.engine.runtime,
                    bundle.entrypoint(),
                    bundle.manifest.compatible.packages.join(",")
                );
                println!("content_hash={hash}");
            }
            Ok(())
        }
        Err(error) => {
            if json {
                println!(
                    "{}",
                    serde_json::to_string(&json!({ "ok": false, "errors": [error.to_string()] }))?
                );
                Ok(())
            } else {
                Err(error)
            }
        }
    }
}

/// Connect (with preflight repair + optional bootstrap) and return the client.
async fn connect(
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
) -> Result<(String, modspec_protocol::RpcClient)> {
    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)?;
    if device.auth_token.is_none() {
        anyhow::bail!(
            "device {} has no bearer token (stale or pre-token record); re-pair first: `modspec pair scan`",
            device.id
        );
    }
    let (client, status) = connect_device(
        device,
        serial.map(str::to_string),
        !no_bootstrap,
        Duration::from_secs(3),
        2,
    )
    .await?;
    if !status.is_healthy() {
        anyhow::bail!(
            "connection failed (issue={}): {}",
            status.issue.as_str(),
            status.detail
        );
    }
    if status.forward_rebuilt {
        println!("connection repaired: forward rebuilt (adb)");
    }
    if status.bootstrapped {
        println!("connection repaired: agent relaunched (MainActivity)");
    }
    Ok((device.id.clone(), client))
}

fn fresh_request_id() -> String {
    Uuid::new_v4().to_string()
}

async fn deploy_script(
    path: &str,
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    activate: bool,
    json: bool,
) -> Result<()> {
    let (bundle, _) = load_bundle(path)?;
    let (device, client) = connect(device_id, serial, no_bootstrap).await?;
    ensure_frida_gadget(&bundle, &device, serial, &client).await?;
    let response = client
        .script_deploy(&ScriptDeployParams {
            request_id: fresh_request_id(),
            script_id: bundle.manifest.meta.id.clone(),
            manifest: bundle.manifest_raw.clone(),
            files: to_dto(&bundle),
            ensure_scope: true,
            activate,
        })
        .await?;
    if json {
        println!("{}", serde_json::to_string(&response)?);
    } else {
        println!(
            "script_uploaded device={} script={} stored={} mode={} generation={} scope={}",
            device,
            response.script_id,
            response.stored,
            response.publish_mode,
            response.generation,
            response.scope_status
        );
        println!("content_hash={}", response.content_hash);
        println!("{}", response.message);
    }
    Ok(())
}

struct RunOptions<'a> {
    path: &'a str,
    device: Option<&'a str>,
    serial: Option<&'a str>,
    no_bootstrap: bool,
    wait_seconds: u64,
    expect: &'a str,
    no_restart: bool,
    json: bool,
}

/// On-demand Frida gadget delivery: when the bundle declares the `frida`
/// capability, resolve the device ABI, take the gadget from the local cache
/// (`~/.cache/modspec/libfrida-gadget-<abi>.so`), push it to the device
/// staging dir and ask the agent to install it next to the module APK.
async fn ensure_frida_gadget(
    bundle: &ScriptBundle,
    device: &str,
    serial: Option<&str>,
    client: &modspec_protocol::RpcClient,
) -> Result<()> {
    let needs_native = bundle.manifest.frida.is_some()
        || bundle
            .manifest
            .permissions
            .capabilities
            .iter()
            .any(|c| c == "native_hook");
    if !needs_native {
        return Ok(());
    }
    let adb = modspec_adb::Adb::resolve()?;
    let abi = adb
        .cpu_abi(serial)
        .with_context(|| "native layer: cannot resolve device ABI (adb)")?;

    // On-demand native components: push whatever is cached, then ask the
    // agent to install (idempotent).
    let cache_dir = cache_dir().join("modspec");
    for (name, stage) in [
        ("libfrida-gadget-<abi>.so", "libfrida-gadget-<abi>.so"),
        ("libmodspec_native-<abi>.so", "libmodspec_native-<abi>.so"),
    ] {
        let file = name.replace("<abi>", &abi);
        let cached = cache_dir.join(&file);
        if !cached.is_file() {
            continue; // component not requested / not cached
        }
        let staging = std::path::PathBuf::from("/data/local/tmp").join(stage.replace("<abi>", &abi));
        adb.push(serial, &cached, &staging).with_context(|| {
            format!(
                "native layer: adb push {} -> {}",
                cached.display(),
                staging.display()
            )
        })?;
    }

    let installed = client
        .install_frida_gadget(&InstallFridaGadgetParams {})
        .await
        .with_context(|| "native layer: install RPC failed")?;
    println!(
        "native_layer installed abi={} frida={} native_hook={}",
        installed.abi.as_deref().unwrap_or(&abi),
        installed.frida,
        installed.native_hook,
    );
    let _ = device;
    Ok(())
}

fn cache_dir() -> std::path::PathBuf {
    std::env::var_os("XDG_CACHE_HOME")
        .map(std::path::PathBuf::from)
        .or_else(|| {
            std::env::var_os("HOME").map(|home| std::path::PathBuf::from(home).join(".cache"))
        })
        .unwrap_or_else(|| std::path::PathBuf::from("."))
}

async fn run_script(options: RunOptions<'_>) -> Result<()> {
    let (bundle, _) = load_bundle(options.path)?;
    let expect = if options.expect == "hit" {
        ScriptExpect::Hit
    } else {
        ScriptExpect::Loaded
    };
    let session = ScriptSessionParams {
        script_id: bundle.manifest.meta.id.clone(),
        manifest: bundle.manifest_raw.clone(),
        files: to_dto(&bundle),
        packages: bundle.manifest.compatible.packages.clone(),
        wait: Duration::from_secs(options.wait_seconds),
        expect,
        no_restart: options.no_restart,
        request_id: None,
        ..Default::default()
    };

    let (device, client) = connect(options.device, options.serial, options.no_bootstrap).await?;
    ensure_frida_gadget(&bundle, &device, options.serial, &client).await?;
    if !options.json {
        println!(
            "session device={} script={} engine={} packages={}",
            device,
            bundle.manifest.meta.id,
            bundle.manifest.engine.runtime,
            bundle.manifest.compatible.packages.join(",")
        );
    }

    let session_future = run_script_session(&client, &session, |event| {
        print_session_event(&event, options.json)
    });
    tokio::pin!(session_future);
    let outcome = tokio::select! {
        result = &mut session_future => result,
        _ = tokio::signal::ctrl_c() => {
            if options.json {
                println!("{}", json!({"type": "session_interrupted"}));
            } else {
                println!("session_interrupted — the script stays deployed; re-run with the same path to resume");
            }
            std::process::exit(130);
        }
    };

    match outcome {
        Ok(outcome) => {
            if options.json {
                println!(
                    "{}",
                    serde_json::to_string(&json!({
                        "type": "session_success",
                        "script_id": bundle.manifest.meta.id,
                        "generation": outcome.generation,
                        "expected": outcome.expected.as_str(),
                        "request_id": outcome.request_id,
                        "polls": outcome.polls,
                    }))?
                );
            } else {
                println!(
                    "session_success expected={} polls={} request_id={}",
                    outcome.expected.as_str(),
                    outcome.polls,
                    outcome.request_id
                );
            }
            Ok(())
        }
        Err(ScriptSessionError::Timeout { expected }) => {
            if options.json {
                println!(
                    "{}",
                    serde_json::to_string(&json!({
                        "type": "session_failure",
                        "reason": format!("timed out waiting for script_{expected}"),
                        "hint": format!("script is deployed; start/trigger {}", bundle.manifest.compatible.packages.join(",")),
                    }))?
                );
                Ok(())
            } else {
                anyhow::bail!(
                    "session timed out waiting for script_{expected}; the script is deployed, so start/trigger {} and retry",
                    bundle.manifest.compatible.packages.join(",")
                )
            }
        }
        Err(other) => {
            if options.json {
                println!(
                    "{}",
                    serde_json::to_string(&json!({
                        "type": "session_failure",
                        "reason": other.to_string(),
                    }))?
                );
                Ok(())
            } else {
                Err(other.into())
            }
        }
    }
}

fn print_session_event(event: &ScriptSessionEvent, json: bool) {
    if json {
        let value: Value = match event {
            ScriptSessionEvent::ScriptUploaded {
                generation,
                publish_mode,
                scope_status,
                content_hash,
                message,
            } => json!({
                "type": "script_uploaded",
                "generation": generation,
                "publish_mode": publish_mode,
                "scope_status": scope_status,
                "content_hash": content_hash,
                "message": message,
            }),
            ScriptSessionEvent::DeployOnly { generation } => {
                json!({ "type": "deploy_only", "generation": generation })
            }
            ScriptSessionEvent::ReloadStarted { targets } => {
                json!({ "type": "reload_started", "targets": targets })
            }
            ScriptSessionEvent::TargetRestarted { package } => {
                json!({ "type": "target_restarted", "package": package })
            }
            ScriptSessionEvent::TargetStopped { package } => {
                json!({ "type": "target_stopped", "package": package, "action": "trigger_manually" })
            }
            ScriptSessionEvent::TargetNotInstalled { package } => {
                json!({ "type": "target_not_installed", "package": package })
            }
            ScriptSessionEvent::LaunchFailed { package, message } => {
                json!({ "type": "launch_failed", "package": package, "message": message })
            }
            ScriptSessionEvent::RestartFailed { package, message } => {
                json!({ "type": "restart_failed", "package": package, "message": message })
            }
            ScriptSessionEvent::Entry(entry) => {
                let mut value = serde_json::to_value(entry).unwrap_or(Value::Null);
                if let Some(object) = value.as_object_mut() {
                    object.insert("type".into(), json!("script_event"));
                }
                value
            }
            ScriptSessionEvent::PollRetry { attempt } => {
                json!({ "type": "poll_retry", "attempt": attempt })
            }
            ScriptSessionEvent::ScriptError { message } => {
                json!({ "type": "script_error", "message": message })
            }
            ScriptSessionEvent::Timeout { expected } => {
                json!({ "type": "session_timeout", "expected": format!("script_{}", expected.as_str()) })
            }
        };
        println!("{value}");
        return;
    }
    match event {
        ScriptSessionEvent::ScriptUploaded {
            generation,
            publish_mode,
            scope_status,
            content_hash,
            message,
        } => println!(
            "script_uploaded generation={generation} mode={publish_mode} scope={scope_status} hash={content_hash} message={message}"
        ),
        ScriptSessionEvent::DeployOnly { generation } => println!(
            "deploy_only generation={generation} — restart the hook processes externally to load the script"
        ),
        ScriptSessionEvent::ReloadStarted { targets } => {
            println!("reload_started targets={}", targets.join(","));
        }
        ScriptSessionEvent::TargetRestarted { package } => {
            println!("target_restarted package={package}");
        }
        ScriptSessionEvent::TargetStopped { package } => {
            println!("target_stopped package={package} action=trigger_manually");
        }
        ScriptSessionEvent::TargetNotInstalled { package } => {
            println!("target_not_installed package={package}");
        }
        ScriptSessionEvent::LaunchFailed { package, message } => {
            println!("launch_failed package={package} message={message}");
        }
        ScriptSessionEvent::RestartFailed { package, message } => {
            println!("restart_failed package={package} message={message}");
        }
        ScriptSessionEvent::Entry(entry) => println!(
            "event_id={} {} event={} script={} package={} message={}",
            entry.event_id,
            entry.timestamp_ms,
            entry.event,
            entry.script_id.as_deref().unwrap_or("-"),
            entry.package.as_deref().unwrap_or("-"),
            entry.message,
        ),
        ScriptSessionEvent::PollRetry { attempt } => println!(
            "poll_retry attempt={attempt} — transient transport failure; retrying with the cursor preserved"
        ),
        ScriptSessionEvent::ScriptError { message } => {
            println!("script_error message={message}");
        }
        ScriptSessionEvent::Timeout { expected } => {
            println!("session_timeout expected=script_{}", expected.as_str());
        }
    }
}

async fn list_scripts(
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    json: bool,
) -> Result<()> {
    let (_device, client) = connect(device_id, serial, no_bootstrap).await?;
    let listed = client.script_list(&ScriptListParams {}).await?;
    if json {
        println!("{}", serde_json::to_string(&listed)?);
        return Ok(());
    }
    println!(
        "active_script={}",
        listed.active_script.as_deref().unwrap_or("(none)")
    );
    for script in &listed.scripts {
        let state = if script.active { "active" } else { "inactive" };
        println!(
            "{}\t{}\t{}\thash={}\t{}",
            script.script_id,
            script.engine,
            state,
            script.content_hash,
            format_state(script)
        );
    }
    Ok(())
}

fn format_state(script: &modspec_protocol::ScriptInfo) -> String {
    let mut parts = Vec::new();
    if let Some(ms) = script.last_loaded_ms {
        parts.push(format!("loaded={ms}"));
    }
    if let Some(ms) = script.last_hit_ms {
        parts.push(format!("hit={ms}"));
    }
    if let Some(error) = &script.last_error {
        parts.push(format!("error={error}"));
    }
    parts.push(format!(
        "hits={} errors={}",
        script.hit_count, script.error_count
    ));
    parts.join(" ")
}

async fn enable_script(
    script_id: &str,
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    exclusive: bool,
    json: bool,
) -> Result<()> {
    let (_device, client) = connect(device_id, serial, no_bootstrap).await?;
    let response = client
        .script_enable(&ScriptEnableParams {
            request_id: fresh_request_id(),
            script_id: script_id.into(),
            exclusive,
        })
        .await?;
    if json {
        println!("{}", serde_json::to_string(&response)?);
    } else {
        println!(
            "script_enabled script={} generation={}",
            response.script_id, response.generation
        );
        if !response.disabled.is_empty() {
            println!("disabled_scripts={}", response.disabled.join(","));
        }
    }
    Ok(())
}

async fn disable_script(
    script_id: &str,
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    json: bool,
) -> Result<()> {
    let (_device, client) = connect(device_id, serial, no_bootstrap).await?;
    let response = client
        .script_disable(&ScriptDisableParams {
            request_id: fresh_request_id(),
            script_id: script_id.into(),
        })
        .await?;
    if json {
        println!("{}", serde_json::to_string(&response)?);
    } else {
        println!(
            "script_disabled script={} generation={}",
            response.script_id, response.generation
        );
    }
    Ok(())
}

async fn remove_script(
    script_id: &str,
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    json: bool,
) -> Result<()> {
    let (_device, client) = connect(device_id, serial, no_bootstrap).await?;
    let response = client
        .script_remove(&ScriptRemoveParams {
            request_id: fresh_request_id(),
            script_id: script_id.into(),
        })
        .await?;
    if json {
        println!("{}", serde_json::to_string(&response)?);
    } else {
        println!(
            "script_removed script={} generation={}",
            response.script_id, response.generation
        );
    }
    Ok(())
}

async fn reload_script(
    script_id: &str,
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    restart: bool,
    json: bool,
) -> Result<()> {
    let (_device, client) = connect(device_id, serial, no_bootstrap).await?;
    let response = client
        .script_reload(&ScriptReloadParams {
            request_id: fresh_request_id(),
            script_id: script_id.into(),
            restart,
        })
        .await?;
    if json {
        println!("{}", serde_json::to_string(&response)?);
        return Ok(());
    }
    println!(
        "script_reload_started script={} generation={}",
        response.script_id, response.generation
    );
    for package in &response.restarted {
        println!("target_restarted package={package}");
    }
    for package in &response.needs_trigger {
        println!("target_stopped package={package} action=trigger_manually");
    }
    for package in &response.not_installed {
        println!("target_not_installed package={package}");
    }
    let failures: BTreeMap<&str, &str> = response
        .failed
        .iter()
        .map(|(package, message)| (package.as_str(), message.as_str()))
        .collect();
    for (package, message) in failures {
        println!("restart_failed package={package} message={message}");
    }
    Ok(())
}

/// Stream journal events with a resumable cursor until Ctrl-C.
async fn follow_events(
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    script_id: Option<&str>,
    cursor: Option<i64>,
    json: bool,
) -> Result<()> {
    let (device, client) = connect(device_id, serial, no_bootstrap).await?;
    if !json {
        println!("follow device={device} script={}", script_id.unwrap_or("*"));
    }
    let mut cursor = cursor;
    let mut reported = std::collections::HashSet::new();
    loop {
        let collected = client
            .collect_logs(&modspec_protocol::CollectLogsParams {
                after_event_id: cursor,
                since_ms: None,
                limit: 200,
                rule_id: None,
                script_id: script_id.map(str::to_string),
                min_generation: None,
                exact_generation: None,
            })
            .await?;
        if collected.truncated {
            eprintln!(
                "warning: journal cursor rotated past {cursor:?}; resuming from event_id={}",
                collected.first_event_id.unwrap_or(0)
            );
        }
        cursor = Some(collected.next_event_id.max(cursor.unwrap_or(0)));
        for entry in collected.entries {
            if !reported.insert(entry.event_id) {
                continue;
            }
            if json {
                println!(
                    "{}",
                    serde_json::to_string(&json!({
                        "type": "script_event",
                        "event_id": entry.event_id,
                        "timestamp_ms": entry.timestamp_ms,
                        "level": entry.level,
                        "event": entry.event,
                        "generation": entry.generation,
                        "script_id": entry.script_id,
                        "package": entry.package,
                        "message": entry.message,
                    }))?
                );
            } else {
                println!(
                    "event_id={} event={} script={} package={} message={}",
                    entry.event_id,
                    entry.event,
                    entry.script_id.as_deref().unwrap_or("-"),
                    entry.package.as_deref().unwrap_or("-"),
                    entry.message,
                );
            }
        }
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {
                if json {
                    println!("{}", json!({"type": "follow_stopped", "cursor": cursor}));
                } else {
                    println!("follow_stopped cursor={}", cursor.unwrap_or(0));
                }
                return Ok(());
            }
            _ = tokio::time::sleep(Duration::from_millis(500)) => {}
        }
    }
}
