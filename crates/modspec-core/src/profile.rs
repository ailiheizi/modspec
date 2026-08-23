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
    /// Declared category tree (strict mode). Empty = implicit mode.
    #[serde(default)]
    pub categories: Vec<CategoryDecl>,
    #[serde(default)]
    pub mods: Vec<ModEntry>,
    #[serde(default)]
    pub reapply: Option<ReapplyConfig>,
    #[serde(default)]
    pub verify: Option<VerifyConfig>,
}

/// Declared category (`[[categories]]`): id path + display title.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CategoryDecl {
    /// Path id, max two levels separated by `/` (e.g. `network/hotspot`).
    pub id: String,
    /// Human-readable display title.
    pub title: String,
    #[serde(default)]
    pub icon: Option<String>,
}

/// Metadata shared by every mod variant (flattened into each one).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct ModCommon {
    /// One-line feature description (primary search text).
    #[serde(default)]
    pub description: Option<String>,
    /// Synonym / English aliases to improve keyword recall.
    #[serde(default)]
    pub aliases: Vec<String>,
    /// Category path; missing = uncategorized.
    #[serde(default)]
    pub category: Option<String>,
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

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        package: String,
        state: LsposedModuleState,
    },
    /// Set scope for a module (`lsposed-cli scope`).
    Scope {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        module: String,
        #[serde(default)]
        mode: ScopeMode,
        apps: Vec<String>,
    },
    /// Reference an existing community Xposed module (orchestration only).
    ModuleRef {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        package: String,
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

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        module: String,
        prefs: HashMap<String, toml::Value>,
    },
    /// Reference a rule from the rule library.
    RuleRef {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        rule: String,
        #[serde(default)]
        scope: Vec<String>,
    },
    /// Inline hook definition (profile-private).
    Hook {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        #[serde(default)]
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

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        packages: Vec<String>,
    },
    /// Remote prefs shared between module app and hooked process.
    RemotePrefs {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        key: String,
        value: toml::Value,
    },
    /// Remote blob file (libxposed service).
    RemoteBlob {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        path: String,
        source: String,
    },
    /// Restore LSPosed backup (`.lsp.gz`).
    LsposedRestore {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        file: String,
    },
    /// Force-stop / reload target apps so hooks take effect.
    Reload {
        id: String,
        #[serde(default = "default_true")]
        enabled: bool,

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        packages: Vec<String>,
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

        #[serde(default)]
        #[serde(flatten)]
        common: ModCommon,        commands: Vec<String>,
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
        #[serde(flatten)]
        common: ModCommon,
        /// 「设置已应用」通道：查询持久化配置（settings get …）。
        #[serde(default)]
        applied_status_command: Option<String>,
        #[serde(default)]
        applied_status_pattern: Option<String>,
        /// 「实时生效」通道：可选，查询运行时状态（dumpsys …）。
        #[serde(default)]
        effective_status_command: Option<String>,
        #[serde(default)]
        effective_status_pattern: Option<String>,
        /// 前置条件：可选，不满足时 UI 禁用并提示 requires_hint。
        #[serde(default)]
        requires_command: Option<String>,
        #[serde(default)]
        requires_pattern: Option<String>,
        #[serde(default)]
        requires_hint: Option<String>,
        /// opt-in 自动补救命令；留空则前置不满足时仅提示。
        #[serde(default)]
        auto_prereq_command: Option<String>,
        /// Legacy single-channel status fields — mapped onto applied_* at parse.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        status_command: Option<String>,
        /// Legacy single-channel status pattern — mapped onto applied_* at parse.
        #[serde(default, skip_serializing_if = "Option::is_none")]
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

    /// Map legacy `status_command` / `status_pattern` onto the applied channel
    /// (explicitly configured applied_* wins). Idempotent.
    pub fn normalize_legacy_fields(&mut self) {
        for m in &mut self.mods {
            if let ModEntry::ShellToggle {
                status_command,
                status_pattern,
                applied_status_command,
                applied_status_pattern,
                ..
            } = m
            {
                if applied_status_command.is_none() && status_command.is_some() {
                    *applied_status_command = status_command.take();
                } else {
                    *status_command = None;
                }
                if applied_status_pattern.is_none() && status_pattern.is_some() {
                    *applied_status_pattern = status_pattern.take();
                } else {
                    *status_pattern = None;
                }
            }
        }
    }

    pub fn mod_ids(&self) -> Vec<&str> {
        self.mods.iter().map(|m| m.id()).collect()
    }
}

impl std::str::FromStr for Profile {
    type Err = crate::error::ModspecError;

