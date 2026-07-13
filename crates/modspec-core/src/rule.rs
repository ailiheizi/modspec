use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::Result;

pub const RULE_VERSION: &str = "1";

/// Atomic reusable hook rule: `*.rule.toml`
/// Pattern inspired by HyperCeiler `rules/` layout + HMA-OSS runtime config reload.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RuleFile {
    pub rule_version: String,
    pub meta: RuleMeta,
    #[serde(default)]
    pub compatible: RuleCompatible,
    /// Default hooks when no variant matches.
    #[serde(default)]
    pub hooks: Vec<RuleHook>,
    /// ROM/version-specific branches (HyperCeiler-style).
    #[serde(default)]
    pub variants: Vec<RuleVariant>,
    #[serde(default)]
    pub verify: Option<RuleVerify>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RuleMeta {
    /// Namespaced id, e.g. `xiaomi/joyose/block-cloud-fetch`
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub min_android: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct RuleCompatible {
    #[serde(default)]
    pub packages: Vec<String>,
    #[serde(default)]
    pub oem: Vec<String>,
    #[serde(default)]
    pub rom: Vec<String>,
    #[serde(default)]
    pub scope_required: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RuleVariant {
    pub name: String,
    #[serde(default)]
    pub when: VariantWhen,
    #[serde(default)]
    pub hooks: Vec<RuleHook>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct VariantWhen {
    #[serde(default)]
    pub android: Option<String>,
    #[serde(default)]
    pub oem: Option<String>,
    #[serde(default)]
    pub rom: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RuleHook {
    pub phase: HookPhase,
    pub target: HookTarget,
    pub action: HookAction,
    #[serde(default)]
    pub options: Option<HookRuleOptions>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum HookPhase {
    OnModuleLoaded,
    OnPackageLoaded,
    OnPackageReady,
    OnSystemServerStarting,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HookTarget {
    #[serde(default)]
    pub resolver: TargetResolver,
    #[serde(default)]
    pub class: Option<String>,
    #[serde(default)]
    pub method: Option<String>,
    #[serde(default)]
    pub signature: Option<String>,
    #[serde(default)]
    pub query: Option<DexKitQuery>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum TargetResolver {
    #[default]
    Static,
    Dexkit,
}

/// DexKit query — aligned with LuckyPray/DexKit find API (HyperCeiler usage).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DexKitQuery {
    #[serde(default)]
    pub class: Option<String>,
    #[serde(default)]
    pub method: Option<String>,
    #[serde(default)]
    pub signature: Option<String>,
    /// Require unique match; fail like HyperCeiler NonUniqueResultException handling.
    #[serde(default = "default_true")]
    pub unique: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum HookAction {
    Before {
        #[serde(default)]
        set_args: Option<Vec<HookValue>>,
    },
    After {
        #[serde(default)]
        set_result: Option<HookValue>,
    },
    Replace {
        #[serde(default)]
        body: Option<String>,
    },
    ReturnConst {
        value: HookValue,
    },
    Throw {
        exception: String,
        #[serde(default)]
        message: Option<String>,
    },
    Skip,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum HookValue {
    Void,
    Boolean { data: bool },
    Int { data: i32 },
    Long { data: i64 },
    Float { data: f32 },
    Double { data: f64 },
    String { data: String },
    Null,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HookRuleOptions {
    #[serde(default)]
    pub priority: Option<i32>,
    #[serde(default)]
    pub exception_mode: Option<crate::profile::ExceptionMode>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RuleVerify {
    #[serde(default)]
    pub log_success: Option<String>,
    #[serde(default)]
    pub log_failure: Option<String>,
}

fn default_true() -> bool {
    true
}

impl RuleFile {
    pub fn from_str(content: &str) -> Result<Self> {
        Ok(toml::from_str(content)?)
    }

    pub fn from_file(path: impl AsRef<Path>) -> Result<Self> {
        let content = std::fs::read_to_string(path)?;
        Self::from_str(&content)
    }
}

/// Convenience alias used in docs.
pub type HookRule = RuleHook;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_example_rule() {
        let content =
            include_str!("../../../rules/xiaomi/joyose/block-cloud-fetch.rule.toml");
        let rule = RuleFile::from_str(content).expect("parse rule");
        assert_eq!(rule.meta.id, "xiaomi/joyose/block-cloud-fetch");
    }
}
