//! Script package RPC types and PC-first script session orchestration.
//!
//! The RPC surface mirrors `apply.rs`: deploy/list/enable/disable/remove/reload
//! with explicit `request_id` idempotency — a mutation that reached the Agent
//! is never blindly retried; the same `request_id` yields the stored response.
//!
//! [`run_script_session`] drives one interactive session: deploy → ensure scope
//! → restart the hook processes → poll structured `script_*` events until the
//! expected outcome or the deadline. It is transport-agnostic and tested
//! against a loopback fake Agent (`modspec-protocol/tests/script_session.rs`).

use std::collections::{BTreeMap, HashSet};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::time::{sleep, timeout_at, Instant};
use uuid::Uuid;

use crate::connection::{classify_retry, RetryClass};
use crate::{
    CollectLogsParams, HookLogEntry, RestartTargetsParams, RestartTargetsResponse, RpcClient,
    RpcClientError,
};

/// PC → agent: validate a script bundle (manifest + files) without storing it.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptValidateParams {
    pub manifest: String,
    #[serde(default)]
    pub files: Vec<ScriptFileDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptValidateResponse {
    pub ok: bool,
    #[serde(default)]
    pub errors: Vec<String>,
}

/// One bundled source file as carried over the wire.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptFileDto {
    pub name: String,
    pub content: String,
}

/// PC → agent: store a script package and publish it to hook processes.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptDeployParams {
    /// Idempotency key: the Agent stores the response for a seen `request_id`
    /// and replays it instead of duplicating the mutation.
    pub request_id: String,
    pub script_id: String,
    pub manifest: String,
    #[serde(default)]
    pub files: Vec<ScriptFileDto>,
    #[serde(default = "default_true")]
    pub ensure_scope: bool,
    /// Also make the script the active one (exclusive) after storing.
    #[serde(default)]
    pub activate: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptDeployResponse {
    pub script_id: String,
    pub stored: bool,
    pub publish_mode: String,
    pub generation: i64,
    pub engine: String,
    pub content_hash: String,
    pub scope_status: String,
    #[serde(default)]
    pub scope_packages: Vec<String>,
    pub message: String,
}

/// PC → agent: make a script the active one (exclusive by default).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptEnableParams {
    pub request_id: String,
    pub script_id: String,
    #[serde(default = "default_true")]
    pub exclusive: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptEnableResponse {
    pub script_id: String,
    pub enabled: bool,
    /// Scripts deactivated by the exclusive switch.
    #[serde(default)]
    pub disabled: Vec<String>,
    pub generation: i64,
}

/// PC → agent: deactivate a script without removing its files.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptDisableParams {
    pub request_id: String,
    pub script_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptDisableResponse {
    pub script_id: String,
    pub disabled: bool,
    pub generation: i64,
}

/// PC → agent: delete a script package and its state.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptRemoveParams {
    pub request_id: String,
    pub script_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptRemoveResponse {
    pub script_id: String,
    pub removed: bool,
    pub generation: i64,
}

/// PC → agent: re-publish a stored script (optional target restart) after a
/// manual file edit, or to push a hot reload without a full deploy.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptReloadParams {
    pub request_id: String,
    pub script_id: String,
    /// Force-stop and relaunch the script's hook processes after publishing.
    #[serde(default)]
    pub restart: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptReloadResponse {
    pub script_id: String,
    pub reload_started: bool,
    pub generation: i64,
    #[serde(default)]
    pub restarted: Vec<String>,
    #[serde(default)]
    pub needs_trigger: Vec<String>,
    #[serde(default)]
    pub not_installed: Vec<String>,
    #[serde(default)]
    pub launch_failed: Vec<String>,
    #[serde(default)]
    pub failed: BTreeMap<String, String>,
}

/// PC → agent: install the on-demand Frida gadget from the staged location
/// (`/data/local/tmp/modspec/frida/`, pushed by the PC). No request_id needed:
/// the operation is idempotent (copy + relabel) and never auto-retried.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct InstallFridaGadgetParams {}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct InstallFridaGadgetResponse {
    pub installed: bool,
    #[serde(default)]
    pub frida: bool,
    #[serde(default)]
    pub native_hook: bool,
    #[serde(default)]
    pub abi: Option<String>,
}

