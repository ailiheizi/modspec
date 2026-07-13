//! RPC apply / job response types.

use serde::{Deserialize, Serialize};

/// Result of `apply_profile` RPC.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ApplyProfileResponse {
    pub job_id: String,
}

/// Result of `reapply` RPC.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ReapplyResponse {
    pub job_id: String,
}

/// Result of `toggle_mod` RPC.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ToggleModResponse {
    pub ok: bool,
}

/// Result of `ping` RPC.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PingResponse {
    pub pong: bool,
}

/// Progress notification payload (`apply_progress` event).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ApplyProgress {
    pub job_id: String,
    pub step: String,
    #[serde(default)]
    pub percent: Option<u8>,
}

/// Completion notification payload (`apply_completed` event).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ApplyCompleted {
    pub job_id: String,
    pub profile_id: String,
}

/// Failure notification payload (`apply_failed` event).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ApplyFailed {
    pub job_id: String,
    pub error: String,
}
