//! Raw `su` command execution (`exec_su`) — runs an arbitrary shell command
//! on the device through the agent, outside any hook/script context.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct ExecSuParams {
    /// Shell command passed verbatim to `su -c`.
    pub command: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ExecSuResponse {
    /// True when the su channel worked and the command exited zero.
    pub success: bool,
    /// Merged stdout+stderr on success; absent on failure.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
    /// Failure message when the command could not be run.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exec_su_params_roundtrip() {
        let params = ExecSuParams {
            command: "cmd wifi force-softap-band enabled 5".into(),
        };
        let json = serde_json::to_value(&params).unwrap();
        assert_eq!(json["command"], "cmd wifi force-softap-band enabled 5");
        let decoded: ExecSuParams = serde_json::from_value(json).unwrap();
        assert_eq!(decoded, params);
    }

    #[test]
    fn exec_su_response_success_shape() {
        let resp = ExecSuResponse {
            success: true,
            output: Some("ok".into()),
            error: None,
        };
        let json = serde_json::to_value(&resp).unwrap();
        assert_eq!(json["success"], true);
        assert_eq!(json["output"], "ok");
        assert!(json.get("error").is_none());
    }

    #[test]
    fn exec_su_response_failure_shape() {
        let resp = ExecSuResponse {
            success: false,
            output: None,
            error: Some("no working su binary".into()),
        };
        let json = serde_json::to_value(&resp).unwrap();
        assert_eq!(json["success"], false);
        assert_eq!(json["error"], "no working su binary");
        assert!(json.get("output").is_none());
    }
}