/// PC → agent: list stored scripts and their persisted lifecycle state.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct ScriptListParams {}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptInfo {
    pub script_id: String,
    pub name: String,
    pub engine: String,
    #[serde(default)]
    pub version: Option<String>,
    pub content_hash: String,
    /// Explicitly active (first-class selection; exclusive).
    pub active: bool,
    #[serde(default)]
    pub generation: Option<i64>,
    #[serde(default)]
    pub last_loaded_ms: Option<i64>,
    #[serde(default)]
    pub last_hit_ms: Option<i64>,
    #[serde(default)]
    pub last_error: Option<String>,
    #[serde(default)]
    pub hit_count: i64,
    #[serde(default)]
    pub error_count: i64,
    #[serde(default)]
    pub packages: Vec<String>,
    #[serde(default)]
    pub target_packages: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptListResponse {
    #[serde(default)]
    pub scripts: Vec<ScriptInfo>,
    #[serde(default)]
    pub active_script: Option<String>,
}

fn default_true() -> bool {
    true
}

/// What counts as script session success.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScriptExpect {
    /// `script_loaded` (or a `script_hit`, which implies a loaded script).
    Loaded,
    /// An actual `script_hit`.
    Hit,
}

impl ScriptExpect {
    pub fn as_str(self) -> &'static str {
        match self {
            ScriptExpect::Loaded => "loaded",
            ScriptExpect::Hit => "hit",
        }
    }

    fn matches(&self, event: &str) -> bool {
        match self {
            ScriptExpect::Loaded => matches!(event, "script_loaded" | "script_hit"),
            ScriptExpect::Hit => event == "script_hit",
        }
    }
}

/// Configuration for one PC script session.
#[derive(Debug, Clone)]
pub struct ScriptSessionParams {
    pub script_id: String,
    pub manifest: String,
    pub files: Vec<ScriptFileDto>,
    /// Hook processes to restart after deploy (the manifest `packages`).
    pub packages: Vec<String>,
    pub wait: Duration,
    pub expect: ScriptExpect,
    pub no_restart: bool,
    /// Stable idempotency key for the deploy mutation; generated when `None`.
    /// Reuse it when resuming a session after a transport loss.
    pub request_id: Option<String>,
    /// Max entries per `collect_logs` poll.
    pub poll_limit: u32,
    /// Delay between polls (may be zero in tests).
    pub poll_interval: Duration,
    /// Bounded automatic retries for transient transport failures on the
    /// read-only `collect_logs` poll. Mutations are NEVER retried.
    pub collect_retries: u32,
}

impl Default for ScriptSessionParams {
    fn default() -> Self {
        Self {
            script_id: String::new(),
            manifest: String::new(),
            files: Vec::new(),
            packages: Vec::new(),
            wait: Duration::from_secs(20),
            expect: ScriptExpect::Loaded,
            no_restart: false,
            request_id: None,
            poll_limit: 200,
            poll_interval: Duration::from_millis(500),
            collect_retries: 3,
        }
    }
}

/// Structured events surfaced by [`run_script_session`] to a reporter.
#[derive(Debug, Clone)]
pub enum ScriptSessionEvent {
    ScriptUploaded {
        generation: i64,
        publish_mode: String,
        scope_status: String,
        content_hash: String,
        message: String,
    },
    DeployOnly {
        generation: i64,
    },
    ReloadStarted {
        targets: Vec<String>,
    },
    TargetRestarted {
        package: String,
    },
    /// Force-stopped but has no launcher activity; PC should trigger it manually.
    TargetStopped {
        package: String,
    },
    TargetNotInstalled {
        package: String,
    },
    LaunchFailed {
        package: String,
        message: String,
    },
    RestartFailed {
        package: String,
        message: String,
    },
    /// A structured journal entry matching the session's exact generation.
    Entry(HookLogEntry),
    /// A transient transport failure was retried on the read-only poll; the
    /// event cursor is preserved, so nothing is duplicated or lost.
    PollRetry {
        attempt: u32,
    },
    /// The agent reported a `script_error` (load failure, callback crash,
    /// circuit break, or an explicit script diagnostic).
    ScriptError {
        message: String,
    },
    Timeout {
        expected: ScriptExpect,
    },
}

