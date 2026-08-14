//! PC-first interactive rule session orchestration.
//!
//! The [`run_rule_session`] function drives a single-rule debug session against
//! an already-connected [`RpcClient`]: deploy → ensure scope → restart targets →
//! poll structured hook events until the expected outcome or the deadline.
//! It is transport-agnostic and unit-tested against a loopback fake Agent
//! (see `modspec-protocol/tests/`); the CLI command is a thin adapter that owns
//! printing and Ctrl-C handling.

use std::collections::HashSet;
use std::time::Duration;

use thiserror::Error;
use tokio::time::{sleep, timeout_at, Instant};

use crate::connection::{classify_retry, RetryClass};
use crate::{
    CollectLogsParams, DeployRuleParams, DeployRuleResponse, HookLogEntry, RestartTargetsParams,
    RestartTargetsResponse, RpcClient, RpcClientError,
};

/// What counts as session success.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Expect {
    /// `hook_loaded` (or a `hook_hit`, which implies a loaded hook).
    Loaded,
    /// An actual `hook_hit`.
    Hit,
}

impl Expect {
    pub fn as_str(self) -> &'static str {
        match self {
            Expect::Loaded => "loaded",
            Expect::Hit => "hit",
        }
    }

    fn matches(&self, event: &str) -> bool {
        match self {
            Expect::Loaded => matches!(event, "hook_loaded" | "hook_hit"),
            Expect::Hit => event == "hook_hit",
        }
    }
}

/// Configuration for one PC rule session.
#[derive(Debug, Clone)]
pub struct RuleSessionParams {
    pub rule_id: String,
    pub content: String,
    pub packages: Vec<String>,
    pub wait: Duration,
    pub expect: Expect,
    pub no_restart: bool,
    /// Max entries per `collect_logs` poll.
    pub poll_limit: u32,
    /// Delay between polls (may be zero in tests).
    pub poll_interval: Duration,
    /// Bounded automatic retries for transient transport failures on the
    /// read-only `collect_logs` poll. Deploy/restart are NEVER retried: a
    /// mutating failure is an explicit, recoverable error instead.
    pub collect_retries: u32,
}

impl Default for RuleSessionParams {
    fn default() -> Self {
        Self {
            rule_id: String::new(),
            content: String::new(),
            packages: Vec::new(),
            wait: Duration::from_secs(15),
            expect: Expect::Loaded,
            no_restart: false,
            poll_limit: 200,
            poll_interval: Duration::from_millis(500),
            collect_retries: 3,
        }
    }
}

