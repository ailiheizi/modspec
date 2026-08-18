//! Connection-manager tests against loopback fakes: stale health preflight,
//! forward repair, bootstrap, unauthorized detection, and retry
//! classification — no Android device required.

mod common;

use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use std::time::Duration;

use common::{FakeAgent, FakeForwarder, FakeHealthServer, FakeResponse};
use modspec_protocol::connection::{
    ensure_connection, health_preflight, ConnectionIssue, ConnectionOptions, PreflightFailure,
};
use modspec_protocol::RpcClient;

const HTTP_PORT: u16 = 8764;

fn short_options() -> ConnectionOptions {
    ConnectionOptions {
        preflight_timeout: Duration::from_millis(200),
        bootstrap_wait: Duration::from_secs(2),
        forward_local_port: 9876,
        forward_remote_port: HTTP_PORT,
        ..Default::default()
    }
}

#[tokio::test(flavor = "current_thread")]
async fn healthy_preflight_reports_healthy_with_latency() {
    let health = FakeHealthServer::start(false);
    let forwarder = FakeForwarder::new(&health);
    let status = ensure_connection("127.0.0.1", health.port, None, None, &short_options()).await;
    assert_eq!(status.issue, ConnectionIssue::Healthy);
    assert!(status.preflight_ok);
    assert!(status.latency_ms.is_some());
    assert!(!status.forward_rebuilt);
    assert!(!status.bootstrapped);
    assert!(forwarder.log().is_empty());
}

#[tokio::test(flavor = "current_thread")]
async fn stale_forward_is_classified_and_repaired() {
    // Hanging server = stale forward whose local port accepts but never answers.
    let health = FakeHealthServer::start(true);
    let forwarder = FakeForwarder::new(&health);
    let mut opts = short_options();
    opts.repair_retries = 1;

    let status = ensure_connection("127.0.0.1", health.port, None, Some(&forwarder), &opts).await;

    // ensure_forward heals the server → connection recovered without bootstrap.
    assert_eq!(status.issue, ConnectionIssue::Healthy);
    assert!(status.forward_rebuilt);
    assert!(!status.bootstrapped);
    assert_eq!(forwarder.log(), vec!["ensure_forward"]);
    assert!(!health.is_hanging());
}

#[tokio::test(flavor = "current_thread")]
async fn bootstrap_recovers_connection_when_forward_cannot() {
    let hang = Arc::new(AtomicBool::new(true));
    let server = FakeHealthServer::start_with_flag(Arc::clone(&hang));
    let forwarder = FakeForwarder::new_shared(&hang);
    // ensure_forward does not heal; bootstrap does.
    forwarder.set_heal_on_forward(false);

    let mut opts = short_options();
    opts.repair_retries = 0;

    let status = ensure_connection("127.0.0.1", server.port, None, Some(&forwarder), &opts).await;

    assert_eq!(status.issue, ConnectionIssue::Healthy);
    assert!(status.forward_rebuilt);
    assert!(status.bootstrapped);
    assert_eq!(forwarder.log(), vec!["ensure_forward", "bootstrap"]);
}

#[tokio::test(flavor = "current_thread")]
async fn repair_exhausted_without_bootstrap_stays_stale_forward() {
    let hang = Arc::new(AtomicBool::new(true));
    let server = FakeHealthServer::start_with_flag(Arc::clone(&hang));
    let forwarder = FakeForwarder::new_shared(&hang);
    forwarder.set_heal_on_forward(false);
    forwarder.set_fail_bootstrap(true);

    let mut opts = short_options();
    opts.repair_retries = 1;

    let status = ensure_connection("127.0.0.1", server.port, None, Some(&forwarder), &opts).await;

    assert_eq!(status.issue, ConnectionIssue::StaleForward);
    assert!(status.forward_rebuilt);
    assert!(!status.bootstrapped); // the bootstrap attempt itself failed
    assert!(status.detail.contains("bootstrap failed"));
    assert_eq!(
        forwarder.log(),
        vec!["ensure_forward", "ensure_forward", "bootstrap"]
    );
}

