mod adb;
mod app;
mod community;
mod connect;
mod device;
mod mcp;
mod pair;
mod process;
mod profile;
mod rule;
mod script;
mod validate;

use anyhow::{Context, Result};
use modspec_core::{DeviceStore, StoredDevice};
use modspec_protocol::RpcClient;

use crate::Commands;

pub(crate) use connect::connect_device;

pub async fn dispatch(command: Commands) -> Result<()> {
    match command {
        Commands::Validate { path } => validate::validate_file(&path),
        Commands::Show { path } => validate::show_file(&path),
        Commands::Community { action } => community::run(action),
        Commands::Pair { action } => pair::run(action).await,
        Commands::Device { action } => device::run(action).await,
        Commands::App { action } => app::run(action).await,
        Commands::Process { action } => process::run(action).await,
        Commands::Adb { action } => adb::run(action),
        Commands::Connect {
            device,
            serial,
            no_bootstrap,
            timeout,
            retries,
        } => {
            connect::run(
                device.as_deref(),
                serial.as_deref(),
                no_bootstrap,
                timeout,
                retries,
            )
            .await
        }
        Commands::Profile { action } => profile::run(action).await,
        Commands::Rule { action } => rule::run(action).await,
        Commands::Script { action } => script::run(action).await,
        Commands::Mcp { action } => mcp::run(action).await,
    }
}

/// Resolve the target paired device and build an (optionally offline) RPC
/// client. Read-only commands pass `offline` through; mutating commands must
/// call [`StoredDevice::require_auth`] on the returned device before sending.
pub(crate) fn resolve_authorized_client(
    device_id: Option<&str>,
    offline: bool,
) -> Result<(StoredDevice, RpcClient)> {
    let store = DeviceStore::default();
    let config = store.load()?;
    let device =
        DeviceStore::resolve_device(&config, device_id).with_context(|| "resolve target device")?;
    if !offline && device.auth_token.is_none() {
        anyhow::bail!(
            "device {} has no bearer token (stale or pre-token record); re-pair first: `modspec pair scan`",
            device.id
        );
    }
    let mut client = RpcClient::from_device(&device.host, device.http_port, device.ws_port)
        .with_auth_token(device.auth_token.clone());
    client.set_offline(offline);
    Ok((device.clone(), client))
}
