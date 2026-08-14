//! Running-process inventory: `modspec process list`.

use anyhow::Result;
use modspec_protocol::{is_valid_package_name, methods, ProcessListParams};

use crate::commands::resolve_authorized_client;
use crate::ProcessAction;

pub async fn run(action: ProcessAction) -> Result<()> {
    match action {
        ProcessAction::List {
            package,
            device,
            limit,
            offline,
        } => process_list(package.as_deref(), device.as_deref(), limit, offline).await,
    }
}

async fn process_list(
    package: Option<&str>,
    device_id: Option<&str>,
    limit: u32,
    offline: bool,
) -> Result<()> {
    if let Some(package) = package {
        if !is_valid_package_name(package) {
            anyhow::bail!("invalid package name: {package:?}");
        }
    }
    let (device, mut client) = resolve_authorized_client(device_id, offline)?;
    let params = ProcessListParams {
        package: package.map(str::to_string),
        limit: limit.clamp(1, 2000),
    };
    if offline {
        let req = client.build_request(methods::PROCESS_LIST, serde_json::to_value(&params)?);
        println!("offline: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        return Ok(());
    }
    client.connect().await?;
    let resp = client.process_list(&params).await?;
    println!(
        "device={} source={} total={} truncated={}",
        device.id, resp.source, resp.total, resp.truncated
    );
    for process in resp.processes {
        println!(
            "pid={} uid={} state={} package={} name={}",
            process.pid,
            process
                .uid
                .map(|uid| uid.to_string())
                .unwrap_or_else(|| "-".into()),
            process.state,
            process.package.as_deref().unwrap_or("-"),
            process.name,
        );
    }
    Ok(())
}