/// Outcome of a finished script session.
#[derive(Debug, Clone)]
pub struct ScriptSessionOutcome {
    pub deployed: ScriptDeployResponse,
    pub restarted: Option<RestartTargetsResponse>,
    pub generation: i64,
    pub expected: ScriptExpect,
    /// The idempotency key used for the deploy mutation (reuse on resume).
    pub request_id: String,
    /// True when the expected event was observed before the deadline.
    pub success: bool,
    /// Number of `collect_logs` polls issued.
    pub polls: u32,
}

#[derive(Debug, Error)]
pub enum ScriptSessionError {
    #[error("{0}")]
    Invalid(String),
    #[error("script_deploy RPC failed: {0}")]
    Deploy(RpcClientError),
    #[error("script upload was not committed: {0}")]
    NotCommitted(String),
    #[error(
        "Agent published via {0}; PC sessions require a bound XposedService/RemoteFile channel"
    )]
    PublishMode(String),
    #[error("scope was not ensured ({0}): {1}; configure ModSpec scope and retry")]
    ScopeNotEnsured(String, String),
    #[error("restart_targets RPC failed: {0}")]
    RestartRpc(RpcClientError),
    #[error("failed to restart {count} target(s)")]
    RestartFailed { count: usize },
    #[error("collect_logs RPC failed: {0}")]
    Collect(RpcClientError),
    #[error("agent reported script_error: {0}")]
    ScriptError(String),
    #[error("session timed out waiting for script_{expected}; the script is deployed, so start/trigger the target and retry")]
    Timeout { expected: &'static str },
}

pub type ScriptSessionResult<T> = Result<T, ScriptSessionError>;

