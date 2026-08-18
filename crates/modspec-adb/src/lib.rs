//! Typed, validated PC-side ADB capability layer.
//!
//! ModSpec treats ADB as a *transport/bootstrap* concern: discovery, port
//! forwarding, APK install/pull, and UI-tree snapshots. This crate wraps the
//! `adb` binary with typed errors and validated inputs (serial/package/port/
//! path/component) so commands never concatenate raw user input into shell
//! strings. Screen streaming is deliberately NOT implemented here; delegate to
//! `scrcpy` (`scrcpy -s <serial>`) instead.
//!
//! Binary resolution: `MODSPEC_ADB` env var, else `adb` on PATH.

use std::path::{Path, PathBuf};
use std::process::Command;

use thiserror::Error;

#[derive(Debug, Error)]
pub enum AdbError {
    #[error(
        "adb binary not found (tried {0:?} and $MODSPEC_ADB); install Android platform-tools or set MODSPEC_ADB"
    )]
    BinaryNotFound(Vec<String>),
    #[error("invalid argument: {0}")]
    Invalid(String),
    #[error("adb {command} failed (exit {code}): {stderr}")]
    NonZero {
        command: String,
        code: i32,
        stderr: String,
    },
    #[error("failed to spawn adb {command}: {source}")]
    Spawn {
        command: String,
        source: std::io::Error,
    },
    #[error("no online adb device (found: {0:?})")]
    NoOnlineDevice(Vec<String>),
}

pub type Result<T> = std::result::Result<T, AdbError>;

/// One `adb devices` row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdbDevice {
    pub serial: String,
    /// `device`, `unauthorized`, `offline`, …
    pub state: String,
}

impl AdbDevice {
    pub fn is_online(&self) -> bool {
        self.state == "device"
    }
}

/// ADB wrapper bound to a resolved binary path.
#[derive(Debug, Clone)]
pub struct Adb {
    binary: PathBuf,
}

impl Adb {
    /// Resolve the adb binary: `$MODSPEC_ADB` first, then `adb` on PATH.
    pub fn resolve() -> Result<Self> {
        if let Some(path) = std::env::var_os("MODSPEC_ADB") {
            let path = PathBuf::from(path);
            if path.is_file() {
                return Ok(Self { binary: path });
            }
        }
        if let Ok(path) = which_adb() {
            return Ok(Self { binary: path });
        }
        Err(AdbError::BinaryNotFound(vec![
            "adb (on PATH)".into(),
            "$MODSPEC_ADB".into(),
        ]))
    }

    pub fn new(binary: impl Into<PathBuf>) -> Self {
        Self {
            binary: binary.into(),
        }
    }

    /// `adb devices` — device discovery (read-only).
    pub fn discover(&self) -> Result<Vec<AdbDevice>> {
        let output = self.run(&["devices"])?;
        Ok(parse_devices(&output))
    }

    /// Pick the target serial: explicit serial, else the single online device.
    /// Returns `Err(NoOnlineDevice)` when none (or several) are online.
    pub fn require_serial(&self, serial: Option<&str>) -> Result<String> {
        if let Some(serial) = serial {
            if !validate_serial(serial) {
                return Err(AdbError::Invalid(format!("invalid adb serial: {serial:?}")));
            }
            return Ok(serial.to_string());
        }
        let online: Vec<String> = self
            .discover()?
            .into_iter()
            .filter(|d| d.is_online())
            .map(|d| d.serial)
            .collect();
        match online.len() {
            0 => Err(AdbError::NoOnlineDevice(online)),
            1 => Ok(online[0].clone()),
            _ => Err(AdbError::Invalid(format!(
                "multiple devices online ({}); pass --serial",
                online.join(", ")
            ))),
        }
    }

    /// Forward a local host port to a device port. Ports are validated
    /// (1–65535); only `tcp:` forwards are supported (loopback-safe usage).
    pub fn forward(&self, serial: Option<&str>, local: u16, remote: u16) -> Result<()> {
        if local == 0 || remote == 0 {
            return Err(AdbError::Invalid(format!(
                "ports must be in 1..=65535 (got {local}->{remote})"
            )));
        }
        let target = self.require_serial(serial)?;
        let _ = self.run(&[
            "-s",
            &target,
            "forward",
            &format!("tcp:{local}"),
            &format!("tcp:{remote}"),
        ])?;
        Ok(())
    }