/// Structured events surfaced by [`run_rule_session`] to a reporter.
#[derive(Debug, Clone)]
pub enum SessionEvent {
    RuleUploaded {
        generation: i64,
        publish_mode: String,
        scope_status: String,
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
    /// A structured hook event matching the session's exact generation.
    Entry(HookLogEntry),
    /// A transient transport failure was retried on the read-only poll; the
    /// event cursor is preserved, so nothing is duplicated or lost.
    PollRetry {
        attempt: u32,
    },
    Timeout {
        expected: Expect,
    },
}

/// Outcome of a finished session.
#[derive(Debug, Clone)]
pub struct SessionOutcome {
    pub deployed: DeployRuleResponse,
    pub restarted: Option<RestartTargetsResponse>,
    pub generation: i64,
    pub expected: Expect,
    /// True when the expected event was observed before the deadline.
    pub success: bool,
    /// Number of `collect_logs` polls issued.
    pub polls: u32,
}

#[derive(Debug, Error)]
pub enum SessionError {
    #[error("{0}")]
    Invalid(String),
    #[error("deploy_rule RPC failed: {0}")]
    Deploy(RpcClientError),
    #[error("rule upload was not committed: {0}")]
    NotCommitted(String),
    #[error(
        "Agent published via {0}; PC debug sessions require a bound XposedService/RemoteFile channel"
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
    #[error("agent reported hook_error: {0}")]
    HookError(String),
    #[error("session timed out waiting for hook_{expected}; rule is deployed, so start/trigger the target and retry")]
    Timeout { expected: &'static str },
}

pub type SessionResult<T> = Result<T, SessionError>;

/// Deploy one rule, optionally restart its targets, and poll structured events.
///
/// Preconditions (mirrored from the CLI for testability):
/// - the rule must declare at least one compatible package (unscoped sessions are refused);
/// - when restarting, `system`/`android` targets are refused.
///
/// `report` receives every [`SessionEvent`] in order (including the terminal
/// `Timeout`). The returned [`SessionOutcome`] describes the result. Errors
/// carry a machine-checkable reason; the CLI renders them for humans.
pub async fn run_rule_session<F>(
    client: &RpcClient,
    params: &RuleSessionParams,
    mut report: F,
) -> SessionResult<SessionOutcome>
where
    F: FnMut(SessionEvent),
{
    if params.packages.is_empty() {
        return Err(SessionError::Invalid(
            "rule has no compatible.packages; refusing an unscoped debug session".into(),
        ));
    }
    if !params.no_restart
        && params
            .packages
            .iter()
            .any(|p| matches!(p.as_str(), "system" | "android"))
    {
        return Err(SessionError::Invalid(
            "system-server rules cannot be force-stopped; rerun with --no-restart and reload the framework explicitly"
                .into(),
        ));
    }

    let deployed = client
        .deploy_rule(&DeployRuleParams {
            rule_id: params.rule_id.clone(),
            content: params.content.clone(),
            packages: params.packages.clone(),
            ensure_scope: true,
        })
        .await
        .map_err(SessionError::Deploy)?;
    report(SessionEvent::RuleUploaded {
        generation: deployed.generation,
        publish_mode: deployed.publish_mode.clone(),
        scope_status: deployed.scope_status.clone(),
        message: deployed.message.clone(),
    });
    if !deployed.stored {
        return Err(SessionError::NotCommitted(deployed.message.clone()));
    }
    if deployed.publish_mode != "remote_file" {
        return Err(SessionError::PublishMode(deployed.publish_mode.clone()));
    }
    if !matches!(deployed.scope_status.as_str(), "applied" | "already") {
        return Err(SessionError::ScopeNotEnsured(
            deployed.scope_status.clone(),
            deployed.message.clone(),
        ));
    }

    let restarted = if params.no_restart {
        report(SessionEvent::DeployOnly {
            generation: deployed.generation,
        });
        None
    } else {
        report(SessionEvent::ReloadStarted {
            targets: params.packages.clone(),
        });
        let resp = client
            .restart_targets(&RestartTargetsParams {
                packages: params.packages.clone(),
            })
            .await
            .map_err(SessionError::RestartRpc)?;
        for package in &resp.restarted {
            report(SessionEvent::TargetRestarted {
                package: package.clone(),
            });
        }
        for package in &resp.needs_trigger {
            report(SessionEvent::TargetStopped {
                package: package.clone(),
            });
        }
        for package in &resp.not_installed {
            report(SessionEvent::TargetNotInstalled {
                package: package.clone(),
            });
        }
        for package in &resp.launch_failed {
            report(SessionEvent::LaunchFailed {
                package: package.clone(),
                message: "launcher could not be started after force-stop".into(),
            });
        }
        for (package, message) in &resp.failed {
            report(SessionEvent::RestartFailed {
                package: package.clone(),
                message: message.clone(),
            });
        }
        if !resp.failed.is_empty() {
            return Err(SessionError::RestartFailed {
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
            rule_id: Some(params.rule_id.clone()),
            script_id: None,
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
                        report(SessionEvent::PollRetry { attempt: retries });
                        sleep(Duration::from_millis(200 * u64::from(retries))).await;
                    }
                    Err(error) => break Err(error),
                }
            }
        })
        .await
        {
            Ok(Ok(collected)) => collected,
            Ok(Err(error)) => return Err(SessionError::Collect(error)),
            Err(_) => {
                report(SessionEvent::Timeout {
                    expected: params.expect,
                });
                return Err(SessionError::Timeout {
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
            report(SessionEvent::Entry(entry.clone()));
            if entry.event == "hook_error" {
                return Err(SessionError::HookError(entry.message.clone()));
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
        report(SessionEvent::Timeout {
            expected: params.expect,
        });
        return Err(SessionError::Timeout {
            expected: params.expect.as_str(),
        });
    }

    Ok(SessionOutcome {
        generation: deployed.generation,
        deployed,
        restarted,
        expected: params.expect,
        success: true,
        polls,
    })
}
