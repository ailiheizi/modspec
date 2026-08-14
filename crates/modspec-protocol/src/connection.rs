//! PC-side connection manager: short health preflight, failure classification,
//! ADB forward repair and (optional) Agent bootstrap.
//!
//! Raw ADB stays an implementation detail: the CLI injects a [`ForwardManager`]
//! backed by `modspec-adb`, tests inject fakes, and everything else in here is
//! pure logic exercised against loopback servers (see
//! `modspec-protocol/tests/connection.rs`).
//!
//! Failure model (observed on real devices): a stale `adb forward` keeps the
//! local port LISTENing while the Agent's HTTP server is dead, so a plain RPC
//! hangs for the full client timeout. The two-sided fix is:
//! 1. the Agent now supervises its servers (watchdog + restart on every start
//!    command) so the accept loop self-heals without a process restart;
//! 2. [`ensure_connection`] bounds every probe with a short timeout, classifies
//!    the failure, rebuilds the forward for an explicitly selected/paired
//!    authorized device, and (optionally) bootstraps the Agent's exported
//!    MainActivity when it is still down.

use std::time::{Duration, Instant};

use crate::{
    methods, transport::call_http_with_timeout, JsonRpcRequest, Result, RpcClient, RpcClientError,
    DEFAULT_HTTP_PORT,
};

pub const DEFAULT_PREFLIGHT_TIMEOUT: Duration = Duration::from_secs(3);
pub const DEFAULT_REPAIR_RETRIES: u32 = 2;
pub const DEFAULT_BOOTSTRAP_WAIT: Duration = Duration::from_secs(8);

/// Why a connection attempt ended up in its state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionIssue {
    Healthy,
    /// Loopback host unreachable — the `adb forward` is stale or missing, or
    /// the device is unplugged/offline.
    StaleForward,
    /// The Agent process is not reachable and could not be repaired.
    AgentUnreachable,
    /// The Agent is reachable but rejected our bearer token (re-pair needed).
    Unauthorized,
}

impl ConnectionIssue {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Healthy => "healthy",
            Self::StaleForward => "stale_forward",
            Self::AgentUnreachable => "agent_unreachable",
            Self::Unauthorized => "unauthorized",
        }
    }
}

/// Whether an RPC failure may be retried automatically.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RetryClass {
    /// Transient transport failures (refused/reset/timeout) on idempotent calls.
    Retryable,
    /// Everything else (auth, RPC-level errors, local offline mode).
    NotRetryable,
}

/// Classify a client error for automatic retry. Only transport failures are
/// retryable — RPC errors, auth failures and offline mode are not.
pub fn classify_retry(error: &RpcClientError) -> RetryClass {
    match error {
        RpcClientError::Transport(_) => RetryClass::Retryable,
        _ => RetryClass::NotRetryable,
    }
}

/// Read-only RPC methods. Only these may be retried automatically; mutating
/// methods (deploy/apply/restart/trigger) must fail explicitly instead of
/// risking a duplicate side effect.
pub fn is_read_only(method: &str) -> bool {
    matches!(
        method,
        methods::PING
            | methods::GET_STATUS
            | methods::INSPECT_DEVICE
            | methods::APP_LIST
            | methods::APP_INFO
            | methods::PROCESS_LIST
            | methods::GET_LOGS
            | methods::MODULE_DIAGNOSTICS
            | methods::COLLECT_LOGS
            | methods::SCRIPT_LIST
            | methods::SCRIPT_VALIDATE
            | methods::VERIFY
    )
}

/// Whether a failed call may be retried automatically: read-only method AND
/// retryable error class.
pub fn should_retry(method: &str, error: &RpcClientError) -> bool {
    is_read_only(method) && classify_retry(error) == RetryClass::Retryable
}

/// The ADB-forward topology binds `127.0.0.1` on the PC; anything else is a
/// direct LAN connection that cannot be repaired by rebuilding a forward.
pub fn is_loopback_host(host: &str) -> bool {
    matches!(host, "127.0.0.1" | "localhost" | "::1")
}

/// Why a preflight probe failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PreflightFailure {
    /// The probe hit its deadline — the classic symptom of a stale `adb
    /// forward` whose local port still accepts but never completes.
    Timeout,
    /// The connection was refused/reset — nothing is listening.
    Refused,
    /// Non-2xx answer or another transport error.
    Other,
}

