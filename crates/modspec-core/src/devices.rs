//! Paired device storage in `devices.toml`.

use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::error::{ModspecError, Result};

/// Root document: `~/.config/modspec/devices.toml`
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct DevicesConfig {
    #[serde(default)]
    pub default_device: Option<String>,
    #[serde(default)]
    pub devices: Vec<StoredDevice>,
}

/// A paired device entry persisted on the PC.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StoredDevice {
    pub id: String,
    pub name: String,
    pub host: String,
    #[serde(default = "default_ws_port")]
    pub ws_port: u16,
    #[serde(default = "default_http_port")]
    pub http_port: u16,
    /// Bearer token issued by the Agent during pairing.
    #[serde(default)]
    pub auth_token: Option<String>,
    pub paired_at: DateTime<Utc>,
}

fn default_ws_port() -> u16 {
    8765
}

fn default_http_port() -> u16 {
    8764
}

impl StoredDevice {
    /// True when a pairing bearer token is persisted for this device.
    ///
    /// Legacy/pre-token records deserialize fine (`auth_token == None`) but must
    /// be re-paired before any dangerous RPC; see [`StoredDevice::require_auth`].
    pub fn is_authorized(&self) -> bool {
        self.auth_token.is_some()
    }

    /// Refuse dangerous RPC when no bearer token is available.
    pub fn require_auth(&self) -> Result<()> {
        if self.auth_token.is_none() {
            return Err(ModspecError::Validation(format!(
                "device {} has no bearer token (stale or pre-token record); re-pair first: `modspec pair scan`",
                self.id
            )));
        }
        Ok(())
    }

    pub fn http_rpc_url(&self) -> String {
        format!("http://{}:{}/rpc", self.host, self.http_port)
    }

    pub fn ws_url(&self) -> String {
        format!("ws://{}:{}/rpc", self.host, self.ws_port)
    }

    pub fn from_pairing(
        device_id: impl Into<String>,
        name: impl Into<String>,
        host: impl Into<String>,
    ) -> Self {
        Self {
            id: device_id.into(),
            name: name.into(),
            host: host.into(),
            ws_port: default_ws_port(),
            http_port: default_http_port(),
            auth_token: None,
            paired_at: Utc::now(),
        }
    }
}

/// Configurable path to the devices file.
#[derive(Debug, Clone)]
pub struct DeviceStore {
    path: PathBuf,
}

impl DeviceStore {
    /// Default: `~/.config/modspec/devices.toml` (or `%APPDATA%/modspec` on Windows).
    pub fn default_path() -> PathBuf {
        config_dir().join("devices.toml")
    }

    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn load(&self) -> Result<DevicesConfig> {
        if !self.path.exists() {
            return Ok(DevicesConfig::default());
        }
        let content = std::fs::read_to_string(&self.path)?;
        let config: DevicesConfig = toml::from_str(&content)?;
        Ok(config)
    }

    pub fn save(&self, config: &DevicesConfig) -> Result<()> {
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let content =
            toml::to_string_pretty(config).map_err(|e| ModspecError::Validation(e.to_string()))?;
        std::fs::write(&self.path, content)?;
        Ok(())
    }

    pub fn upsert_device(&self, device: StoredDevice) -> Result<DevicesConfig> {
        let mut config = self.load()?;
        if let Some(existing) = config.devices.iter_mut().find(|d| d.id == device.id) {
            *existing = device;
        } else {
            if config.default_device.is_none() {
                config.default_device = Some(device.id.clone());
            }
            config.devices.push(device);
        }
        self.save(&config)?;
        Ok(config)
    }

    pub fn resolve_device<'a>(
        config: &'a DevicesConfig,
        device_id: Option<&str>,
    ) -> Result<&'a StoredDevice> {
        match device_id {
            Some(id) => config
                .devices
                .iter()
                .find(|d| d.id == id)
                .ok_or_else(|| ModspecError::Validation(format!("unknown device id: {id}"))),
            None => {
                let default_id = config.default_device.as_deref().ok_or_else(|| {
                    ModspecError::Validation("no default device; use --device".into())
                })?;
                config
                    .devices
                    .iter()
                    .find(|d| d.id == default_id)
                    .ok_or_else(|| {
                        ModspecError::Validation(format!("default device not found: {default_id}"))
                    })
            }
        }
    }
}

