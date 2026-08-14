//! Structured device inventory returned by the Android Agent.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InspectDeviceParams {
    #[serde(default)]
    pub include_apps: bool,
    #[serde(default = "default_app_limit")]
    pub app_limit: u32,
}

impl Default for InspectDeviceParams {
    fn default() -> Self {
        Self {
            include_apps: false,
            app_limit: default_app_limit(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DeviceInspection {
    pub hardware: HardwareInfo,
    pub software: SoftwareInfo,
    pub display: DisplayInfo,
    pub memory: MemoryInfo,
    pub storage: StorageInfo,
    pub runtime: RuntimeInfo,
    pub apps: AppInventory,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct HardwareInfo {
    pub manufacturer: String,
    pub brand: String,
    pub model: String,
    pub device: String,
    pub product: String,
    pub board: String,
    pub hardware: String,
    pub soc_manufacturer: Option<String>,
    pub soc_model: Option<String>,
    pub cpu_abis: Vec<String>,
    pub cpu_cores: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SoftwareInfo {
    pub android_release: String,
    pub sdk_int: u32,
    pub security_patch: String,
    pub build_id: String,
    pub incremental: String,
    pub display_build: String,
    pub fingerprint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DisplayInfo {
    pub width_pixels: u32,
    pub height_pixels: u32,
    pub density_dpi: u32,
    pub refresh_rate_hz: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MemoryInfo {
    pub total_bytes: u64,
    pub available_bytes: u64,
    pub low_memory: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StorageInfo {
    pub internal_total_bytes: u64,
    pub internal_available_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RuntimeInfo {
    pub root_available: bool,
    pub xposed_service_bound: bool,
    pub lsposed_framework: Option<String>,
    pub agent_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AppInventory {
    pub total: u32,
    pub system: u32,
    pub user: u32,
    pub returned: u32,
    pub truncated: bool,
    #[serde(default)]
    pub entries: Vec<InstalledApp>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InstalledApp {
    pub package: String,
    pub version_name: Option<String>,
    pub version_code: u64,
    pub system: bool,
    /// Package is enabled for the current user (defaults to true for older
    /// agents that did not yet report the field).
    #[serde(default = "default_enabled")]
    pub enabled: bool,
}

fn default_enabled() -> bool {
    true
}

fn default_app_limit() -> u32 {
    200
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inspect_params_defaults() {
        let params = InspectDeviceParams::default();
        assert!(!params.include_apps);
        assert_eq!(params.app_limit, 200);
    }

    #[test]
    fn inspection_roundtrips_through_json() {
        let inspection = DeviceInspection {
            hardware: HardwareInfo {
                manufacturer: "Xiaomi".into(),
                brand: "Xiaomi".into(),
                model: "M2102J2SC".into(),
                device: "haydn".into(),
                product: "haydn".into(),
                board: "kona".into(),
                hardware: "kona".into(),
                soc_manufacturer: Some("Qualcomm".into()),
                soc_model: Some("SM8350".into()),
                cpu_abis: vec!["arm64-v8a".into()],
                cpu_cores: 8,
            },
            software: SoftwareInfo {
                android_release: "13".into(),
                sdk_int: 33,
                security_patch: "2023-01-01".into(),
                build_id: "TKQ1.220829.002".into(),
                incremental: "V14.0.4.0.TKKEUXM".into(),
                display_build: "TKQ1.220829.002".into(),
                fingerprint: "Xiaomi/haydn/haydn:13/TKQ1.220829.002/V14.0.4.0:user/release-keys"
                    .into(),
            },
            display: DisplayInfo {
                width_pixels: 1080,
                height_pixels: 2400,
                density_dpi: 440,
                refresh_rate_hz: 120.0,
            },
            memory: MemoryInfo {
                total_bytes: 12_884_901_888,
                available_bytes: 6_442_450_944,
                low_memory: false,
            },
            storage: StorageInfo {
                internal_total_bytes: 251_900_149_760,
                internal_available_bytes: 171_798_691_840,
            },
            runtime: RuntimeInfo {
                root_available: true,
                xposed_service_bound: true,
                lsposed_framework: Some("LSPosed-mod 1.10.1 (7024)".into()),
                agent_version: "0.1.0".into(),
            },
            apps: AppInventory {
                total: 320,
                system: 210,
                user: 110,
                returned: 2,
                truncated: true,
                entries: vec![
                    InstalledApp {
                        package: "com.android.settings".into(),
                        version_name: Some("13".into()),
                        version_code: 33,
                        system: true,
                        enabled: true,
                    },
                    InstalledApp {
                        package: "com.example.app".into(),
                        version_name: None,
                        version_code: 42,
                        system: false,
                        enabled: false,
                    },
                ],
            },
        };

        let json = serde_json::to_value(&inspection).unwrap();
        // Round trip must preserve every field, including f32 refresh rate.
        let decoded: DeviceInspection = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, inspection);
        assert_eq!(decoded.hardware.cpu_abis, vec!["arm64-v8a"]);
        assert!(!decoded.apps.entries[1].enabled);
    }

    #[test]
    fn installed_app_missing_enabled_defaults_to_true() {
        let value = serde_json::json!({
            "package": "com.example.app",
            "version_name": "1.0",
            "version_code": 1,
            "system": false
        });
        let app: InstalledApp = serde_json::from_value(value).unwrap();
        assert!(app.enabled);
    }
}