    pub fn remove_forward(&self, serial: Option<&str>, local: u16) -> Result<()> {
        if local == 0 {
            return Err(AdbError::Invalid(format!(
                "port must be in 1..=65535 (got {local})"
            )));
        }
        let target = self.require_serial(serial)?;
        let _ = self.run(&[
            "-s",
            &target,
            "forward",
            "--remove",
            &format!("tcp:{local}"),
        ])?;
        Ok(())
    }

    /// Install an APK (replacing an existing install). The path must exist
    /// and end in `.apk`.
    pub fn install(&self, serial: Option<&str>, apk: &Path) -> Result<()> {
        if !validate_apk_path(apk) {
            return Err(AdbError::Invalid(format!(
                "not an installable apk: {}",
                apk.display()
            )));
        }
        let target = self.require_serial(serial)?;
        let apk = apk.to_string_lossy();
        let _ = self.run(&["-s", &target, "install", "-r", &apk])?;
        Ok(())
    }

    /// Pull a remote file to a local destination. `remote` must be an
    /// absolute path without control characters; `local` must have an
    /// existing parent directory.
    pub fn pull(&self, serial: Option<&str>, remote: &Path, local: &Path) -> Result<()> {
        if !validate_remote_path(remote) {
            return Err(AdbError::Invalid(format!(
                "remote path must be absolute and plain: {}",
                remote.display()
            )));
        }
        if !validate_local_target(local) {
            return Err(AdbError::Invalid(format!(
                "local destination has no existing parent: {}",
                local.display()
            )));
        }
        let target = self.require_serial(serial)?;
        let remote = remote.to_string_lossy();
        let local = local.to_string_lossy();
        let _ = self.run(&["-s", &target, "pull", &remote, &local])?;
        Ok(())
    }

    /// Push a local file to a remote path (creates the remote parent via `mkdir -p`).
    pub fn push(&self, serial: Option<&str>, local: &Path, remote: &Path) -> Result<()> {
        if !validate_apk_path(local) && !local.is_file() {
            return Err(AdbError::Invalid(format!(
                "local file not readable: {}",
                local.display()
            )));
        }
        if !validate_remote_path(remote) {
            return Err(AdbError::Invalid(format!(
                "remote path must be absolute and plain: {}",
                remote.display()
            )));
        }
        let target = self.require_serial(serial)?;
        let local = local.to_string_lossy();
        let remote = remote.to_string_lossy();
        if let Some(parent) = Path::new(remote.as_ref()).parent() {
            let _ = self.run(&["-s", &target, "shell", "mkdir", "-p", &parent.to_string_lossy()]);
        }
        let _ = self.run(&["-s", &target, "push", &local, &remote])?;
        Ok(())
    }

    /// Device primary ABI (`ro.product.cpu.abi`), used to select on-demand
    /// native components such as the Frida gadget.
    pub fn cpu_abi(&self, serial: Option<&str>) -> Result<String> {
        let target = self.require_serial(serial)?;
        let output = self.run(&["-s", &target, "shell", "getprop", "ro.product.cpu.abi"])?;
        let abi = output.trim();
        if abi.is_empty() {
            return Err(AdbError::Invalid("empty ro.product.cpu.abi".into()));
        }
        Ok(abi.to_string())
    }

    /// Capture the current UI hierarchy as XML (snapshot; read-only).
    ///
    /// This is a one-shot `uiautomator dump` + `cat`. It is NOT screen
    /// streaming — delegate to `scrcpy -s <serial>` for that. The temporary
    /// dump file is removed after capture.
    pub fn ui_tree(&self, serial: Option<&str>) -> Result<String> {
        let target = self.require_serial(serial)?;
        let _ = self.run(&[
            "-s",
            &target,
            "shell",
            "uiautomator",
            "dump",
            "/sdcard/modspec_window_dump.xml",
        ])?;
        let xml = self.run(&[
            "-s",
            &target,
            "shell",
            "cat",
            "/sdcard/modspec_window_dump.xml",
        ])?;
        let _ = self.run(&[
            "-s",
            &target,
            "shell",
            "rm",
            "-f",
            "/sdcard/modspec_window_dump.xml",
        ]);
        Ok(xml)
    }