    fn from_str(content: &str) -> Result<Self> {
        let mut profile: Self = toml::from_str(content)?;
        profile.normalize_legacy_fields();
        Ok(profile)
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

    /// Shared metadata (description / aliases / category) flattened into every variant.
    pub fn common(&self) -> &ModCommon {
        match self {
            Self::LsposedModule { common, .. }
            | Self::Scope { common, .. }
            | Self::ModuleRef { common, .. }
            | Self::ModulePrefs { common, .. }
            | Self::RuleRef { common, .. }
            | Self::Hook { common, .. }
            | Self::DynamicScope { common, .. }
            | Self::RemotePrefs { common, .. }
            | Self::RemoteBlob { common, .. }
            | Self::LsposedRestore { common, .. }
            | Self::Reload { common, .. }
            | Self::PostAction { common, .. }
            | Self::ShellToggle { common, .. } => common,
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
            applied_status_command,
            ..
        } = entry
        else {
            panic!("expected shell_toggle mod");
        };
        assert_eq!(title, "5GHz 热点");
        assert_eq!(on_command, "cmd wifi force-softap-band enabled 5");
        assert_eq!(off_command, "cmd wifi force-softap-band disabled");
        // Legacy status_command maps onto the applied channel at parse time.
        assert_eq!(
            applied_status_command.as_deref(),
            Some("cmd wifi force-softap-band")
        );
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

    #[test]
    fn legacy_status_fields_map_to_applied_channel() {
        let content = r#"
            mspec_version = "1"

            [meta]
            id = "toggle-legacy"
            name = "toggle"

            [[mods]]
            id = "hotspot-5ghz"
            type = "shell_toggle"
            title = "5GHz 热点"
            on_command = "on"
            off_command = "off"
            status_command = "settings get global band"
            status_pattern = "^5$"
        "#;
        let profile: Profile = content.parse().expect("parse profile");
        // Normalization is also serialized away: no legacy keys survive.
        let json = serde_json::to_string(&profile).unwrap();
        assert!(!json.contains("\"status_command\""));
        assert!(!json.contains("\"status_pattern\""));
        let ModEntry::ShellToggle {
            status_command,
            status_pattern,
            applied_status_command,
            applied_status_pattern,
            ..
        } = &profile.mods[0]
        else {
            panic!("expected shell_toggle mod");
        };
        assert!(status_command.is_none());
        assert!(status_pattern.is_none());
        assert_eq!(
            applied_status_command.as_deref(),
            Some("settings get global band")
        );
        assert_eq!(applied_status_pattern.as_deref(), Some("^5$"));
    }

    #[test]
    fn explicit_applied_wins_over_legacy() {
        let content = r#"
            mspec_version = "1"

            [meta]
            id = "toggle-mixed"
            name = "toggle"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            on_command = "on"
            off_command = "off"
            applied_status_command = "cmd new"
            status_command = "cmd old"
        "#;
        let profile: Profile = content.parse().expect("parse profile");
        let ModEntry::ShellToggle {
            status_command,
            applied_status_command,
            ..
        } = &profile.mods[0]
        else {
            panic!("expected shell_toggle mod");
        };
        assert_eq!(applied_status_command.as_deref(), Some("cmd new"));
        assert!(status_command.is_none());
    }

    #[test]
    fn parse_three_channel_shell_toggle() {
        let content = r#"
            mspec_version = "1"

            [meta]
            id = "toggle-channels"
            name = "toggle"

            [[mods]]
            id = "hotspot-5ghz"
            type = "shell_toggle"
            title = "5GHz 热点强制"
            description = "强制热点使用 5GHz 频段"
            aliases = ["softap 5g", "便携热点"]
            category = "network/hotspot"
            on_command = "on"
            off_command = "off"
            applied_status_command = "settings get global wifi_ap_settings_band"
            applied_status_pattern = "^5$"
            effective_status_command = "dumpsys wifi | grep mCurrentSoftApInfoMap"
            effective_status_pattern = "frequency= 5"
            requires_command = "dumpsys wifi | grep softApEnabled"
            requires_pattern = "softApEnabled=true"
            requires_hint = "需先开启热点"
            auto_prereq_command = ""
        "#;
        let profile: Profile = content.parse().expect("parse profile");
        let entry = &profile.mods[0];
        let ModEntry::ShellToggle { common, .. } = entry else {
            panic!("expected shell_toggle mod");
        };
        assert_eq!(common.description.as_deref(), Some("强制热点使用 5GHz 频段"));
        assert_eq!(common.aliases, vec!["softap 5g", "便携热点"]);
        assert_eq!(common.category.as_deref(), Some("network/hotspot"));
        assert_eq!(entry.common().category, common.category);
        let ModEntry::ShellToggle {
            applied_status_command,
            applied_status_pattern,
            effective_status_command,
            effective_status_pattern,
            requires_command,
            requires_pattern,
            requires_hint,
            auto_prereq_command,
            ..
        } = entry
        else {
            panic!("expected shell_toggle mod");
        };
        assert_eq!(
            applied_status_command.as_deref(),
            Some("settings get global wifi_ap_settings_band")
        );
        assert_eq!(applied_status_pattern.as_deref(), Some("^5$"));
        assert!(effective_status_command
            .as_deref()
            .unwrap()
            .contains("mCurrentSoftApInfoMap"));
        assert_eq!(effective_status_pattern.as_deref(), Some("frequency= 5"));
        assert!(requires_command.as_deref().unwrap().contains("softApEnabled"));
        assert_eq!(requires_pattern.as_deref(), Some("softApEnabled=true"));
        assert_eq!(requires_hint.as_deref(), Some("需先开启热点"));
        assert_eq!(auto_prereq_command.as_deref(), Some(""));
    }

    #[test]
    fn parse_categories_declaration() {
        let content = r#"
            mspec_version = "1"

            [meta]
            id = "cat-test"
            name = "cats"

            [[categories]]
            id = "network"
            title = "网络"

            [[categories]]
            id = "network/hotspot"
            title = "热点"
            icon = "wifi_tethering"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            on_command = "on"
            off_command = "off"
            category = "network/hotspot"

            [[mods]]
            id = "t2"
            type = "reload"
            packages = ["a"]
        "#;
        let profile: Profile = content.parse().expect("parse profile");
        assert_eq!(profile.categories.len(), 2);
        assert_eq!(profile.categories[1].id, "network/hotspot");
        assert_eq!(profile.categories[1].title, "热点");
        assert_eq!(profile.categories[1].icon.as_deref(), Some("wifi_tethering"));
        assert_eq!(profile.mods[0].common().category.as_deref(), Some("network/hotspot"));
        // Old profiles without category keep parsing.
        assert_eq!(profile.mods[1].common().category, None);
    }
}