/// Short, bounded `GET /health` probe. Returns the round-trip latency on
/// success, or a [`PreflightFailure`] classification otherwise.
pub async fn health_preflight(
    host: &str,
    http_port: u16,
    timeout: Duration,
) -> std::result::Result<Duration, PreflightFailure> {
    let url = format!("http://{host}:{http_port}/health");
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(timeout)
        .build()
        .map_err(|_| PreflightFailure::Other)?;
    let start = Instant::now();
    let response = client.get(&url).send().await.map_err(|error| {
        if error.is_timeout() {
            PreflightFailure::Timeout
        } else {
            PreflightFailure::Refused
        }
    })?;
    if response.status().is_success() {
        Ok(start.elapsed())
    } else {
        Err(PreflightFailure::Other)
    }
}

/// Authorized `ping` RPC with a short timeout — detects stale/rotated bearer
/// tokens and distinguishes "reachable but unauthenticated" from "down".
pub async fn authorized_probe(client: &RpcClient, timeout: Duration) -> Result<()> {
    let request = JsonRpcRequest::new(methods::PING, serde_json::Value::Null);
    let result =
        call_http_with_timeout(client.http_url(), client.auth_token(), &request, timeout).await?;
    serde_json::from_value::<serde_json::Value>(result)?;
    Ok(())
}

/// PC-side ADB forward/bootstrapping boundary. The CLI implements it with
/// `modspec-adb`; tests inject a fake.
pub trait ForwardManager: Send + Sync {
    /// Human-readable description of the managed forward (e.g. the adb serial).
    fn describe(&self) -> String;

    /// Rebuild `adb forward tcp:<local> tcp:<remote>` idempotently.
    fn ensure_forward(&self, local: u16, remote: u16) -> std::result::Result<(), String>;

    /// Relaunch the Agent's exported MainActivity (which restarts AgentService
    /// and its supervised servers). Optional; may be a no-op.
    fn bootstrap(&self) -> std::result::Result<(), String>;

    /// Best-effort adb state hint for diagnostics (devices online/offline).
    fn adb_hint(&self) -> Option<String> {
        None
    }
}

/// Behavior knobs for [`ensure_connection`].
#[derive(Debug, Clone)]
pub struct ConnectionOptions {
    pub preflight_timeout: Duration,
    /// Forward-rebuild + re-probe cycles before giving up (and before bootstrap).
    pub repair_retries: u32,
    /// Whether to launch the Agent's MainActivity when it stays unreachable.
    pub bootstrap: bool,
    /// How long to keep probing after a bootstrap attempt.
    pub bootstrap_wait: Duration,
    pub forward_local_port: u16,
    pub forward_remote_port: u16,
}

impl Default for ConnectionOptions {
    fn default() -> Self {
        Self {
            preflight_timeout: DEFAULT_PREFLIGHT_TIMEOUT,
            repair_retries: DEFAULT_REPAIR_RETRIES,
            bootstrap: true,
            bootstrap_wait: DEFAULT_BOOTSTRAP_WAIT,
            forward_local_port: 9876,
            forward_remote_port: DEFAULT_HTTP_PORT,
        }
    }
}

/// Outcome of one [`ensure_connection`] attempt.
#[derive(Debug, Clone)]
pub struct ConnectionStatus {
    pub issue: ConnectionIssue,
    pub preflight_ok: bool,
    pub latency_ms: Option<u64>,
    pub forward_rebuilt: bool,
    pub bootstrapped: bool,
    pub detail: String,
}

impl ConnectionStatus {
    pub fn is_healthy(&self) -> bool {
        self.issue == ConnectionIssue::Healthy
    }
}

