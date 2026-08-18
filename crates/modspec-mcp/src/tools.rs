use anyhow::{Context, Result};
use modspec_core::{validate_profile, validate_rule, DeviceStore, Profile, RuleFile};
use modspec_protocol::{
    AppInfoParams, AppListParams, AppScope, ApplyProfileParams, CollectLogsParams,
    InspectDeviceParams, ProcessListParams, RpcClient, SoftRestartParams, VerifyParams,
};
use serde_json::{json, Value};

pub fn tool_definitions() -> Value {
    json!([
        {
            "name": "validate",
            "description": "Validate a .mspec.toml profile or .rule.toml rule file",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Path to profile or rule file" }
                },
                "required": ["path"]
            }
        },
        {
            "name": "show",
            "description": "Show profile or rule file as JSON",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "path": { "type": "string" }
                },
                "required": ["path"]
            }
        },
        {
            "name": "list_rules",
            "description": "List rules from bundled rules/index.toml",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "list_devices",
            "description": "List paired devices from devices.toml",
            "inputSchema": { "type": "object", "properties": {} }
        },
        {
            "name": "device_status",
            "description": "Query modspec-agent device status via HTTP RPC",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "device": { "type": "string", "description": "Device id (default: configured default)" }
                }
            }
        },
        {
            "name": "device_inspect",
            "description": "Read-only hardware/software/display/memory/storage/runtime inventory of a paired device. Optionally include the installed-app summary (bounded).",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "device": { "type": "string", "description": "Device id (default: configured default)" },
                    "apps": { "type": "boolean", "description": "Include bounded installed-app entries (default: counts only)" },
                    "app_limit": { "type": "integer", "description": "Max package entries with apps=true (1..2000)" }
                }
            }
        },
        {
            "name": "app_list",
            "description": "List installed packages on a paired device: package, version, system/user, enabled. Bounded and filterable.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "device": { "type": "string", "description": "Device id (default: configured default)" },
                    "scope": { "type": "string", "enum": ["all", "system", "user"], "description": "Package universe (default: all)" },
                    "limit": { "type": "integer", "description": "Max records (1..2000, default 200)" },
                    "filter": { "type": "string", "description": "Case-insensitive substring filter on package names" }
                }
            }
        },
        {
            "name": "app_info",
            "description": "Structured detail for one installed package: version, system/enabled, installer, launcher activity, component counts, uid.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "device": { "type": "string", "description": "Device id (default: configured default)" },
                    "package": { "type": "string", "description": "Android package name" }
                },
                "required": ["package"]
            }
        },
        {
            "name": "process_list",
            "description": "List running processes on a paired device (optionally for one package): pid, uid, state, package.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "device": { "type": "string", "description": "Device id (default: configured default)" },
                    "package": { "type": "string", "description": "Restrict to one package" },
                    "limit": { "type": "integer", "description": "Max records (1..2000, default 200)" }
                }
            }
        },
        {
            "name": "apply_profile",
            "description": "Apply a profile to a paired device",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "path": { "type": "string" },
                    "device": { "type": "string" },
                    "dry_run": { "type": "boolean", "description": "Validate and plan only" }
                },
                "required": ["path"]
            }
        },
        {
            "name": "script_validate",
            "description": "Validate a JS/Lua script package directory (manifest.toml + sources) offline",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Path to a script package directory" }
                },
                "required": ["path"]
            }
        },
        {
            "name": "script_list",
            "description": "List stored script packages on a paired device with lifecycle state",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "device": { "type": "string", "description": "Device id (default: configured default)" }
                }
            }
        },
        {
            "name": "script_deploy",
            "description": "Deploy a script package directory to a paired device (validates locally first)",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Path to a script package directory" },
                    "device": { "type": "string", "description": "Device id (default: configured default)" },
                    "activate": { "type": "boolean", "description": "Make it the active script (default: true)" }
                },
                "required": ["path"]
            }
        },
        {
            "name": "script_enable",
            "description": "Make a stored script the active one (exclusive by default)",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "script_id": { "type": "string", "description": "Script id" },
                    "device": { "type": "string", "description": "Device id (default: configured default)" },
                    "exclusive": { "type": "boolean", "description": "Disable other scripts (default: true)" }
                },
                "required": ["script_id"]
            }
        },
        {
            "name": "script_disable",
            "description": "Deactivate a stored script without removing its files",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "script_id": { "type": "string" },
                    "device": { "type": "string" }
                },
                "required": ["script_id"]
            }
        },
        {
            "name": "script_remove",
            "description": "Delete a stored script package and its state",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "script_id": { "type": "string" },
                    "device": { "type": "string" }
                },
                "required": ["script_id"]
            }
        },
        {
            "name": "soft_restart",
            "description": "Hot-reload rules or soft-restart hook processes on a paired device",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "rules_only": { "type": "boolean", "description": "Republish rules only, keep processes running (default: false)" },
                    "device": { "type": "string", "description": "Device id (default: configured default)" }
                }
            }
        },
        {
            "name": "collect_logs",
            "description": "Collect hook event log entries from a paired device (cursor: after_event_id)",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "after_event_id": { "type": "integer", "description": "Return only entries with event_id after this cursor" },
                    "limit": { "type": "integer", "description": "Max entries (1..1000, default 200)" },
                    "rule_id": { "type": "string", "description": "Restrict to one rule" },
                    "script_id": { "type": "string", "description": "Restrict to one script" },
                    "exact_generation": { "type": "integer", "description": "Restrict to one rules generation" },
                    "device": { "type": "string", "description": "Device id (default: configured default)" }
                }
            }
        },
        {
            "name": "verify_profile",
            "description": "Verify live rules state on a paired device and report drift",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "profile_id": { "type": "string", "description": "Profile id to compare (default: active profile)" },
                    "device": { "type": "string", "description": "Device id (default: configured default)" }
                }
            }
        }
    ])
}

