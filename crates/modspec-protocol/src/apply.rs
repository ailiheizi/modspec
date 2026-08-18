//! RPC apply / job response types.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Result of `apply_profile` RPC.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ApplyProfileResponse {
    pub job_id: String,
}

/// PC → agent: re-run the applied profile, optionally only the failed steps.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ReapplyParams {
    #[serde(default)]
    pub only_failed: bool,
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

/// PC → agent: deploy one complete rule document.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DeployRuleParams {
    pub rule_id: String,
    pub content: String,
    #[serde(default)]
    pub packages: Vec<String>,
    #[serde(default = "default_true")]
    pub ensure_scope: bool,
}

/// Agent acknowledgement after the rule is durable and published to hook processes.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DeployRuleResponse {
    pub rule_id: String,
    pub stored: bool,
    pub publish_mode: String,
    pub generation: i64,
    pub scope_status: String,
    #[serde(default)]
    pub scope_packages: Vec<String>,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RestartTargetsParams {
    pub packages: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RestartTargetsResponse {
    #[serde(default)]
    pub restarted: Vec<String>,
    /// Force-stopped successfully but no launcher activity could be started.
    #[serde(default)]
    pub needs_trigger: Vec<String>,
    /// Package was not installed on the device; force-stop/launch skipped.
    #[serde(default)]
    pub not_installed: Vec<String>,
    /// Stopped but the launcher command exited with an error other than "no activity".
    #[serde(default)]
    pub launch_failed: Vec<String>,
    /// Force-stop itself failed, with per-package error messages.
    #[serde(default)]
    pub failed: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct CollectLogsParams {
    /// Opaque, monotonically increasing cursor: only return entries with
    /// `event_id > after_event_id`. Pass the previous response's `next_event_id`.
    /// This is the reliable incremental-collection cursor.
    #[serde(default)]
    pub after_event_id: Option<i64>,
    /// Deprecated compatibility filter (legacy millisecond logcat cursor).
    /// Ignored when `after_event_id` is present; kept only for old CLIs.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub since_ms: Option<i64>,
    #[serde(default = "default_log_limit")]
    pub limit: u32,
    #[serde(default)]
    pub rule_id: Option<String>,
    #[serde(default)]
    pub script_id: Option<String>,
    #[serde(default)]
    pub min_generation: Option<i64>,
    #[serde(default)]
    pub exact_generation: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HookLogEntry {
    /// Agent-assigned monotonic id; used as the incremental-collection cursor.
    #[serde(default)]
    pub event_id: i64,
    pub timestamp_ms: i64,
    pub level: String,
    pub tag: String,
    pub event: String,
    #[serde(default)]
    pub generation: Option<i64>,
    #[serde(default)]
    pub rule_id: Option<String>,
    #[serde(default)]
    pub script_id: Option<String>,
    #[serde(default)]
    pub package: Option<String>,
    pub message: String,
    #[serde(default)]
    pub raw: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CollectLogsResponse {
    #[serde(default)]
    pub entries: Vec<HookLogEntry>,
    /// Cursor for the next poll: the last delivered `event_id` (or the requested
    /// `after_event_id` when nothing matched). Monotonic; safe to echo back.
    pub next_event_id: i64,
    /// Oldest event_id still retained by the agent ring. When
    /// `truncated == true` the requested cursor predates it and history was lost.
    #[serde(default)]
    pub first_event_id: Option<i64>,
    /// True when the requested cursor predates the oldest retained event, i.e. the
    /// agent rotated its bounded ring past the cursor. The PC should restart
    /// collection from `first_event_id` (or warn about a gap).
    #[serde(default)]
    pub truncated: bool,
    /// Where entries came from: "journal" (durable event journal seeded by the
    /// agent) or "ring_only" (in-memory ring without durable hook ingestion).
    #[serde(default = "default_source")]
    pub source: String,
}

fn default_source() -> String {
    "journal".into()
}

fn default_true() -> bool {
    true
}
fn default_log_limit() -> u32 {
    200
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
