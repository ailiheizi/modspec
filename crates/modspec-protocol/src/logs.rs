//! Bounded, filtered diagnostic log retrieval (raw logcat), separate from the
//! structured hook-event journal served by `collect_logs`.

use serde::{Deserialize, Serialize};

pub const MAX_LOG_LIMIT: u32 = 1000;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct GetLogsParams {
    /// Filter to log lines whose tid/pid belongs to this package.
    #[serde(default)]
    pub package: Option<String>,
    /// Filter to an exact logcat tag.
    #[serde(default)]
    pub tag: Option<String>,
    /// Max entries returned (Agent caps at [`MAX_LOG_LIMIT`]).
    #[serde(default = "default_log_limit")]
    pub limit: u32,
    /// Drop lines older than this epoch-millis timestamp.
    #[serde(default)]
    pub since_ms: Option<i64>,
}

impl Default for GetLogsParams {
    fn default() -> Self {
        Self {
            package: None,
            tag: None,
            limit: default_log_limit(),
            since_ms: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LogEntry {
    pub timestamp_ms: i64,
    pub level: String,
    pub tag: String,
    pub pid: Option<u32>,
    pub tid: Option<u32>,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct GetLogsResponse {
    pub entries: Vec<LogEntry>,
    /// True when more matching lines existed beyond the limit.
    pub truncated: bool,
    /// `"logcat"` when derived from a `logcat -d` dump, `"none"` otherwise.
    pub source: String,
    pub root_available: bool,
    /// Pids the package filter resolved to (useful for diagnosing missing logs).
    #[serde(default)]
    pub resolved_pids: Vec<u32>,
}

fn default_log_limit() -> u32 {
    200
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn get_logs_params_defaults() {
        let params = GetLogsParams::default();
        assert_eq!(params.package, None);
        assert_eq!(params.tag, None);
        assert_eq!(params.limit, 200);
        assert_eq!(params.since_ms, None);
    }

    #[test]
    fn get_logs_response_roundtrips() {
        let response = GetLogsResponse {
            entries: vec![LogEntry {
                timestamp_ms: 1_700_000_000_123,
                level: "E".into(),
                tag: "AndroidRuntime".into(),
                pid: Some(1234),
                tid: Some(1234),
                message: "FATAL EXCEPTION".into(),
            }],
            truncated: false,
            source: "logcat".into(),
            root_available: true,
            resolved_pids: vec![1234],
        };
        let json = serde_json::to_value(&response).unwrap();
        let decoded: GetLogsResponse = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, response);
    }
}
