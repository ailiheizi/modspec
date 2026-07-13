use anyhow::{Context, Result};
use modspec_core::{DeviceStore, StoredDevice};
use modspec_protocol::{health_check, PairConfirm, PairRequest};

use crate::PairAction;

pub async fn run(action: PairAction) -> Result<()> {
    match action {
        PairAction::Scan {
            code,
            host,
            offline,
        } => pair_scan(&code, &host, offline).await,
    }
}

async fn pair_scan(code: &str, host: &str, offline: bool) -> Result<()> {
    let request = PairRequest {
        code: code.to_string(),
        client_name: Some("modspec-cli".into()),
    };

    if offline {
        println!("offline: would POST http://{host}:8764/pair");
        println!("{}", serde_json::to_string_pretty(&request)?);
    } else {
        let http_base = format!("http://{host}:8764");
        if !health_check(host, 8764).await.unwrap_or(false) {
            anyhow::bail!(
                "agent not reachable at {http_base}/health — ensure modspec-agent is running and on the same LAN"
            );
        }

        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(10))
            .build()?;
        let confirm: PairConfirm = client
            .post(format!("{http_base}/pair"))
            .json(&request)
            .send()
            .await
            .with_context(|| format!("POST {http_base}/pair"))?
            .error_for_status()
            .with_context(|| "pair request rejected")?
            .json()
            .await
            .with_context(|| "decode pair response")?;

        let device = StoredDevice::from_pairing(
            &confirm.device_id,
            &confirm.device_name,
            host,
        );

        let store = DeviceStore::default();
        let mut config = store.load()?;
        if let Some(existing) = config.devices.iter_mut().find(|d| d.id == device.id) {
            *existing = device.clone();
        } else {
            config.devices.push(device.clone());
        }
        config.default_device = Some(device.id.clone());
        store.save(&config)?;

        println!(
            "paired {} ({}) model={} android={:?}",
            device.id, device.name, confirm.model, confirm.android_version
        );
        return Ok(());
    }

    let device_id = format!("device-{}", &code[..code.len().min(6)]);
    let device = StoredDevice::from_pairing(&device_id, format!("Device @ {host}"), host);

    let store = DeviceStore::default();
    store
        .upsert_device(device.clone())
        .with_context(|| format!("save device to {}", store.path().display()))?;

    println!(
        "paired device {} ({}) saved to {} (offline stub)",
        device.id,
        device.name,
        store.path().display()
    );
    Ok(())
}
