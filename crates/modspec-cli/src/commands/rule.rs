use std::time::Duration;

use anyhow::{Context, Result};
use modspec_core::{validate_rule, DeviceStore, RuleFile};
use modspec_protocol::{Expect, RuleSessionParams, SessionError, SessionEvent};

use crate::commands::connect_device;
use crate::RuleAction;

pub async fn run(action: RuleAction) -> Result<()> {
    match action {
        RuleAction::Validate { path } => super::validate::validate_rule_file(&path),
        RuleAction::Init { id, package, output } => rule_init(&id, &package, output.as_deref()),
        RuleAction::List => list_rules_index(),
        RuleAction::Run {
            path,
            device,
            serial,
            no_bootstrap,
            wait,
            expect,
            no_restart,
        } => {
            run_rule(
                &path,
                device.as_deref(),
                serial.as_deref(),
                no_bootstrap,
                wait,
                &expect,
                no_restart,
            )
            .await
        }
    }
}

async fn run_rule(
    path: &str,
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    wait_seconds: u64,
    expect: &str,
    no_restart: bool,
) -> Result<()> {
    let content = std::fs::read_to_string(path).with_context(|| format!("read rule {path}"))?;
    let rule: RuleFile = content
        .parse()
        .with_context(|| format!("parse rule {path}"))?;
    validate_rule(&rule)?;

    let expect = if expect == "hit" {
        Expect::Hit
    } else {
        Expect::Loaded
    };
    let session = RuleSessionParams {
        rule_id: rule.meta.id.clone(),
        content,
        packages: rule.compatible.packages.clone(),
        wait: Duration::from_secs(wait_seconds),
        expect,
        no_restart,
        ..Default::default()
    };

    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)?;
    if device.auth_token.is_none() {
        anyhow::bail!(
            "device {} has no bearer token (stale or pre-token record); re-pair first: `modspec pair scan`",
            device.id
        );
    }

    // Preflight + repair (and optional Agent bootstrap) before the session so a
    // stale adb forward or a dead agent cannot hang the deploy below.
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

    println!(
        "session device={} rule={} targets={}",
        device.id,
        rule.meta.id,
        rule.compatible.packages.join(",")
    );

    let outcome = {
        let session_future =
            modspec_protocol::session::run_rule_session(&client, &session, |event| {
                print_session_event(&event)
            });
        tokio::pin!(session_future);
        tokio::select! {
            result = &mut session_future => result,
            _ = tokio::signal::ctrl_c() => {
                anyhow::bail!("session interrupted; no remote process was left attached");
            }
        }
    };

    match outcome {
        Ok(outcome) => {
            println!(
                "session_success expected={} polls={}",
                outcome.expected.as_str(),
                outcome.polls
            );
            Ok(())
        }
        Err(SessionError::Timeout { expected }) => anyhow::bail!(
            "session timed out waiting for hook_{expected}; rule is deployed, so start/trigger {} and retry",
            rule.compatible.packages.join(",")
        ),
        Err(other) => Err(other.into()),
    }
}

fn print_session_event(event: &SessionEvent) {
    match event {
        SessionEvent::RuleUploaded {
            generation,
            publish_mode,
            scope_status,
            message,
        } => println!(
            "rule_uploaded generation={} mode={} scope={} message={}",
            generation, publish_mode, scope_status, message
        ),
        SessionEvent::DeployOnly { generation } => println!(
            "deploy_only generation={} — restart target(s)/framework externally to load the rule",
            generation
        ),
        SessionEvent::ReloadStarted { targets } => {
            println!("reload_started targets={}", targets.join(","));
        }
        SessionEvent::TargetRestarted { package } => {
            println!("target_restarted package={package}");
        }
        SessionEvent::TargetStopped { package } => {
            println!("target_stopped package={package} action=trigger_manually");
        }
        SessionEvent::TargetNotInstalled { package } => {
            println!("target_not_installed package={package}");
        }
        SessionEvent::LaunchFailed { package, message } => {
            println!("launch_failed package={package} message={message}");
        }
        SessionEvent::RestartFailed { package, message } => {
            println!("restart_failed package={package} message={message}");
        }
        SessionEvent::Entry(entry) => println!(
            "event_id={} {} event={} rule={} package={} message={}",
            entry.event_id,
            entry.timestamp_ms,
            entry.event,
            entry.rule_id.as_deref().unwrap_or("-"),
            entry.package.as_deref().unwrap_or("-"),
            entry.message,
        ),
        SessionEvent::PollRetry { attempt } => println!(
            "poll_retry attempt={attempt} — transient transport failure; retrying with the cursor preserved"
        ),
        SessionEvent::Timeout { expected } => {
            println!("session_timeout expected=hook_{}", expected.as_str());
        }
    }
}

fn rule_init(id: &str, package: &str, output: Option<&str>) -> Result<()> {
    let rule = RuleFile::template(id, package);
    validate_rule(&rule)?;

    let output_path = match output {
        Some(path) => std::path::PathBuf::from(path),
        None => {
            let repo = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
            repo.join("rules").join(format!("{id}.rule.toml"))
        }
    };
    if let Some(parent) = output_path.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("create {}", parent.display()))?;
    }
    let text = toml::to_string(&rule).with_context(|| "serialize rule template")?;
    if output_path.exists() {
        anyhow::bail!(
            "refusing to overwrite {} (pass --output to choose another path)",
            output_path.display()
        );
    }
    std::fs::write(&output_path, text)
        .with_context(|| format!("write {}", output_path.display()))?;
    println!("wrote rule template to {}", output_path.display());
    println!("next: edit the placeholder target/action, then `modspec rule validate` it");
    Ok(())
}

fn list_rules_index() -> Result<()> {
    #[derive(serde::Deserialize)]
    struct Index {
        rules: Vec<RuleEntry>,
    }
    #[derive(serde::Deserialize)]
    struct RuleEntry {
        id: String,
        path: String,
        tags: Vec<String>,
    }

    let index: Index = toml::from_str(include_str!("../../../../rules/index.toml"))?;
    for r in index.rules {
        println!("{}\t{}\t{}", r.id, r.path, r.tags.join(","));
    }
    Ok(())
}
