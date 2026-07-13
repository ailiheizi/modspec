use anyhow::{Context, Result};
use modspec_core::DeviceStore;
use modspec_protocol::{methods, RpcClient};

use crate::DeviceAction;

pub async fn run(action: DeviceAction) -> Result<()> {
    match action {
        DeviceAction::List => device_list(),
        DeviceAction::Status { device, offline } => device_status(device.as_deref(), offline).await,
    }
}

fn device_list() -> Result<()> {
    let store = DeviceStore::default();
    let config = store.load().with_context(|| format!("load {}", store.path().display()))?;

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
            d.id, d.name, d.host, d.paired_at.to_rfc3339()
        );
    }
    Ok(())
}

async fn device_status(device_id: Option<&str>, offline: bool) -> Result<()> {
    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)
        .with_context(|| "resolve target device")?;

    let mut client = RpcClient::from_device(&device.host, device.http_port, device.ws_port);
    client.set_offline(offline);

    if offline {
        let req = client.build_request(methods::GET_STATUS, serde_json::Value::Null);
        println!("offline: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        println!("device {} ({}) — status unknown (offline mode)", device.id, device.host);
        return Ok(());
    }

    client.connect().await.with_context(|| format!("connect to {}", device.host))?;

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
