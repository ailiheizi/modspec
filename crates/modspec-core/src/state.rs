use std::collections::HashMap;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Deserializer, Serialize};

fn deserialize_last_apply<'de, D>(deserializer: D) -> Result<Option<DateTime<Utc>>, D::Error>
where
    D: Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum LastApply {
        Rfc3339(String),
        Millis(i64),
    }
    match Option::<LastApply>::deserialize(deserializer)? {
        None => Ok(None),
        Some(LastApply::Rfc3339(s)) => DateTime::parse_from_rfc3339(&s)
            .map(|dt| dt.with_timezone(&Utc))
            .map(Some)
            .map_err(serde::de::Error::custom),
        Some(LastApply::Millis(ms)) => DateTime::from_timestamp_millis(ms)
            .ok_or_else(|| serde::de::Error::custom(format!("invalid millis timestamp: {ms}")))
            .map(Some),
    }
}

/// Runtime state persisted on device — pattern from HMA-OSS `config.json` + LSPosed backup metadata.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct ProfileState {
    #[serde(default)]
    pub active_profile: Option<String>,
    #[serde(default)]
    pub active_rules: Vec<String>,
    #[serde(default, deserialize_with = "deserialize_last_apply")]
    pub last_apply: Option<DateTime<Utc>>,
    #[serde(default)]
    pub items: HashMap<String, ItemState>,
    #[serde(default)]
    pub lsposed_modules: HashMap<String, LsposedModuleState>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ItemState {
    pub enabled: bool,
    pub status: ApplyStatus,
    #[serde(default)]
    pub changes: Vec<String>,
    #[serde(default)]
    pub hook_ids: Vec<String>,
    #[serde(default)]
    pub last_verify: Option<VerifyStatus>,
    #[serde(default)]
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ApplyStatus {
    Pending,
    Applied,
    Manual,
    Failed,
    Disabled,
    Drifted,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum VerifyStatus {
    Ok,
    Failed,
    Skipped,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct LsposedModuleState {
    pub enabled: bool,
    #[serde(default)]
    pub scope: Vec<String>,
}

impl ProfileState {
    pub fn from_json(content: &str) -> crate::Result<Self> {
        Ok(serde_json::from_str(content)?)
    }

    pub fn to_json_pretty(&self) -> crate::Result<String> {
        Ok(serde_json::to_string_pretty(self)?)
    }
}
