//! Read-only LSPosed/module diagnostics for the PC-side debug loop.
//!
//! This surface reports framework/scope/rules state only; it never exposes
//! arbitrary root file reads or personal data.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ModuleDiagnostics {
    pub lsposed_framework: Option<String>,
    pub xposed_service_bound: bool,
    /// Packages currently in the module scope (read-only snapshot).
    #[serde(default)]
    pub scope: Vec<String>,
    #[serde(default)]
    pub active_rules: Vec<String>,
    pub rules_generation: Option<i64>,
    pub lsposed_cli_available: bool,
    pub root_available: bool,
    /// `"journal"` or `"ring_only"` — whether hook events are durably persisted.
    pub event_source: String,
    pub tailer_running: bool,
    /// Local server health (supervisor report): accept loops alive, restart
    /// counts, and the most recent supervised failure.
    #[serde(default)]
    pub server_http_alive: bool,
    #[serde(default)]
    pub server_ws_alive: bool,
    #[serde(default)]
    pub server_http_restarts: u32,
    #[serde(default)]
    pub server_ws_restarts: u32,
    #[serde(default)]
    pub server_last_error: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn diagnostics_roundtrip() {
        let diagnostics = ModuleDiagnostics {
            lsposed_framework: Some("LSPosed-mod 1.10.1 (7024)".into()),
            xposed_service_bound: true,
            scope: vec!["system".into(), "com.example.target".into()],
            active_rules: vec!["test/smoke-joyose".into()],
            rules_generation: Some(1_700_000_000_000),
            lsposed_cli_available: true,
            root_available: true,
            event_source: "journal".into(),
            tailer_running: true,
            server_http_alive: true,
            server_ws_alive: false,
            server_http_restarts: 1,
            server_ws_restarts: 0,
            server_last_error: Some("accept loop stopped; supervisor restarting".into()),
        };
        let json = serde_json::to_value(&diagnostics).unwrap();
        let decoded: ModuleDiagnostics = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, diagnostics);
        assert_eq!(decoded.scope.len(), 2);
    }

    #[test]
    fn diagnostics_accept_legacy_payload_without_server_health() {
        let legacy = serde_json::json!({
            "lsposed_framework": null,
            "xposed_service_bound": false,
            "scope": [],
            "active_rules": [],
            "rules_generation": null,
            "lsposed_cli_available": false,
            "root_available": false,
            "event_source": "journal",
            "tailer_running": false
        });
        let decoded: ModuleDiagnostics = serde_json::from_value(legacy).unwrap();
        assert!(!decoded.server_http_alive);
        assert!(!decoded.server_ws_alive);
        assert_eq!(decoded.server_http_restarts, 0);
        assert_eq!(decoded.server_last_error, None);
    }
}