    /// Start an activity via `am start -n <component>` (the Agent bootstrap
    /// path). The component must pass [`validate_component`]; input is never
    /// concatenated into a shell command unvalidated.
    pub fn start_activity(&self, serial: Option<&str>, component: &str) -> Result<()> {
        if !validate_component(component) {
            return Err(AdbError::Invalid(format!(
                "invalid component: {component:?}"
            )));
        }
        let target = self.require_serial(serial)?;
        let _ = self.run(&["-s", &target, "shell", "am", "start", "-n", component])?;
        Ok(())
    }

    /// Relaunch the ModSpec Agent's exported MainActivity, which starts
    /// AgentService and its supervised servers (used by `modspec connect`
    /// when the Agent is unreachable).
    pub fn bootstrap_agent(&self, serial: Option<&str>) -> Result<()> {
        self.start_activity(serial, "com.modspec.agent/.MainActivity")
    }

    fn run(&self, args: &[&str]) -> Result<String> {
        let command = args.join(" ");
        let output = Command::new(&self.binary)
            .args(args)
            .output()
            .map_err(|source| AdbError::Spawn {
                command: command.clone(),
                source,
            })?;
        if !output.status.success() {
            return Err(AdbError::NonZero {
                command,
                code: output.status.code().unwrap_or(-1),
                stderr: String::from_utf8_lossy(&output.stderr).trim().to_string(),
            });
        }
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
    }
}

// ---- pure validation & parsing (unit-tested) ----

pub fn validate_serial(serial: &str) -> bool {
    !serial.is_empty()
        && serial.len() <= 64
        && serial
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | ':'))
}

/// Strict Android package-name validation (mirrors the Agent's guard).
pub fn validate_package(package: &str) -> bool {
    !package.is_empty()
        && package.len() <= 255
        && package.split('.').all(|seg| {
            !seg.is_empty() && seg.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
        && package.contains('.')
}

/// Validate a `package/.Class` or `package/package.Class` component string.
/// Only plain identifiers (and `.`/`_`/`$`) are accepted, so a component can
/// never smuggle shell metacharacters into `am start`.
pub fn validate_component(component: &str) -> bool {
    if component.is_empty() || component.len() > 512 {
        return false;
    }
    let Some((package, class)) = component.split_once('/') else {
        return false;
    };
    if !validate_package(package) {
        return false;
    }
    !class.is_empty()
        && class
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '$'))
}

pub fn validate_port(port: u16) -> bool {
    port != 0
}

pub fn validate_apk_path(path: &Path) -> bool {
    path.extension().and_then(|e| e.to_str()) == Some("apk") && path.is_file()
}

pub fn validate_remote_path(path: &Path) -> bool {
    path.is_absolute()
        && !path.as_os_str().is_empty()
        && !path
            .to_string_lossy()
            .chars()
            .any(|c| c.is_control() || c == '\n' || c == '\r' || c == '"')
}

pub fn validate_local_target(path: &Path) -> bool {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    parent.is_dir()
}

/// Parse `adb devices` output. Empty/unparseable lines are skipped.
pub fn parse_devices(output: &str) -> Vec<AdbDevice> {
    output
        .lines()
        .skip(1) // "List of devices attached"
        .map(|line| line.trim())
        .filter(|line| !line.is_empty() && !line.starts_with('*'))
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?;
            let state = parts.next().unwrap_or("unknown").to_string();
            Some(AdbDevice {
                serial: serial.to_string(),
                state,
            })
        })
        .collect()
}

