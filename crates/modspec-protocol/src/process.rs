//! Typed process inventory: which target packages are running, with pids/uids.

use serde::{Deserialize, Serialize};

pub const MAX_PROCESS_LIMIT: u32 = 2000;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProcessListParams {
    /// Restrict to processes belonging to this package (any uid/process name).
    #[serde(default)]
    pub package: Option<String>,
    #[serde(default = "default_process_limit")]
    pub limit: u32,
}

impl Default for ProcessListParams {
    fn default() -> Self {
        Self {
            package: None,
            limit: default_process_limit(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProcessInfo {
    /// Owning package when derivable from the process name (may be `None` for
    /// kernel/system processes such as `system_server`).
    pub package: Option<String>,
    pub pid: u32,
    /// `uid`/`user` of the process owner (root reports all; shell only what it sees).
    pub uid: Option<u32>,
    pub user: String,
    /// Scheduling state letter from `ps` (R/S/D/Z/T…).
    pub state: String,
    /// Process name (`ps` NAME column; includes `:`-suffixed process parts).
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProcessListResponse {
    pub processes: Vec<ProcessInfo>,
    pub total: u32,
    pub truncated: bool,
    /// `"ps"` when derived from `ps -A`, `"none"` when root is unavailable.
    pub source: String,
}

fn default_process_limit() -> u32 {
    200
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn process_params_defaults() {
        let params = ProcessListParams::default();
        assert_eq!(params.package, None);
        assert_eq!(params.limit, 200);
    }

    #[test]
    fn process_list_response_roundtrips() {
        let response = ProcessListResponse {
            processes: vec![ProcessInfo {
                package: Some("com.example.target".into()),
                pid: 1234,
                uid: Some(10123),
                user: "u0_a123".into(),
                state: "S".into(),
                name: "com.example.target".into(),
            }],
            total: 1,
            truncated: false,
            source: "ps".into(),
        };
        let json = serde_json::to_value(&response).unwrap();
        let decoded: ProcessListResponse = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, response);
    }
}
