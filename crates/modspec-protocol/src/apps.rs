//! Typed app inventory: list with scope filters and per-package detail.

use serde::{Deserialize, Serialize};

use crate::InstalledApp;

pub const MAX_APP_LIMIT: u32 = 2000;

/// Package universe filter for `app list`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AppScope {
    #[default]
    All,
    System,
    User,
}

impl AppScope {
    pub fn as_str(self) -> &'static str {
        match self {
            AppScope::All => "all",
            AppScope::System => "system",
            AppScope::User => "user",
        }
    }
}

/// Strict Android package-name validation shared by PC clients and tests.
/// Mirrors the Agent's `requireSafePackage` for the cases we control.
pub fn is_valid_package_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && name.split('.').all(|seg| {
            !seg.is_empty() && seg.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
        && name.contains('.')
}

/// Validate a `package[/component]` target. When a component is present its
/// package prefix must equal the target package (a caller may never steer the
/// Agent towards another app's exported component implicitly).
pub fn is_valid_component(package: &str, component: &str) -> bool {
    let (component_pkg, _class) = match component.split_once('/') {
        Some(parts) => parts,
        None => return false,
    };
    component_pkg == package && is_valid_class(component_pkg, _class)
}

fn is_valid_class(package: &str, class: &str) -> bool {
    let shorthand = class.strip_prefix('.');
    let class = shorthand.unwrap_or(class);
    if class.is_empty() {
        return false;
    }
    // Shorthand `pkg/.Class` expands to `pkg.Class`; fully qualified
    // components must already carry the `pkg.` prefix.
    let qualified = match shorthand {
        Some(_) => format!("{package}.{class}"),
        None => {
            let Some(rest) = class.strip_prefix(&format!("{package}.")) else {
                return false;
            };
            format!("{package}.{rest}")
        }
    };
    qualified
        .split('.')
        .all(|seg| !seg.is_empty() && seg.chars().all(|c| c.is_ascii_alphanumeric() || c == '_'))
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AppListParams {
    #[serde(default)]
    pub scope: AppScope,
    #[serde(default = "default_app_limit")]
    pub limit: u32,
    /// Case-insensitive substring filter on the package name.
    #[serde(default)]
    pub filter: Option<String>,
}

impl Default for AppListParams {
    fn default() -> Self {
        Self {
            scope: AppScope::All,
            limit: default_app_limit(),
            filter: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AppListResponse {
    pub total: u32,
    pub system: u32,
    pub user: u32,
    pub returned: u32,
    pub truncated: bool,
    pub scope: AppScope,
    #[serde(default)]
    pub entries: Vec<InstalledApp>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AppInfoParams {
    pub package: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AppInfoResponse {
    pub package: String,
    pub version_name: Option<String>,
    pub version_code: u64,
    pub system: bool,
    pub enabled: bool,
    /// Installer package (from `pm list packages -i`; requires root on
    /// recent Android releases). `None` when unknown or unavailable.
    pub installer: Option<String>,
    /// Whether a launcher activity exists (the app can be started by `trigger`).
    pub launchable: bool,
    /// Resolved primary launcher component, e.g. `com.foo/.MainActivity`.
    pub primary_activity: Option<String>,
    pub uid: Option<u32>,
    pub first_install_ms: Option<i64>,
    pub last_update_ms: Option<i64>,
    pub components: ComponentCounts,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct ComponentCounts {
    pub activities: u32,
    pub services: u32,
    pub receivers: u32,
    pub providers: u32,
}

fn default_app_limit() -> u32 {
    200
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_list_params_defaults() {
        let params = AppListParams::default();
        assert_eq!(params.scope, AppScope::All);
        assert_eq!(params.limit, 200);
        assert_eq!(params.filter, None);
    }

    #[test]
    fn app_scope_serializes_lowercase() {
        let value = serde_json::to_value(AppScope::System).unwrap();
        assert_eq!(value, serde_json::json!("system"));
        let scope: AppScope = serde_json::from_value(serde_json::json!("user")).unwrap();
        assert_eq!(scope, AppScope::User);
    }

    #[test]
    fn app_list_response_roundtrips() {
        let response = AppListResponse {
            total: 5,
            system: 3,
            user: 2,
            returned: 5,
            truncated: false,
            scope: AppScope::All,
            entries: vec![InstalledApp {
                package: "com.example.app".into(),
                version_name: Some("1.2.3".into()),
                version_code: 12,
                system: false,
                enabled: true,
            }],
        };
        let json = serde_json::to_value(&response).unwrap();
        let decoded: AppListResponse = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, response);
    }

    #[test]
    fn package_name_validation() {
        assert!(is_valid_package_name("com.example.app"));
        assert!(is_valid_package_name("com.android.settings"));
        assert!(is_valid_package_name("io.github.lsposed.mod"));
        assert!(is_valid_package_name("com.example"));
        assert!(!is_valid_package_name(""));
        assert!(!is_valid_package_name("no-dash.here"));
        assert!(!is_valid_package_name(".com.example"));
        assert!(!is_valid_package_name("com..example.app"));
        assert!(!is_valid_package_name("system"));
        assert!(!is_valid_package_name("android"));
        assert!(!is_valid_package_name("com.example app"));
    }

    #[test]
    fn component_validation() {
        assert!(is_valid_component("com.foo", "com.foo/.Main"));
        assert!(is_valid_component("com.foo", "com.foo/com.foo.Main"));
        assert!(is_valid_component("com.foo", "com.foo/.MainExtra"));
        // Component package must match the target package.
        assert!(!is_valid_component("com.foo", "com.evil/.Main"));
        assert!(!is_valid_component("com.foo", "com.foo"));
        assert!(!is_valid_component("com.foo", "/Main"));
        assert!(!is_valid_component("com.foo", "com.foo/"));
        // Fully-qualified components must be prefixed with the package.
        assert!(!is_valid_component("com.foo", "com.foo/Other.Main"));
        assert!(!is_valid_component("com.foo", "com.foo/.evil main"));
    }
}