/// Drive a connection attempt: preflight → classify → (repair forward →
/// re-probe → bootstrap → re-probe). Never fails — every outcome is
/// classified so callers can render or exit accordingly.
///
/// `auth` is the authenticated client used for the authorized probe (pass
/// `None` for token-less devices, which only get health semantics).
pub async fn ensure_connection(
    host: &str,
    http_port: u16,
    auth: Option<&RpcClient>,
    forwarder: Option<&dyn ForwardManager>,
    opts: &ConnectionOptions,
) -> ConnectionStatus {
    let mut status = ConnectionStatus {
        issue: ConnectionIssue::AgentUnreachable,
        preflight_ok: false,
        latency_ms: None,
        forward_rebuilt: false,
        bootstrapped: false,
        detail: String::new(),
    };

    // 1. Plain health preflight.
    match health_preflight(host, http_port, opts.preflight_timeout).await {
        Ok(latency) => {
            status.preflight_ok = true;
            status.latency_ms = Some(latency.as_millis() as u64);
            // 2. Authorized probe to detect stale/rotated tokens.
            let Some(client) = auth else {
                return finish_healthy(status, "agent healthy");
            };
            return match authorized_probe(client, opts.preflight_timeout).await {
                Ok(()) => finish_healthy(status, "agent healthy"),
                Err(RpcClientError::Unauthorized { message }) => {
                    status.issue = ConnectionIssue::Unauthorized;
                    status.detail = message;
                    status
                }
                Err(error) if classify_retry(&error) == RetryClass::Retryable => {
                    // Health ok but the RPC path is flaky — likely a
                    // mid-restart Agent. Wait briefly and re-probe (bounded);
                    // do NOT touch the forward, which already works.
                    for attempt in 1..=opts.repair_retries {
                        tokio::time::sleep(Duration::from_millis(500) * attempt).await;
                        match authorized_probe(client, opts.preflight_timeout).await {
                            Ok(()) => return finish_healthy(status, "agent recovered"),
                            Err(other) if classify_retry(&other) == RetryClass::Retryable => {
                                continue;
                            }
                            Err(RpcClientError::Unauthorized { message }) => {
                                status.issue = ConnectionIssue::Unauthorized;
                                status.detail = message;
                                return status;
                            }
                            Err(other) => {
                                status.issue = ConnectionIssue::AgentUnreachable;
                                status.detail = format!("health ok but RPC probe failed: {other}");
                                return status;
                            }
                        }
                    }
                    status.issue = ConnectionIssue::AgentUnreachable;
                    status.detail = "health ok but RPC probe kept failing; restart the ModSpec Agent on the device".into();
                    status
                }
                Err(error) => {
                    status.issue = ConnectionIssue::AgentUnreachable;
                    status.detail = format!("health ok but RPC probe failed: {error}");
                    status
                }
            };
        }
        Err(failure) => {
            status.issue = if is_loopback_host(host) {
                ConnectionIssue::StaleForward
            } else {
                ConnectionIssue::AgentUnreachable
            };
            status.detail = format!("health preflight failed: {failure:?}");
        }
    }

    // 3. Repair the ADB forward (loopback only) and re-probe.
    let Some(forwarder) = forwarder else {
        return status;
    };
    status.detail = format!("forwarder={}", forwarder.describe());
    for attempt in 0..=opts.repair_retries {
        if let Err(error) =
            forwarder.ensure_forward(opts.forward_local_port, opts.forward_remote_port)
        {
            status.detail = format!(
                "forward rebuild failed ({error}); {}",
                forwarder.adb_hint().unwrap_or_default()
            );
            break;
        }
        status.forward_rebuilt = true;
        match health_preflight(host, http_port, opts.preflight_timeout).await {
            Ok(latency) => {
                status.preflight_ok = true;
                status.latency_ms = Some(latency.as_millis() as u64);
                if let Some(client) = auth {
                    match authorized_probe(client, opts.preflight_timeout).await {
                        Ok(()) => return finish_healthy(status, "forward rebuilt"),
                        Err(RpcClientError::Unauthorized { message }) => {
                            status.issue = ConnectionIssue::Unauthorized;
                            status.detail = message;
                            return status;
                        }
                        Err(error) => {
                            status.detail = format!("health ok but RPC probe failed: {error}");
                            continue;
                        }
                    }
                }
                return finish_healthy(status, "forward rebuilt");
            }
            Err(failure) => {
                status.detail = format!("re-probe after forward rebuild failed: {failure:?}");
                if attempt < opts.repair_retries {
                    tokio::time::sleep(Duration::from_millis(300)).await;
                }
            }
        }
    }

    // 4. Optional bootstrap: relaunch the Agent's MainActivity, then keep
    // probing until it comes up or the budget is spent.
    if opts.bootstrap {
        match forwarder.bootstrap() {
            Ok(()) => {
                status.bootstrapped = true;
                let deadline = Instant::now() + opts.bootstrap_wait;
                loop {
                    if let Ok(latency) =
                        health_preflight(host, http_port, opts.preflight_timeout).await
                    {
                        status.preflight_ok = true;
                        status.latency_ms = Some(latency.as_millis() as u64);
                        if let Some(client) = auth {
                            match authorized_probe(client, opts.preflight_timeout).await {
                                Ok(()) => return finish_healthy(status, "agent bootstrapped"),
                                Err(RpcClientError::Unauthorized { message }) => {
                                    status.issue = ConnectionIssue::Unauthorized;
                                    status.detail = message;
                                    return status;
                                }
                                Err(_) => {}
                            }
                        } else {
                            return finish_healthy(status, "agent bootstrapped");
                        }
                    }
                    if Instant::now() >= deadline {
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(500)).await;
                }
                status.issue = if is_loopback_host(host) {
                    ConnectionIssue::StaleForward
                } else {
                    ConnectionIssue::AgentUnreachable
                };
                status.detail = format!(
                    "agent bootstrapped but never became healthy within {}s",
                    opts.bootstrap_wait.as_secs()
                );
            }
            Err(error) => {
                status.detail = format!("bootstrap failed: {error}");
            }
        }
    }
    status
}

