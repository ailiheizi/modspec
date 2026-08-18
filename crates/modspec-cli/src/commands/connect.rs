//! `modspec connect` — semantic connection manager.
//!
//! Short health preflight → classify (`stale_forward` / `agent_unreachable` /
//! `unauthorized`) → rebuild the ADB forward for the selected paired device →
//! optionally bootstrap the Agent's exported MainActivity. Raw ADB stays an
//! implementation detail behind [`AdbForwarder`]; the orchestration itself is
//! in `modspec_protocol::connection` and is fake-transport tested.

use std::time::Duration;

use anyhow::{Context, Result};
use modspec_core::{DeviceStore, StoredDevice};
use modspec_protocol::connection::{
    ensure_connection, is_loopback_host, ConnectionIssue, ConnectionOptions, ConnectionStatus,
    ForwardManager,
};
use modspec_protocol::RpcClient;

/// [`ForwardManager`] backed by the typed modspec-adb layer.
pub(crate) struct AdbForwarder {
    adb: modspec_adb::Adb,
    serial: Option<String>,
}

impl ForwardManager for AdbForwarder {
    fn describe(&self) -> String {
        match &self.serial {
            Some(serial) => format!("adb serial={serial}"),
            None => "adb (single online device)".into(),
        }
    }

    fn ensure_forward(&self, local: u16, remote: u16) -> std::result::Result<(), String> {
        self.adb
            .forward(self.serial.as_deref(), local, remote)
            .map_err(|e| e.to_string())
    }

    fn bootstrap(&self) -> std::result::Result<(), String> {
        self.adb
            .bootstrap_agent(self.serial.as_deref())
            .map_err(|e| e.to_string())
    }

    fn adb_hint(&self) -> Option<String> {
        self.adb
            .discover()
            .map(|devices| {
                if devices.is_empty() {
                    "adb devices: none — is the device plugged in with USB debugging enabled?"
                        .into()
                } else {
                    format!(
                        "adb devices: {}",
                        devices
                            .iter()
                            .map(|d| format!("{}={}", d.serial, d.state))
                            .collect::<Vec<_>>()
                            .join(", ")
                    )
                }
            })
            .ok()
    }
}

/// Build the ADB forwarder only for the loopback (adb-forward) topology.
pub(crate) fn build_forwarder(host: &str, serial: Option<String>) -> Option<AdbForwarder> {
    if !is_loopback_host(host) {
        return None;
    }
    match modspec_adb::Adb::resolve() {
        Ok(adb) => Some(AdbForwarder { adb, serial }),
        Err(_) => None,
    }
}

/// Preflight + repair + (optional) bootstrap a connection to `device`, then
/// return the authenticated RPC client. Bails with the classification when the
/// connection cannot be established.
pub(crate) async fn connect_device(
    device: &StoredDevice,
    serial: Option<String>,
    bootstrap: bool,
    preflight_timeout: Duration,
    repair_retries: u32,
) -> Result<(RpcClient, ConnectionStatus)> {
    let mut client = RpcClient::from_device(&device.host, device.http_port, device.ws_port)
        .with_auth_token(device.auth_token.clone());
    client
        .connect()
        .await
        .with_context(|| format!("connect to {}", device.host))?;

    let opts = ConnectionOptions {
        preflight_timeout,
        repair_retries,
        bootstrap,
        forward_local_port: device.http_port,
        ..Default::default()
    };
    let forwarder = build_forwarder(&device.host, serial);
    let auth = device.auth_token.as_ref().map(|_| &client);
    let forwarder_ref = forwarder.as_ref().map(|f| f as &dyn ForwardManager);
    let status =
        ensure_connection(&device.host, device.http_port, auth, forwarder_ref, &opts).await;
    Ok((client, status))
}

pub async fn run(
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
    timeout_seconds: u64,
    retries: u32,
) -> Result<()> {
    let store = DeviceStore::default();
    let config = store
        .load()
        .with_context(|| format!("load {}", store.path().display()))?;
    let device = DeviceStore::resolve_device(&config, device_id)?;

    let (_client, status) = connect_device(
        device,
        serial.map(str::to_string),
        !no_bootstrap,
        Duration::from_secs(timeout_seconds.max(1)),
        retries,
    )
    .await?;

    print_status(device, &status);

    if status.is_healthy() {
        Ok(())
    } else {
        let hint = match status.issue {
            ConnectionIssue::Unauthorized => "re-pair first: `modspec pair scan`",
            ConnectionIssue::StaleForward => {
                "check the USB connection and that the ModSpec Agent app is open"
            }
            _ => "make sure the ModSpec Agent app is running on the device",
        };
        anyhow::bail!(
            "connection failed (issue={}): {} — {hint}",
            status.issue.as_str(),
            status.detail
        )
    }
}

fn print_status(device: &StoredDevice, status: &ConnectionStatus) {
    println!(
        "device={} host={}:{} issue={} preflight={} latency_ms={} forward_rebuilt={} bootstrapped={}",
        device.id,
        device.host,
        device.http_port,
        status.issue.as_str(),
        if status.preflight_ok { "ok" } else { "failed" },
        status
            .latency_ms
            .map(|ms| ms.to_string())
            .unwrap_or_else(|| "-".into()),
        status.forward_rebuilt,
        status.bootstrapped,
    );
    println!("detail: {}", status.detail);
}