impl Default for DeviceStore {
    fn default() -> Self {
        Self::new(Self::default_path())
    }
}

fn config_dir() -> PathBuf {
    if let Ok(dir) = std::env::var("MODSPEC_CONFIG_DIR") {
        return PathBuf::from(dir);
    }
    if let Some(proj) = directories::ProjectDirs::from("", "", "modspec") {
        return proj.config_dir().to_path_buf();
    }
    // Fallback when directories crate cannot resolve home.
    PathBuf::from(".config/modspec")
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn sample_device(id: &str) -> StoredDevice {
        StoredDevice {
            id: id.into(),
            name: format!("Phone {id}"),
            host: "192.168.1.42".into(),
            ws_port: 8765,
            http_port: 8764,
            auth_token: Some("test-token".into()),
            paired_at: Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0).unwrap(),
        }
    }

    #[test]
    fn save_and_load_roundtrip() {
        let dir = std::env::temp_dir().join(format!("modspec-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("devices.toml");
        let store = DeviceStore::new(&path);

        let mut config = DevicesConfig {
            default_device: Some("dev-1".into()),
            ..DevicesConfig::default()
        };
        config.devices.push(sample_device("dev-1"));
        store.save(&config).unwrap();

        let loaded = store.load().unwrap();
        assert_eq!(loaded, config);

        let device2 = sample_device("dev-2");
        store.upsert_device(device2.clone()).unwrap();
        let loaded = store.load().unwrap();
        assert_eq!(loaded.devices.len(), 2);
        assert!(loaded.devices.iter().any(|d| d.id == "dev-2"));

        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn upsert_replaces_existing() {
        let dir = std::env::temp_dir().join(format!("modspec-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let store = DeviceStore::new(dir.join("devices.toml"));

        let mut device = sample_device("dev-1");
        store.upsert_device(device.clone()).unwrap();

        device.name = "Renamed".into();
        store.upsert_device(device.clone()).unwrap();

        let loaded = store.load().unwrap();
        assert_eq!(loaded.devices.len(), 1);
        assert_eq!(loaded.devices[0].name, "Renamed");

        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn resolve_device_picks_default() {
        let mut config = DevicesConfig {
            default_device: Some("dev-a".into()),
            ..DevicesConfig::default()
        };
        config.devices.push(sample_device("dev-a"));
        config.devices.push(sample_device("dev-b"));

        let resolved = DeviceStore::resolve_device(&config, None).unwrap();
        assert_eq!(resolved.id, "dev-a");

        let resolved = DeviceStore::resolve_device(&config, Some("dev-b")).unwrap();
        assert_eq!(resolved.id, "dev-b");
    }

    #[test]
    fn resolve_unknown_device_errors() {
        let config = DevicesConfig::default();
        let err = DeviceStore::resolve_device(&config, Some("missing")).unwrap_err();
        assert!(err.to_string().contains("unknown device id"));
    }

    #[test]
    fn legacy_record_without_token_deserializes_and_requires_repair() {
        let toml_str = r#"
[[devices]]
id = "legacy-1"
name = "Old Phone"
host = "127.0.0.1"
paired_at = "2026-01-01T00:00:00Z"
"#;
        let config: DevicesConfig = toml::from_str(toml_str).unwrap();
        let device = &config.devices[0];
        assert!(!device.is_authorized());
        let err = device.require_auth().unwrap_err();
        assert!(err.to_string().contains("re-pair"));
        // Default ports still resolve for old records.
        assert_eq!(device.http_port, 8764);
        assert_eq!(device.ws_port, 8765);
    }

    #[test]
    fn bearer_token_roundtrips_through_toml() {
        let dir = std::env::temp_dir().join(format!("modspec-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let store = DeviceStore::new(dir.join("devices.toml"));

        let mut device = sample_device("dev-token");
        device.auth_token = Some("super-secret-token".into());
        store.upsert_device(device.clone()).unwrap();

        let loaded = store.load().unwrap();
        assert_eq!(
            loaded.devices[0].auth_token.as_deref(),
            Some("super-secret-token")
        );
        assert!(loaded.devices[0].is_authorized());
        loaded.devices[0].require_auth().unwrap();

        let _ = std::fs::remove_dir_all(dir);
    }
}
