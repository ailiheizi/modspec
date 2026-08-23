use std::collections::HashMap;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::Result;

pub const PROFILE_VERSION: &str = "1";

/// Root document: `*.mspec.toml`
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Profile {
    pub mspec_version: String,
    pub meta: Meta,
    #[serde(default)]
    pub device: Option<DeviceConstraints>,
    #[serde(default)]
    pub mods: Vec<ModEntry>,
    #[serde(default)]
    pub reapply: Option<ReapplyConfig>,
    #[serde(default)]
    pub verify: Option<VerifyConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Meta {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub requires: Option<Requirements>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Requirements {
    #[serde(default)]
    pub oem: Option<Vec<String>>,
    #[serde(default)]
    pub rom: Option<Vec<String>>,
    #[serde(default)]
    pub min_android: Option<u32>,
    #[serde(default)]
    pub lsposed: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DeviceConstraints {
    #[serde(default)]
    pub oem: Option<String>,
    #[serde(default)]
    pub rom: Option<String>,
    #[serde(default)]
    pub min_android: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ReapplyConfig {
    #[serde(default)]
    pub on_boot: bool,
    #[serde(default)]
    pub on_lsposed_reload: bool,
    #[serde(default)]
    pub on_rule_change: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VerifyConfig {
    #[serde(default)]
    pub checks: Vec<VerifyCheck>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VerifyCheck {
    pub mod_id: String,
    pub source: VerifySource,
    #[serde(default)]
    pub pattern: Option<String>,
    #[serde(default)]
    pub expect: Option<String>,
    #[serde(default)]
    pub module: Option<String>,
    #[serde(default)]
    pub expect_apps: Option<Vec<String>>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum VerifySource {
    LsposedLog,
    Scope,
    ModuleEnabled,
}

/// Polymorphic modification entry — `type` selects variant.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ModEntry {
    /// Enable/disable an LSPosed module package.
    LsposedModule {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        package: String,
        state: LsposedModuleState,
    },
    /// Set scope for a module (`lsposed-cli scope`).
    Scope {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        module: String,
        #[serde(default)]
        mode: ScopeMode,
        apps: Vec<String>,
    },
    /// Reference an existing community Xposed module (orchestration only).
    ModuleRef {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        package: String,
        #[serde(default)]
        scope: Vec<String>,
        #[serde(default)]
        note: Option<String>,
    },
    /// Write module SharedPreferences (companion app side).
    ModulePrefs {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        module: String,
        prefs: HashMap<String, toml::Value>,
    },
    /// Reference a rule from the rule library.
    RuleRef {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        rule: String,
        #[serde(default)]
        scope: Vec<String>,
    },
    /// Inline hook definition (profile-private).
    Hook {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        #[serde(default)]
        scope: Vec<String>,
        phase: crate::rule::HookPhase,
        target: crate::rule::HookTarget,
        action: crate::rule::HookAction,
        #[serde(default)]
        options: Option<HookOptions>,
    },
    /// Dynamic scope request via libxposed service (API 101+).
    DynamicScope {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        packages: Vec<String>,
    },
    /// Remote prefs shared between module app and hooked process.
    RemotePrefs {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        key: String,
        value: toml::Value,
    },
    /// Remote blob file (libxposed service).
    RemoteBlob {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        path: String,
        source: String,
    },
    /// Restore LSPosed backup (`.lsp.gz`).
    LsposedRestore {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        file: String,
    },
    /// Force-stop / reload target apps so hooks take effect.
    Reload {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        packages: Vec<String>,
        #[serde(default)]
        mode: ReloadMode,
        #[serde(default)]
        depends_on: Vec<String>,
    },
    /// Ordered post-apply shell-like steps (LSPosed workflow: clear joyose, etc.).
    PostAction {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        commands: Vec<String>,
        #[serde(default)]
        depends_on: Vec<String>,
    },
    /// Declarative shell toggle rendered as an app switch; ON/OFF run su commands.
    ShellToggle {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,
        title: String,
        on_command: String,
        off_command: String,
        #[serde(default)]
        status_command: Option<String>,
        #[serde(default)]
        status_pattern: Option<String>,
    },
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum LsposedModuleState {
    Enabled,
    #[default]
    Disabled,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum ScopeMode {
    #[default]
    Set,
    Append,
    Remove,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum ReloadMode {
    #[default]
    ForceStop,
    Kill,
    SoftRestart,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HookOptions {
    #[serde(default)]
    pub priority: Option<i32>,
    #[serde(default)]
    pub exception_mode: Option<ExceptionMode>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ExceptionMode {
    Protective,
    Passthrough,
}

fn default_true() -> bool {
    true
}

impl Profile {
    pub fn from_file(path: impl AsRef<Path>) -> Result<Self> {
        let content = std::fs::read_to_string(path)?;
        content.parse()
    }

    pub fn mod_ids(&self) -> Vec<&str> {
        self.mods.iter().map(|m| m.id()).collect()
    }
}

impl std::str::FromStr for Profile {
    type Err = crate::error::ModspecError;

    fn from_str(content: &str) -> Result<Self> {
        Ok(toml::from_str(content)?)
    }
}

impl ModEntry {
    pub fn id(&self) -> &str {
        match self {
            Self::LsposedModule { id, .. }
            | Self::Scope { id, .. }
            | Self::ModuleRef { id, .. }
            | Self::ModulePrefs { id, .. }
            | Self::RuleRef { id, .. }
            | Self::Hook { id, .. }
            | Self::DynamicScope { id, .. }
            | Self::RemotePrefs { id, .. }
            | Self::RemoteBlob { id, .. }
            | Self::LsposedRestore { id, .. }
            | Self::Reload { id, .. }
            | Self::PostAction { id, .. }
            | Self::ShellToggle { id, .. } => id,
        }
    }

    pub fn enabled(&self) -> bool {
        match self {
            Self::LsposedModule { enabled, .. }
            | Self::Scope { enabled, .. }
            | Self::ModuleRef { enabled, .. }
            | Self::ModulePrefs { enabled, .. }
            | Self::RuleRef { enabled, .. }
            | Self::Hook { enabled, .. }
            | Self::DynamicScope { enabled, .. }
            | Self::RemotePrefs { enabled, .. }
            | Self::RemoteBlob { enabled, .. }
            | Self::LsposedRestore { enabled, .. }
            | Self::Reload { enabled, .. }
            | Self::PostAction { enabled, .. }
            | Self::ShellToggle { enabled, .. } => *enabled,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_example_profile() {
        let content = include_str!("../../../profiles/xiaomi/hyper-perf-pack.mspec.toml");
        let profile: Profile = content.parse().expect("parse profile");
        assert_eq!(profile.meta.id, "hyper-perf-pack");
        assert!(profile.mods.len() >= 3);
    }

    #[test]
    fn parse_shell_toggle_mod() {
        let content = r#"
            mspec_version = "1"

            [meta]
            id = "toggle-test"
            name = "toggle"

            [[mods]]
            id = "hotspot-5ghz"
            type = "shell_toggle"
            enabled = true
            title = "5GHz 热点"
            on_command = "cmd wifi force-softap-band enabled 5"
            off_command = "cmd wifi force-softap-band disabled"
            status_command = "cmd wifi force-softap-band"
        "#;
        let profile: Profile = content.parse().expect("parse profile");
        let entry = &profile.mods[0];
        assert_eq!(entry.id(), "hotspot-5ghz");
        assert!(entry.enabled());
        let ModEntry::ShellToggle {
            title,
            on_command,
            off_command,
            status_command,
            ..
        } = entry
        else {
            panic!("expected shell_toggle mod");
        };
        assert_eq!(title, "5GHz 热点");
        assert_eq!(on_command, "cmd wifi force-softap-band enabled 5");
        assert_eq!(off_command, "cmd wifi force-softap-band disabled");
        assert_eq!(status_command.as_deref(), Some("cmd wifi force-softap-band"));
    }

    #[test]
    fn parse_shell_toggle_without_status_command() {
        let content = r#"
            mspec_version = "1"

            [meta]
            id = "toggle-test-2"
            name = "toggle"

            [[mods]]
            id = "some-toggle"
            type = "shell_toggle"
            title = "some toggle"
            on_command = "on"
            off_command = "off"
        "#;
        let profile: Profile = content.parse().expect("parse profile");
        let ModEntry::ShellToggle {
            status_command, ..
        } = &profile.mods[0]
        else {
            panic!("expected shell_toggle mod");
        };
        assert!(status_command.is_none());
    }
}