/// Deploy one script package, optionally restart its hook processes, and poll
/// structured `script_*` events.
///
/// Preconditions (mirrored from the CLI for testability):
/// - the script must declare at least one compatible package (unscoped sessions are refused);
/// - when restarting, `system`/`android` targets are refused.
///
/// Mutations carry a stable `request_id`; a failed deploy is never retried
/// automatically. `report` receives every [`ScriptSessionEvent`] in order.
pub async fn run_script_session<F>(
    client: &RpcClient,
    params: &ScriptSessionParams,
    mut report: F,
) -> ScriptSessionResult<ScriptSessionOutcome>
where
    F: FnMut(ScriptSessionEvent),
{
    if params.packages.is_empty() {
        return Err(ScriptSessionError::Invalid(
            "script has no compatible.packages; refusing an unscoped session".into(),
        ));
    }
    if !params.no_restart
        && params
            .packages
            .iter()
            .any(|p| matches!(p.as_str(), "system" | "android"))
    {
        return Err(ScriptSessionError::Invalid(
            "scripts in system_server processes cannot be force-stopped; rerun with --no-restart and reload the framework explicitly"
                .into(),
        ));
    }
    let request_id = params
        .request_id
        .clone()
        .unwrap_or_else(|| Uuid::new_v4().to_string());

    let deployed = client
        .script_deploy(&crate::ScriptDeployParams {
            request_id: request_id.clone(),
            script_id: params.script_id.clone(),
            manifest: params.manifest.clone(),
            files: params.files.clone(),
            ensure_scope: true,
            activate: true,
        })
        .await
        .map_err(ScriptSessionError::Deploy)?;
    report(ScriptSessionEvent::ScriptUploaded {
        generation: deployed.generation,
        publish_mode: deployed.publish_mode.clone(),
        scope_status: deployed.scope_status.clone(),
        content_hash: deployed.content_hash.clone(),
        message: deployed.message.clone(),
    });
    if !deployed.stored {
        return Err(ScriptSessionError::NotCommitted(deployed.message.clone()));
    }
    if deployed.publish_mode != "remote_file" {
        return Err(ScriptSessionError::PublishMode(
            deployed.publish_mode.clone(),
        ));
    }
    if !matches!(deployed.scope_status.as_str(), "applied" | "already") {
        return Err(ScriptSessionError::ScopeNotEnsured(
            deployed.scope_status.clone(),
            deployed.message.clone(),
        ));
    }

    let restarted = if params.no_restart {
        report(ScriptSessionEvent::DeployOnly {
            generation: deployed.generation,
        });
        None
    } else {
        report(ScriptSessionEvent::ReloadStarted {
            targets: params.packages.clone(),
        });
        let resp = client
            .restart_targets(&RestartTargetsParams {
                packages: params.packages.clone(),
            })
            .await
            .map_err(ScriptSessionError::RestartRpc)?;
        for package in &resp.restarted {
            report(ScriptSessionEvent::TargetRestarted {
                package: package.clone(),
            });
        }
        for package in &resp.needs_trigger {
            report(ScriptSessionEvent::TargetStopped {
                package: package.clone(),
            });
        }
        for package in &resp.not_installed {
            report(ScriptSessionEvent::TargetNotInstalled {
                package: package.clone(),
            });
        }
        for package in &resp.launch_failed {
            report(ScriptSessionEvent::LaunchFailed {
                package: package.clone(),
                message: "launcher could not be started after force-stop".into(),
            });
        }
        for (package, message) in &resp.failed {
            report(ScriptSessionEvent::RestartFailed {
                package: package.clone(),
                message: message.clone(),
            });
        }
        if !resp.failed.is_empty() {
            return Err(ScriptSessionError::RestartFailed {
                count: resp.failed.len(),
            });
        }
        Some(resp)
    };

    // The wait budget covers the observation phase only (deploy/restart are
    // acknowledged synchronously before this point).
    let deadline = Instant::now() + params.wait;
    let mut cursor: Option<i64> = None;
    let mut reported: HashSet<i64> = HashSet::new();
    let mut polls = 0u32;
    let mut success = false;
    loop {
        let log_params = CollectLogsParams {
            after_event_id: cursor,
            since_ms: None,
            limit: params.poll_limit,
            rule_id: None,
            script_id: Some(params.script_id.clone()),
            min_generation: None,
            exact_generation: Some(deployed.generation),
        };
        // Retries are bounded and only happen on read-only, cursor-keyed
        // `collect_logs` transport failures: the retried request carries the
        // same `after_event_id`, so no event can be duplicated, and `reported`
        // additionally guards against a reconnect race where the Agent echoes
        // an already-delivered entry.
        let collect = match timeout_at(deadline, async {
            let mut retries = 0u32;
            loop {
                match client.collect_logs(&log_params).await {
                    Ok(collected) => break Ok(collected),
                    Err(error)
                        if classify_retry(&error) == RetryClass::Retryable
                            && retries < params.collect_retries =>
                    {
                        retries += 1;
                        report(ScriptSessionEvent::PollRetry { attempt: retries });
                        sleep(Duration::from_millis(200 * u64::from(retries))).await;
                    }
                    Err(error) => break Err(error),
                }
            }
        })
        .await
        {
            Ok(Ok(collected)) => collected,
            Ok(Err(error)) => return Err(ScriptSessionError::Collect(error)),
            Err(_) => {
                report(ScriptSessionEvent::Timeout {
                    expected: params.expect,
                });
                return Err(ScriptSessionError::Timeout {
                    expected: params.expect.as_str(),
                });
            }
        };
        polls += 1;
        cursor = Some(collect.next_event_id.max(cursor.unwrap_or(0)));
        for entry in collect.entries {
            // Exact generation isolation: never process a stale/concurrent event.
            if entry.generation != Some(deployed.generation) {
                continue;
            }
            // Reconnect dedupe: an entry is reported at most once per session.
            if !reported.insert(entry.event_id) {
                continue;
            }
            report(ScriptSessionEvent::Entry(entry.clone()));
            if entry.event == "script_error" {
                report(ScriptSessionEvent::ScriptError {
                    message: entry.message.clone(),
                });
                return Err(ScriptSessionError::ScriptError(entry.message.clone()));
            }
            if params.expect.matches(&entry.event) {
                success = true;
                break;
            }
        }
        if success {
            break;
        }
        if Instant::now() >= deadline {
            break;
        }
        sleep(params.poll_interval).await;
    }

    if !success {
        report(ScriptSessionEvent::Timeout {
            expected: params.expect,
        });
        return Err(ScriptSessionError::Timeout {
            expected: params.expect.as_str(),
        });
    }

    Ok(ScriptSessionOutcome {
        generation: deployed.generation,
        deployed,
        restarted,
        expected: params.expect,
        request_id,
        success: true,
        polls,
    })
}