#[tokio::test(flavor = "current_thread")]
async fn non_loopback_host_is_agent_unreachable_without_forward_repair() {
    let health = FakeHealthServer::start(true);
    let mut opts = short_options();
    opts.bootstrap = false;
    let status = ensure_connection("192.168.1.42", health.port, None, None, &opts).await;
    assert_eq!(status.issue, ConnectionIssue::AgentUnreachable);
    assert!(!status.forward_rebuilt);
    assert!(!status.bootstrapped);
}

#[tokio::test(flavor = "current_thread")]
async fn loopback_without_forwarder_stays_stale_forward() {
    let health = FakeHealthServer::start(true);
    let mut opts = short_options();
    opts.bootstrap = false;
    let status = ensure_connection("127.0.0.1", health.port, None, None, &opts).await;
    assert_eq!(status.issue, ConnectionIssue::StaleForward);
}

#[tokio::test(flavor = "current_thread")]
async fn unauthorized_token_is_classified() {
    let health = FakeHealthServer::start(false);
    let agent = FakeAgent::start(|_, _| FakeResponse::HttpStatus(401, "bad token".into()));
    let mut client =
        RpcClient::new("127.0.0.1", agent.port, agent.port).with_auth_token(Some("expired".into()));
    client.connect().await.unwrap();

    let mut opts = short_options();
    opts.bootstrap = false;
    let status = ensure_connection("127.0.0.1", health.port, Some(&client), None, &opts).await;
    assert_eq!(status.issue, ConnectionIssue::Unauthorized);
    assert!(status.preflight_ok);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn unauthorized_is_classified_even_after_forward_repair() {
    let hang = Arc::new(AtomicBool::new(true));
    let server = FakeHealthServer::start_with_flag(Arc::clone(&hang));
    let forwarder = FakeForwarder::new_shared(&hang);
    let agent = FakeAgent::start(|_, _| FakeResponse::HttpStatus(401, "bad token".into()));
    let mut client =
        RpcClient::new("127.0.0.1", agent.port, agent.port).with_auth_token(Some("expired".into()));
    client.connect().await.unwrap();

    let mut opts = short_options();
    opts.repair_retries = 1;

    let status = ensure_connection(
        "127.0.0.1",
        server.port,
        Some(&client),
        Some(&forwarder),
        &opts,
    )
    .await;

    assert_eq!(status.issue, ConnectionIssue::Unauthorized);
    assert!(status.forward_rebuilt);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn preflight_timeout_vs_refused_is_distinguished() {
    // Hanging server → Timeout.
    let hang = Arc::new(AtomicBool::new(true));
    let server = FakeHealthServer::start_with_flag(Arc::clone(&hang));
    let result = health_preflight("127.0.0.1", server.port, Duration::from_millis(150)).await;
    assert_eq!(result.unwrap_err(), PreflightFailure::Timeout);

    // Closed port → Refused.
    let closed = FakeHealthServer::start(false);
    let port = closed.port;
    drop(closed);
    let result = health_preflight("127.0.0.1", port, Duration::from_millis(500)).await;
    assert_eq!(result.unwrap_err(), PreflightFailure::Refused);
}

#[tokio::test(flavor = "current_thread")]
async fn forward_rebuild_failure_reports_adb_hint_and_no_bootstrap() {
    let hang = Arc::new(AtomicBool::new(true));
    let server = FakeHealthServer::start_with_flag(Arc::clone(&hang));
    let forwarder = FakeForwarder::new_shared(&hang);
    forwarder.set_fail_forward(true);

    let mut opts = short_options();
    opts.repair_retries = 1;
    opts.bootstrap = false; // isolate the forward-repair path

    let status = ensure_connection("127.0.0.1", server.port, None, Some(&forwarder), &opts).await;
    assert_eq!(status.issue, ConnectionIssue::StaleForward);
    assert!(!status.forward_rebuilt);
    assert!(!status.bootstrapped);
    assert!(status.detail.contains("adb forward failed"));
    assert!(status.detail.contains("adb devices: R58M2ABCD=device"));
    assert_eq!(forwarder.log(), vec!["ensure_forward"]);
}