pub async fn call_tool(name: &str, args: &Value) -> Result<String> {
    match name {
        "validate" => tool_validate(args),
        "show" => tool_show(args),
        "list_rules" => tool_list_rules(),
        "list_devices" => tool_list_devices(),
        "device_status" => tool_device_status(args).await,
        "device_inspect" => tool_device_inspect(args).await,
        "app_list" => tool_app_list(args).await,
        "app_info" => tool_app_info(args).await,
        "process_list" => tool_process_list(args).await,
        "apply_profile" => tool_apply_profile(args).await,
        "script_validate" => tool_script_validate(args),
        "script_list" => tool_script_list(args).await,
        "script_deploy" => tool_script_deploy(args).await,
        "script_enable" => tool_script_enable(args).await,
        "script_disable" => tool_script_disable(args).await,
        "script_remove" => tool_script_remove(args).await,
        "soft_restart" => tool_soft_restart(args).await,
        "collect_logs" => tool_collect_logs(args).await,
        "verify_profile" => tool_verify_profile(args).await,
        other => anyhow::bail!("unknown tool: {other}"),
    }
}

fn tool_validate(args: &Value) -> Result<String> {
    let path = arg_str(args, "path")?;
    if path.ends_with(".rule.toml") {
        let rule = RuleFile::from_file(&path).with_context(|| format!("read rule {path}"))?;
        validate_rule(&rule)?;
        Ok(format!(
            "OK rule {} ({} hooks, {} variants)",
            rule.meta.id,
            rule.hooks.len(),
            rule.variants.len()
        ))
    } else {
        let profile = Profile::from_file(&path).with_context(|| format!("read profile {path}"))?;
        validate_profile(&profile)?;
        Ok(format!(
            "OK profile {} ({} mods)",
            profile.meta.id,
            profile.mods.len()
        ))
    }
}

fn tool_show(args: &Value) -> Result<String> {
    let path = arg_str(args, "path")?;
    if path.ends_with(".rule.toml") {
        let rule = RuleFile::from_file(&path)?;
        Ok(serde_json::to_string_pretty(&rule)?)
    } else {
        let profile = Profile::from_file(&path)?;
        Ok(serde_json::to_string_pretty(&profile)?)
    }
}

fn tool_list_rules() -> Result<String> {
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

    let index: Index = toml::from_str(include_str!("../../../rules/index.toml"))?;
    let lines: Vec<String> = index
        .rules
        .iter()
        .map(|r| format!("{}\t{}\t{}", r.id, r.path, r.tags.join(",")))
        .collect();
    Ok(lines.join("\n"))
}

