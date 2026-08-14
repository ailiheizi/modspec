//! Explicit, validated app launch (`trigger`); restart reuses `restart_targets`.

use serde::{Deserialize, Serialize};

use crate::apps::is_valid_component;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TriggerAppParams {
    pub package: String,
    /// Explicit component `package/.Class` or `package/full.Class`.
    /// When omitted, the launcher activity is used.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub component: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TriggerAppResponse {
    pub package: String,
    /// Whether the start command was issued successfully.
    pub launched: bool,
    /// `"launcher"`, `"component"`, or `"none"`.
    pub method: String,
    /// True when the package has no launcher activity and no explicit
    /// component was supplied — the PC should report this instead of guessing.
    pub needs_trigger: bool,
    pub message: String,
}

impl TriggerAppResponse {
    pub fn launched(package: &str, method: &str, message: &str) -> Self {
        Self {
            package: package.into(),
            launched: true,
            method: method.into(),
            needs_trigger: false,
            message: message.into(),
        }
    }

    pub fn needs_trigger(package: &str, message: &str) -> Self {
        Self {
            package: package.into(),
            launched: false,
            method: "none".into(),
            needs_trigger: true,
            message: message.into(),
        }
    }
}

/// PC-side guard: the component (when supplied) must be well-formed and must
/// belong to the target package. The Agent re-validates on-device.
pub fn validate_trigger_target(package: &str, component: Option<&str>) -> Result<(), String> {
    if !crate::apps::is_valid_package_name(package) {
        return Err(format!("invalid package name: {package}"));
    }
    if matches!(package, "system" | "android" | "system_server") {
        return Err(format!("refusing to trigger system target: {package}"));
    }
    if let Some(component) = component {
        if !is_valid_component(package, component) {
            return Err(format!(
                "invalid component {component:?} for package {package:?} (must be '{package}/.Class')"
            ));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trigger_params_roundtrip() {
        let params = TriggerAppParams {
            package: "com.example.app".into(),
            component: Some("com.example.app/.Main".into()),
        };
        let json = serde_json::to_value(&params).unwrap();
        assert_eq!(json["package"], "com.example.app");
        assert_eq!(json["component"], "com.example.app/.Main");
        let decoded: TriggerAppParams = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, params);
    }

    #[test]
    fn omitted_component_is_not_serialized_as_null() {
        let params = TriggerAppParams {
            package: "com.example.app".into(),
            component: None,
        };
        let json = serde_json::to_value(&params).unwrap();
        assert!(!json.as_object().unwrap().contains_key("component"));
    }

    #[test]
    fn trigger_response_shapes() {
        let ok = TriggerAppResponse::launched("com.example.app", "launcher", "started");
        assert!(ok.launched);
        assert!(!ok.needs_trigger);
        assert_eq!(ok.method, "launcher");

        let stopped = TriggerAppResponse::needs_trigger("com.example.app", "no launcher");
        assert!(!stopped.launched);
        assert!(stopped.needs_trigger);
        assert_eq!(stopped.method, "none");
    }

    #[test]
    fn trigger_target_validation() {
        assert!(validate_trigger_target("com.example.app", None).is_ok());
        assert!(validate_trigger_target("com.example.app", Some("com.example.app/.Main")).is_ok());
        assert!(validate_trigger_target(
            "com.example.app",
            Some("com.example.app/com.example.app.Main")
        )
        .is_ok());
        assert!(validate_trigger_target("com.example.app", Some("com.evil/.Main")).is_err());
        assert!(validate_trigger_target("com.example.app", Some("com.example.app/Main")).is_err());
        assert!(validate_trigger_target("system", None).is_err());
        assert!(validate_trigger_target("android", None).is_err());
        assert!(validate_trigger_target("system_server", None).is_err());
        assert!(validate_trigger_target("not-a-package", None).is_err());
    }
}
