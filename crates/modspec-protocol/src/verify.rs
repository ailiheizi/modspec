//! RPC verify / soft-restart wire types.
//!
//! `verify` reports agent-side drift between the profile expectation and live
//! device state; `soft_restart` re-applies rules through LSPosed's runtime
//! without a full process restart.

use serde::{Deserialize, Serialize};

/// PC → agent: request a drift report for a profile.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VerifyParams {
    /// Profile id to compare against; `None` compares the active profile.
    #[serde(default)]
    pub profile_id: Option<String>,
}

/// Result of `verify` RPC: one entry per drifted expectation.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct VerifyResponse {
    #[serde(default)]
    pub drift: Vec<DriftItem>,
}

/// One divergence between the profile expectation and live device state.
/// Every field is optional so the struct tolerates agent payloads that are
/// still evolving (all `None` means the item carried no structured detail).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct DriftItem {
    #[serde(default)]
    pub mod_id: Option<String>,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub expected: Option<String>,
    #[serde(default)]
    pub actual: Option<String>,
    #[serde(default)]
    pub reason: Option<String>,
}

/// PC → agent: re-run the applied profile, optionally only the failed steps.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SoftRestartParams {
    /// Only republish rule files, skipping target restarts.
    #[serde(default)]
    pub rules_only: bool,
}

/// Result of `soft_restart` RPC.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct SoftRestartResponse {
    #[serde(default)]
    pub hot_reload_ok: bool,
    #[serde(default)]
    pub hot_reload_failed: bool,
    #[serde(default)]
    pub hot_reload_unsupported: bool,
    #[serde(default)]
    pub running_targets: Vec<String>,
    #[serde(default)]
    pub restarted_packages: Vec<String>,
    #[serde(default)]
    pub message: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn verify_params_serialize_with_profile_id() {
        let params = VerifyParams {
            profile_id: Some("hyper-perf-pack".into()),
        };
        let value = serde_json::to_value(&params).unwrap();
        assert_eq!(
            value,
            serde_json::json!({ "profile_id": "hyper-perf-pack" })
        );
    }

    #[test]
    fn verify_response_roundtrips() {
        let response = VerifyResponse {
            drift: vec![DriftItem {
                mod_id: Some("hyper-perf-pack/joyose".into()),
                kind: Some("rule_ref".into()),
                expected: Some("generation 41".into()),
                actual: Some("generation 42".into()),
                reason: Some("rule changed on device".into()),
            }],
        };
        let json = serde_json::to_value(&response).unwrap();
        let decoded: VerifyResponse = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, response);
        assert_eq!(
            decoded.drift[0].mod_id.as_deref(),
            Some("hyper-perf-pack/joyose")
        );
    }

    #[test]
    fn verify_response_tolerates_partial_drift_items() {
        let json = serde_json::json!({
            "drift": [
                { "mod_id": "foo" },
                {},
                { "mod_id": "bar", "kind": "rule_ref", "reason": "missing" }
            ]
        });
        let decoded: VerifyResponse = serde_json::from_value(json).unwrap();
        assert_eq!(decoded.drift.len(), 3);
        assert_eq!(decoded.drift[0].mod_id.as_deref(), Some("foo"));
        assert_eq!(decoded.drift[0].kind, None);
        assert_eq!(decoded.drift[1].mod_id, None);
        assert_eq!(decoded.drift[2].kind.as_deref(), Some("rule_ref"));
    }

    #[test]
    fn verify_response_tolerates_missing_drift() {
        let decoded: VerifyResponse = serde_json::from_value(serde_json::json!({})).unwrap();
        assert!(decoded.drift.is_empty());
    }

    #[test]
    fn soft_restart_params_roundtrips() {
        let params = SoftRestartParams { rules_only: true };
        let json = serde_json::to_value(&params).unwrap();
        assert_eq!(json, serde_json::json!({ "rules_only": true }));
        let decoded: SoftRestartParams = serde_json::from_value(serde_json::json!({})).unwrap();
        assert!(!decoded.rules_only);
    }

    #[test]
    fn soft_restart_response_roundtrips() {
        let response = SoftRestartResponse {
            hot_reload_ok: true,
            hot_reload_failed: false,
            hot_reload_unsupported: false,
            running_targets: vec!["com.example.target".into()],
            restarted_packages: vec!["com.example.target".into()],
            message: "soft restart done".into(),
        };
        let json = serde_json::to_value(&response).unwrap();
        let decoded: SoftRestartResponse = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, response);
        assert!(decoded.hot_reload_ok);
    }

    #[test]
    fn soft_restart_response_tolerates_legacy_payload() {
        let decoded: SoftRestartResponse =
            serde_json::from_value(serde_json::json!({ "message": "n/a" })).unwrap();
        assert!(!decoded.hot_reload_ok);
        assert!(!decoded.hot_reload_failed);
        assert!(!decoded.hot_reload_unsupported);
        assert!(decoded.running_targets.is_empty());
        assert!(decoded.restarted_packages.is_empty());
        assert_eq!(decoded.message, "n/a");
    }
}