fn finish_healthy(mut status: ConnectionStatus, detail: &str) -> ConnectionStatus {
    status.issue = ConnectionIssue::Healthy;
    status.preflight_ok = true;
    status.detail = detail.to_string();
    status
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RpcClientError;

    #[test]
    fn retry_classification_only_retries_transport_errors() {
        let transport = RpcClientError::Transport("connection reset".into());
        assert_eq!(classify_retry(&transport), RetryClass::Retryable);
        assert_eq!(
            classify_retry(&RpcClientError::Unauthorized {
                message: "401".into()
            }),
            RetryClass::NotRetryable
        );
        assert_eq!(
            classify_retry(&RpcClientError::Rpc {
                code: -32603,
                message: "boom".into()
            }),
            RetryClass::NotRetryable
        );
        assert_eq!(
            classify_retry(&RpcClientError::Offline {
                method: "ping".into()
            }),
            RetryClass::NotRetryable
        );
        assert_eq!(
            classify_retry(&RpcClientError::NotConnected {
                url: "http://x".into()
            }),
            RetryClass::NotRetryable
        );
        assert_eq!(
            classify_retry(&RpcClientError::Decode(
                serde_json::from_str::<serde_json::Value>("").unwrap_err()
            )),
            RetryClass::NotRetryable
        );
    }

    #[test]
    fn should_retry_combines_method_safety_and_error_class() {
        let transport = RpcClientError::Transport("timeout".into());
        let unauthorized = RpcClientError::Unauthorized {
            message: "401".into(),
        };
        assert!(should_retry(methods::COLLECT_LOGS, &transport));
        assert!(should_retry(methods::GET_STATUS, &transport));
        assert!(should_retry(methods::PING, &transport));
        assert!(should_retry(methods::VERIFY, &transport));
        // Mutating methods are never retried automatically.
        assert!(!should_retry(methods::DEPLOY_RULE, &transport));
        assert!(!should_retry(methods::APPLY_PROFILE, &transport));
        assert!(!should_retry(methods::RESTART_TARGETS, &transport));
        assert!(!should_retry(methods::TRIGGER_APP, &transport));
        assert!(!should_retry(methods::SOFT_RESTART, &transport));
        assert!(!should_retry(methods::REAPPLY, &transport));
        // Auth failures are never retried, even on read-only methods.
        assert!(!should_retry(methods::COLLECT_LOGS, &unauthorized));
        assert!(!should_retry(methods::GET_STATUS, &unauthorized));
    }

    #[test]
    fn loopback_detection() {
        assert!(is_loopback_host("127.0.0.1"));
        assert!(is_loopback_host("localhost"));
        assert!(is_loopback_host("::1"));
        assert!(!is_loopback_host("192.168.1.10"));
        assert!(!is_loopback_host("10.0.0.5"));
    }

    #[test]
    fn issue_labels() {
        assert_eq!(ConnectionIssue::Healthy.as_str(), "healthy");
        assert_eq!(ConnectionIssue::StaleForward.as_str(), "stale_forward");
        assert_eq!(
            ConnectionIssue::AgentUnreachable.as_str(),
            "agent_unreachable"
        );
        assert_eq!(ConnectionIssue::Unauthorized.as_str(), "unauthorized");
    }
}