fn tool_list_devices() -> Result<String> {
    let store = DeviceStore::default();
    let config = store.load()?;
    if config.devices.is_empty() {
        return Ok("no paired devices".into());
    }
    let mut lines = vec![format!("config: {}", store.path().display())];
    for d in &config.devices {
        let mark = if Some(d.id.as_str()) == config.default_device.as_deref() {
            "*"
        } else {
            " "
        };
        lines.push(format!("{mark} {}\t{}\t{}", d.id, d.name, d.host));
    }
    Ok(lines.join("\n"))
}

async fn tool_device_status(args: &Value) -> Result<String> {
    let device_id = args.get("device").and_then(|v| v.as_str());
    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)?;

    let mut client = RpcClient::from_device(&device.host, device.http_port, device.ws_port)
        .with_auth_token(device.auth_token.clone());
    client.connect().await?;
    let status = client.get_status().await?;
    Ok(serde_json::to_string_pretty(&status)?)
}

async fn tool_device_inspect(args: &Value) -> Result<String> {
    let (_device, mut client) = resolve_rpc(args).await?;
    let params = InspectDeviceParams {
        include_apps: args.get("apps").and_then(|v| v.as_bool()).unwrap_or(false),
        app_limit: args
            .get("app_limit")
            .and_then(|v| v.as_u64())
            .unwrap_or(200)
            .clamp(1, 2000) as u32,
    };
    client.connect().await?;
    let inspection = client.inspect_device(&params).await?;
    Ok(serde_json::to_string_pretty(&inspection)?)
}

async fn tool_app_list(args: &Value) -> Result<String> {
    let (_device, mut client) = resolve_rpc(args).await?;
    let scope = args.get("scope").and_then(|v| v.as_str()).unwrap_or("all");
    let params = AppListParams {
        scope: match scope {
            "system" => AppScope::System,
            "user" => AppScope::User,
            _ => AppScope::All,
        },
        limit: args
            .get("limit")
            .and_then(|v| v.as_u64())
            .unwrap_or(200)
            .clamp(1, 2000) as u32,
        filter: args
            .get("filter")
            .and_then(|v| v.as_str())
            .map(str::to_string),
    };
    client.connect().await?;
    let response = client.app_list(&params).await?;
    Ok(serde_json::to_string_pretty(&response)?)
}

async fn tool_app_info(args: &Value) -> Result<String> {
    let package = args
        .get("package")
        .and_then(|v| v.as_str())
        .context("missing argument: package")?;
    if !modspec_protocol::is_valid_package_name(package) {
        anyhow::bail!("invalid package name: {package:?}");
    }
    let (_device, mut client) = resolve_rpc(args).await?;
    client.connect().await?;
    let info = client
        .app_info(&AppInfoParams {
            package: package.into(),
        })
        .await?;
    Ok(serde_json::to_string_pretty(&info)?)
}

async fn tool_process_list(args: &Value) -> Result<String> {
    let package = args.get("package").and_then(|v| v.as_str());
    let (_device, mut client) = resolve_rpc(args).await?;
    let params = ProcessListParams {
        package: package.map(str::to_string),
        limit: args
            .get("limit")
            .and_then(|v| v.as_u64())
            .unwrap_or(200)
            .clamp(1, 2000) as u32,
    };
    client.connect().await?;
    let response = client.process_list(&params).await?;
    Ok(serde_json::to_string_pretty(&response)?)
}

/// Shared resolver for read-only tools: authorized paired device + RPC client.
async fn resolve_rpc(args: &Value) -> Result<(modspec_core::StoredDevice, RpcClient)> {
    let device_id = args.get("device").and_then(|v| v.as_str());
    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)?;
    device.require_auth()?;
    let client = RpcClient::from_device(&device.host, device.http_port, device.ws_port)
        .with_auth_token(device.auth_token.clone());
    Ok((device.clone(), client))
}

