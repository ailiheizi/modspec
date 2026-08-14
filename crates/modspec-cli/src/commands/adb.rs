//! Typed ADB transport helpers: `modspec adb devices|forward|install|pull|ui-tree`.
//!
//! These wrap the real `adb` binary (see `modspec-adb` crate) with validated
//! inputs and typed errors. Screen streaming is NOT implemented — delegate to
//! `scrcpy -s <serial>` (documented in docs/).

use std::path::PathBuf;

use anyhow::{bail, Result};
use modspec_adb::Adb;

use crate::AdbAction;

pub fn run(action: AdbAction) -> Result<()> {
    let adb = Adb::resolve()?;
    match action {
        AdbAction::Devices => adb_devices(&adb),
        AdbAction::Forward {
            local,
            remote,
            serial,
        } => adb_forward(&adb, serial.as_deref(), local, remote),
        AdbAction::ForwardRemove { local, serial } => {
            adb_forward_remove(&adb, serial.as_deref(), local)
        }
        AdbAction::Install { apk, serial } => adb_install(&adb, serial.as_deref(), &apk),
        AdbAction::Pull {
            remote,
            local,
            serial,
        } => adb_pull(&adb, serial.as_deref(), &remote, &local),
        AdbAction::UiTree { serial } => adb_ui_tree(&adb, serial.as_deref()),
    }
}

fn adb_devices(adb: &Adb) -> Result<()> {
    let devices = adb.discover()?;
    if devices.is_empty() {
        println!("no devices (run `adb devices`; check USB debugging)");
        return Ok(());
    }
    for device in devices {
        let mark = if device.is_online() { "•" } else { " " };
        println!("{mark} {}\t{}", device.serial, device.state);
    }
    Ok(())
}

fn adb_forward(adb: &Adb, serial: Option<&str>, local: u16, remote: u16) -> Result<()> {
    let target = adb.require_serial(serial)?;
    adb.forward(Some(&target), local, remote)?;
    println!("forwarded tcp:{local} -> tcp:{remote} on {target}");
    Ok(())
}

fn adb_forward_remove(adb: &Adb, serial: Option<&str>, local: u16) -> Result<()> {
    let target = adb.require_serial(serial)?;
    adb.remove_forward(Some(&target), local)?;
    println!("removed tcp:{local} forward on {target}");
    Ok(())
}

fn adb_install(adb: &Adb, serial: Option<&str>, apk: &str) -> Result<()> {
    let path = PathBuf::from(apk);
    if !modspec_adb::validate_apk_path(&path) {
        bail!("not an installable apk: {apk}");
    }
    let target = adb.require_serial(serial)?;
    adb.install(Some(&target), &path)?;
    println!("installed {} on {}", path.display(), target);
    Ok(())
}

fn adb_pull(adb: &Adb, serial: Option<&str>, remote: &str, local: &str) -> Result<()> {
    let remote_path = PathBuf::from(remote);
    let local_path = PathBuf::from(local);
    if !modspec_adb::validate_remote_path(&remote_path) {
        bail!("remote path must be absolute and plain: {remote}");
    }
    if !modspec_adb::validate_local_target(&local_path) {
        bail!("local destination has no existing parent directory: {local}");
    }
    let target = adb.require_serial(serial)?;
    adb.pull(Some(&target), &remote_path, &local_path)?;
    println!(
        "pulled {} -> {} from {target}",
        remote_path.display(),
        local_path.display()
    );
    Ok(())
}

fn adb_ui_tree(adb: &Adb, serial: Option<&str>) -> Result<()> {
    let target = adb.require_serial(serial)?;
    let xml = adb.ui_tree(Some(&target))?;
    println!("{xml}");
    println!("# ui-tree snapshot from {target}; use `scrcpy -s {target}` for live screen");
    Ok(())
}