fn which_adb() -> std::io::Result<PathBuf> {
    let path_var = std::env::var_os("PATH").unwrap_or_default();
    for dir in std::env::split_paths(&path_var) {
        let candidate = dir.join("adb");
        if candidate.is_file() {
            return Ok(candidate);
        }
        #[cfg(windows)]
        if candidate.with_extension("exe").is_file() {
            return Ok(candidate.with_extension("exe"));
        }
    }
    Err(std::io::Error::new(
        std::io::ErrorKind::NotFound,
        "adb not found on PATH",
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_devices_output() {
        let output = "List of devices attached\nR58M2ABCD\tdevice\nemulator-5554\toffline\n\n";
        let devices = parse_devices(output);
        assert_eq!(devices.len(), 2);
        assert_eq!(devices[0].serial, "R58M2ABCD");
        assert!(devices[0].is_online());
        assert!(!devices[1].is_online());
    }

    #[test]
    fn parses_empty_and_unauthorized() {
        let devices = parse_devices("List of devices attached\n");
        assert!(devices.is_empty());
        let devices = parse_devices("List of devices attached\nXYZ\tunauthorized\n");
        assert_eq!(devices.len(), 1);
        assert_eq!(devices[0].state, "unauthorized");
        assert!(!devices[0].is_online());
    }

    #[test]
    fn serial_validation() {
        assert!(validate_serial("R58M2ABCD"));
        assert!(validate_serial("emulator-5554"));
        assert!(validate_serial("192.168.1.10:5555"));
        assert!(!validate_serial(""));
        assert!(!validate_serial("bad serial with spaces"));
        assert!(!validate_serial(&"x".repeat(65)));
        assert!(!validate_serial("a;rm -rf /"));
    }

    #[test]
    fn apk_path_validation() {
        let dir = std::env::temp_dir();
        assert!(!validate_apk_path(&dir.join("missing.apk"))); // missing file
        assert!(!validate_apk_path(&dir.join("app.txt"))); // wrong extension
                                                           // A real apk file passes.
        let apk = dir.join(format!("modspec-test-{}.apk", uuid_suffix()));
        std::fs::write(&apk, b"PK\x03\x04").unwrap();
        assert!(validate_apk_path(&apk));
        let _ = std::fs::remove_file(&apk);
    }

    #[test]
    fn remote_path_validation() {
        assert!(validate_remote_path(Path::new("/data/local/tmp/x.txt")));
        assert!(!validate_remote_path(Path::new("relative/x.txt")));
        assert!(!validate_remote_path(Path::new("/data/local/tmp/x\n.txt")));
        assert!(!validate_remote_path(Path::new("/data/\"evil\"")));
    }

    #[test]
    fn local_target_validation() {
        let dir = std::env::temp_dir();
        assert!(validate_local_target(&dir.join("out.txt")));
        assert!(validate_local_target(&dir.join("sub"))); // target itself may be a dir
        assert!(!validate_local_target(Path::new(
            "/nonexistent-dir-xyz/out.txt"
        )));
    }

    #[test]
    fn port_validation() {
        assert!(validate_port(9876));
        assert!(!validate_port(0));
    }

    #[test]
    fn package_validation() {
        assert!(validate_package("com.modspec.agent"));
        assert!(validate_package("com.example_app.target2"));
        assert!(!validate_package(""));
        assert!(!validate_package("no-dash.here"));
        assert!(!validate_package(".com.example"));
        assert!(!validate_package("com..example.app"));
        assert!(!validate_package("com.example; rm -rf /"));
    }

    #[test]
    fn component_validation() {
        assert!(validate_component("com.modspec.agent/.MainActivity"));
        assert!(validate_component(
            "com.modspec.agent/com.modspec.agent.MainActivity"
        ));
        assert!(validate_component("com.example/.a$Inner"));
        assert!(validate_component("com.example.app/MainActivity")); // plain class (no shell chars)
        assert!(!validate_component(""));
        assert!(!validate_component("com.example.app"));
        assert!(!validate_component("com.example.app/.Main Activity")); // space
        assert!(!validate_component("com.example.app/.Main;reboot")); // metacharacter
        assert!(!validate_component("com.example.app/../.Main")); // metacharacter
        assert!(!validate_component("com.example.app/.Main&id"));
        assert!(!validate_component(&format!("{}/.X", "a".repeat(300)))); // oversized package
    }

    #[test]
    fn bootstrap_component_is_valid() {
        assert!(validate_component("com.modspec.agent/.MainActivity"));
    }

    fn uuid_suffix() -> String {
        use std::time::{SystemTime, UNIX_EPOCH};
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
            .to_string()
    }
}