async fn tool_apply_profile(args: &Value) -> Result<String> {
    let path = arg_str(args, "path")?;
    let device_id = args.get("device").and_then(|v| v.as_str());
    let dry_run = args
        .get("dry_run")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    let profile = Profile::from_file(&path)?;
    validate_profile(&profile)?;

    if dry_run {
        let plan: Vec<String> = profile
            .mods
            .iter()
            .map(|m| format!("- {} [{}]", m.id(), m.enabled()))
            .collect();
        return Ok(format!(
            "dry-run profile {} ({} mods):\n{}",
            profile.meta.id,
            profile.mods.len(),
            plan.join("\n")
        ));
    }

    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)?;
    device.require_auth()?;

    let params = ApplyProfileParams {
        profile,
        dry_run: false,
        only: vec![],
    };

    let mut client = RpcClient::from_device(&device.host, device.http_port, device.ws_port)
        .with_auth_token(device.auth_token.clone());
    client.connect().await?;
    let resp = client.apply_profile(&params).await?;
    Ok(format!("apply started: job_id={}", resp.job_id))
}

fn arg_str(args: &Value, key: &str) -> Result<String> {
    args.get(key)
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .with_context(|| format!("missing argument: {key}"))
}

fn load_script_bundle(path: &str) -> Result<modspec_core::ScriptBundle> {
    let bundle = modspec_core::ScriptBundle::from_dir(std::path::Path::new(path))
        .with_context(|| format!("read script package {path}"))?;
    modspec_core::validate_script_bundle(&bundle)
        .with_context(|| format!("validate script package {path}"))?;
    Ok(bundle)
}

fn tool_script_validate(args: &Value) -> Result<String> {
    let path = arg_str(args, "path")?;
    let bundle = load_script_bundle(&path)?;
    Ok(serde_json::to_string_pretty(&json!({
        "ok": true,
        "script_id": bundle.manifest.meta.id,
        "engine": bundle.manifest.engine.runtime,
        "entrypoint": bundle.entrypoint(),
        "packages": bundle.manifest.compatible.packages,
        "target_packages": bundle.manifest.compatible.target_packages,
        "content_hash": bundle.content_hash(),
    }))?)
}

async fn tool_script_list(args: &Value) -> Result<String> {
    let (_device, mut client) = resolve_rpc(args).await?;
    client.connect().await?;
    let response = client.script_list(&Default::default()).await?;
    Ok(serde_json::to_string_pretty(&response)?)
}

async fn tool_script_deploy(args: &Value) -> Result<String> {
    let path = arg_str(args, "path")?;
    let bundle = load_script_bundle(&path)?;
    let activate = args
        .get("activate")
        .and_then(|v| v.as_bool())
        .unwrap_or(true);
    let (_device, mut client) = resolve_rpc(args).await?;
    client.connect().await?;
    let response = client
        .script_deploy(&modspec_protocol::ScriptDeployParams {
            request_id: uuid::Uuid::new_v4().to_string(),
            script_id: bundle.manifest.meta.id.clone(),
            manifest: bundle.manifest_raw.clone(),
            files: bundle
                .files
                .iter()
                .map(|file| modspec_protocol::ScriptFileDto {
                    name: file.name.clone(),
                    content: file.content.clone(),
                })
                .collect(),
            ensure_scope: true,
            activate,
        })
        .await?;
    Ok(serde_json::to_string_pretty(&response)?)
}

async fn tool_script_enable(args: &Value) -> Result<String> {
    let script_id = arg_str(args, "script_id")?;
    let exclusive = args
        .get("exclusive")
        .and_then(|v| v.as_bool())
        .unwrap_or(true);
    let (_device, mut client) = resolve_rpc(args).await?;
    client.connect().await?;
    let response = client
        .script_enable(&modspec_protocol::ScriptEnableParams {
            request_id: uuid::Uuid::new_v4().to_string(),
            script_id,
            exclusive,
        })
        .await?;
    Ok(serde_json::to_string_pretty(&response)?)
}

async fn tool_script_disable(args: &Value) -> Result<String> {
    let script_id = arg_str(args, "script_id")?;
    let (_device, mut client) = resolve_rpc(args).await?;
    client.connect().await?;
    let response = client
        .script_disable(&modspec_protocol::ScriptDisableParams {
            request_id: uuid::Uuid::new_v4().to_string(),
            script_id,
        })
        .await?;
    Ok(serde_json::to_string_pretty(&response)?)
}

