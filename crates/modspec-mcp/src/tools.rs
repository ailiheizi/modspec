use anyhow::{Context, Result};
use modspec_core::{DeviceStore, Profile, RuleFile, validate_profile, validate_rule};
use modspec_protocol::{ApplyProfileParams, RpcClient};
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
        "apply_profile" => tool_apply_profile(args).await,
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

    let mut client = RpcClient::from_device(&device.host, device.http_port, device.ws_port);
    client.connect().await?;
    let status = client.get_status().await?;
    Ok(serde_json::to_string_pretty(&status)?)
}

async fn tool_apply_profile(args: &Value) -> Result<String> {
    let path = arg_str(args, "path")?;
    let device_id = args.get("device").and_then(|v| v.as_str());
    let dry_run = args.get("dry_run").and_then(|v| v.as_bool()).unwrap_or(false);

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

    let params = ApplyProfileParams {
        profile,
        dry_run: false,
        only: vec![],
    };

    let mut client = RpcClient::from_device(&device.host, device.http_port, device.ws_port);
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
