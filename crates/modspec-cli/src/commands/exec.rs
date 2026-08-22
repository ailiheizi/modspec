//! `modspec exec-su` — run an arbitrary shell command on the device via `su`.
//!
//! This is a raw, explicit escape hatch outside the hook/script context: the
//! command is passed verbatim to the agent, which runs it through `su -c`.
//! Useful for one-off device control (e.g. `cmd wifi force-softap-band
//! enabled 5`). Mutating by nature — never auto-retried, and the agent only
//! accepts it over the paired (bearer-token) channel.

use anyhow::{bail, Context, Result};
use modspec_protocol::ExecSuParams;

use crate::commands::resolve_authorized_client;

pub async fn run(command: &[String], device_id: Option<&str>, timeout: u64) -> Result<()> {
    exec_su(command, device_id, timeout).await
}

async fn exec_su(command: &[String], device_id: Option<&str>, timeout: u64) -> Result<()> {
    if command.is_empty() {
        bail!("missing command (e.g. `modspec exec-su -- cmd wifi force-softap-band enabled 5`)");
    }
    let (device, mut client) = resolve_authorized_client(device_id, false)?;
    device.require_auth()?;
    client.connect().await?;
    let joined = command.join(" ");
    let result = if timeout > 0 {
        client
            .exec_su_timeout(
                &ExecSuParams {
                    command: joined.clone(),
                },
                std::time::Duration::from_secs(timeout),
            )
            .await
    } else {
        client
            .exec_su(&ExecSuParams {
                command: joined.clone(),
            })
            .await
    }
    .with_context(|| format!("exec_su `{joined}` on {}", device.id))?;

    if result.success {
        println!("{}", result.output.as_deref().unwrap_or(""));
        Ok(())
    } else {
        bail!(
            "exec_su failed: {}",
            result.error.as_deref().unwrap_or("su command failed")
        );
    }
}