async fn tool_script_remove(args: &Value) -> Result<String> {
    let script_id = arg_str(args, "script_id")?;
    let (_device, mut client) = resolve_rpc(args).await?;
    client.connect().await?;
    let response = client
        .script_remove(&modspec_protocol::ScriptRemoveParams {
            request_id: uuid::Uuid::new_v4().to_string(),
            script_id,
        })
        .await?;
    Ok(serde_json::to_string_pretty(&response)?)
}

async fn tool_soft_restart(args: &Value) -> Result<String> {
    let rules_only = args
        .get("rules_only")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let (_device, mut client) = resolve_rpc(args).await?;
    client.connect().await?;
    let resp = client
        .soft_restart(&SoftRestartParams { rules_only })
        .await?;
    let summary = if resp.hot_reload_unsupported {
        format!("hot reload unsupported: {}", resp.message)
    } else if resp.hot_reload_failed {
        format!("hot reload failed: {}", resp.message)
    } else {
        format!(
            "hot reload ok: {} targets running, {} packages restarted",
            resp.running_targets.len(),
            resp.restarted_packages.len()
        )
    };
    Ok(serde_json::to_string_pretty(&json!({
        "hot_reload_ok": resp.hot_reload_ok,
        "hot_reload_failed": resp.hot_reload_failed,
        "hot_reload_unsupported": resp.hot_reload_unsupported,
        "running_targets": resp.running_targets,
        "restarted_packages": resp.restarted_packages,
        "message": resp.message,
        "summary": summary,
    }))?)
}

async fn tool_collect_logs(args: &Value) -> Result<String> {
    let (_device, mut client) = resolve_rpc(args).await?;
    let params = CollectLogsParams {
        after_event_id: args.get("after_event_id").and_then(|v| v.as_i64()),
        since_ms: None,
        limit: args
            .get("limit")
            .and_then(|v| v.as_u64())
            .unwrap_or(200)
            .clamp(1, 1000) as u32,
        rule_id: args
            .get("rule_id")
            .and_then(|v| v.as_str())
            .map(str::to_string),
        script_id: args
            .get("script_id")
            .and_then(|v| v.as_str())
            .map(str::to_string),
        min_generation: None,
        exact_generation: args.get("exact_generation").and_then(|v| v.as_i64()),
    };
    client.connect().await?;
    let resp = client.collect_logs(&params).await?;
    let entries: Vec<Value> = resp
        .entries
        .iter()
        .map(|e| {
            json!({
                "event_id": e.event_id,
                "timestamp_ms": e.timestamp_ms,
                "event": e.event,
                "rule_id": e.rule_id,
                "script_id": e.script_id,
                "package": e.package,
                "message": e.message,
            })
        })
        .collect();
    Ok(serde_json::to_string(&json!({
        "count": entries.len(),
        "next_event_id": resp.next_event_id,
        "truncated": resp.truncated,
        "source": resp.source,
        "entries": entries,
    }))?)
}

async fn tool_verify_profile(args: &Value) -> Result<String> {
    let profile_id = args
        .get("profile_id")
        .and_then(|v| v.as_str())
        .map(str::to_string);
    let (_device, mut client) = resolve_rpc(args).await?;
    client.connect().await?;
    let resp = client.verify(&VerifyParams { profile_id }).await?;
    let drift_count = resp.drift.len();
    let lines: Vec<String> = resp
        .drift
        .iter()
        .map(|item| {
            let target = item
                .mod_id
                .as_deref()
                .or(item.expected.as_deref())
                .unwrap_or("?");
            let kind = item.kind.as_deref().unwrap_or("drift");
            match item.reason.as_deref() {
                Some(reason) => format!("- [{kind}] {target}: {reason}"),
                None => format!(
                    "- [{kind}] {target}: expected {:?}, actual {:?}",
                    item.expected, item.actual
                ),
            }
        })
        .collect();
    let summary = if lines.is_empty() {
        "no drift detected".to_string()
    } else {
        format!("{} drifted item(s)\n{}", lines.len(), lines.join("\n"))
    };
    Ok(serde_json::to_string(&json!({
        "drift": resp.drift,
        "drift_count": drift_count,
        "summary": summary,
    }))?)
}
