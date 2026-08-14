use anyhow::{Context, Result};
use modspec_core::DeviceStore;
use modspec_protocol::{is_valid_package_name, methods, GetLogsParams, InspectDeviceParams};

use crate::commands::resolve_authorized_client;
use crate::DeviceAction;

pub async fn run(action: DeviceAction) -> Result<()> {
    match action {
        DeviceAction::List => device_list(),
        DeviceAction::Status { device, offline } => device_status(device.as_deref(), offline).await,
        DeviceAction::Inspect {
            device,
            apps,
            app_limit,
            offline,
        } => device_inspect(device.as_deref(), apps, app_limit, offline).await,
        DeviceAction::Logs {
            device,
            package,
            tag,
            limit,
            since_ms,
            offline,
        } => {
            device_logs(
                device.as_deref(),
                package.as_deref(),
                tag.as_deref(),
                limit,
                since_ms,
                offline,
            )
            .await
        }
        DeviceAction::Diagnostics { device, offline } => {
            device_diagnostics(device.as_deref(), offline).await
        }
    }
}

async fn device_inspect(
    device_id: Option<&str>,
    include_apps: bool,
    app_limit: u32,
    offline: bool,
) -> Result<()> {
    let (_device, mut client) = resolve_authorized_client(device_id, offline)?;
    let params = InspectDeviceParams {
        include_apps,
        app_limit: app_limit.clamp(1, 2000),
    };
    if offline {
        let req = client.build_request(methods::INSPECT_DEVICE, serde_json::to_value(&params)?);
        println!("offline: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        return Ok(());
    }
    client.connect().await?;
    let inspection = client.inspect_device(&params).await?;
    println!("{}", serde_json::to_string_pretty(&inspection)?);
    Ok(())
}

async fn device_logs(
    device_id: Option<&str>,
    package: Option<&str>,
    tag: Option<&str>,
    limit: u32,
    since_ms: Option<i64>,
    offline: bool,
) -> Result<()> {
    if let Some(package) = package {
        if !is_valid_package_name(package) {
            anyhow::bail!("invalid package name: {package:?}");
        }
    }
    let (device, mut client) = resolve_authorized_client(device_id, offline)?;
    let params = GetLogsParams {
        package: package.map(str::to_string),
        tag: tag.map(str::to_string),
        limit: limit.clamp(1, 1000),
        since_ms,
    };
    if offline {
        let req = client.build_request(methods::GET_LOGS, serde_json::to_value(&params)?);
        println!("offline: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        return Ok(());
    }
    client.connect().await?;
    let logs = client.get_logs(&params).await?;
    println!(
        "device={} source={} root_available={} truncated={} resolved_pids={:?}",
        device.id, logs.source, logs.root_available, logs.truncated, logs.resolved_pids
    );
    for entry in logs.entries {
        println!(
            "{} {} {} pid={} message={}",
            entry.timestamp_ms,
            entry.level,
            entry.tag,
            entry
                .pid
                .map(|pid| pid.to_string())
                .unwrap_or_else(|| "-".into()),
            entry.message,
        );
    }
    if !logs.root_available {
        anyhow::bail!(
            "the agent has no root; raw logcat is unavailable (hook events still work via `collect_logs`)"
        );
    }
    Ok(())
}

async fn device_diagnostics(device_id: Option<&str>, offline: bool) -> Result<()> {
    let (_device, mut client) = resolve_authorized_client(device_id, offline)?;
    if offline {
        let req = client.build_request(methods::MODULE_DIAGNOSTICS, serde_json::Value::Null);
        println!("offline: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        return Ok(());
    }
    client.connect().await?;
    let diagnostics = client.module_diagnostics().await?;
    println!("{}", serde_json::to_string_pretty(&diagnostics)?);
    Ok(())
}

fn device_list() -> Result<()> {
    let store = DeviceStore::default();
    let config = store
        .load()
        .with_context(|| format!("load {}", store.path().display()))?;

    if config.devices.is_empty() {
        println!("no paired devices (use `modspec pair scan`)");
        return Ok(());
    }

    let default = config.default_device.as_deref().unwrap_or("-");
    println!("devices.toml: {}", store.path().display());
    println!("default: {default}");
    for d in &config.devices {
        let mark = if Some(d.id.as_str()) == config.default_device.as_deref() {
            "*"
        } else {
            " "
        };
        println!(
            "{mark} {}\t{}\t{}\t{}",
            d.id,
            d.name,
            d.host,
            d.paired_at.to_rfc3339()
        );
    }
    Ok(())
}

async fn device_status(device_id: Option<&str>, offline: bool) -> Result<()> {
    let (device, mut client) = resolve_authorized_client(device_id, offline)?;

    if offline {
        let req = client.build_request(methods::GET_STATUS, serde_json::Value::Null);
        println!("offline: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        println!(
            "device {} ({}) — status unknown (offline mode)",
            device.id, device.host
        );
        return Ok(());
    }

    client
        .connect()
        .await
        .with_context(|| format!("connect to {}", device.host))?;

    match client.get_status().await {
        Ok(status) => {
            println!("device {} online", device.id);
            println!("{}", serde_json::to_string_pretty(&status)?);
        }
        Err(e) => {
            println!("device {} ({}) — error: {e}", device.id, device.host);
        }
    }
    Ok(())
}
