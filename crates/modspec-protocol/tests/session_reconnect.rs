//! Hook-event continuation and retry-policy tests: transient transport
//! failures on the read-only `collect_logs` poll are retried with the cursor
//! preserved (no duplicate events, no lost events), while mutating calls
//! (deploy) and auth failures are NEVER retried automatically.

mod common;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use common::{hook_entry, ok_deploy, FakeAgent, FakeResponse};
use modspec_protocol::session::run_rule_session;
use modspec_protocol::{Expect, RuleSessionParams, SessionError, SessionEvent};
use serde_json::json;

const RULE_ID: &str = "test/smoke";
const PKG: &str = "com.example.target";

fn params() -> RuleSessionParams {
    RuleSessionParams {
        rule_id: RULE_ID.into(),
        content: "rule_version = \"1\"".into(),
        packages: vec![PKG.into()],
        wait: Duration::from_secs(5),
        expect: Expect::Loaded,
        no_restart: false,
        poll_limit: 50,
        poll_interval: Duration::from_millis(5),
        collect_retries: 3,
    }
}

async fn connect(agent: &FakeAgent) -> modspec_protocol::RpcClient {
    let mut client = modspec_protocol::RpcClient::new("127.0.0.1", agent.port, agent.port);
    client.connect().await.unwrap();
    client
}

fn collect_ok(entries: serde_json::Value, next_event_id: i64) -> FakeResponse {
    FakeResponse::Result(json!({
        "entries": entries,
        "next_event_id": next_event_id,
        "first_event_id": 1,
        "truncated": false,
        "source": "journal"
    }))
}

fn restart_ok() -> FakeResponse {
    FakeResponse::Result(json!({
        "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
    }))
}

#[tokio::test(flavor = "current_thread")]
async fn collect_transient_failure_is_retried_without_duplicates_and_cursor_continues() {
    let collect_calls = AtomicUsize::new(0);
    let agent = FakeAgent::start(move |captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(42)),
        "restart_targets" => restart_ok(),
        "collect_logs" => {
            let call = collect_calls.fetch_add(1, Ordering::SeqCst);
            match call {
                0 => FakeResponse::HttpStatus(500, "transient server failure".into()),
                1 => collect_ok(
                    json!([
                        hook_entry("hook_loaded", 42, 5, "a"),
                        hook_entry("hook_loaded", 42, 6, "b")
                    ]),
                    6,
                ),
                _ => collect_ok(json!([hook_entry("hook_hit", 42, 7, "c")]), 7),
            }
        }
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut session = params();
    session.expect = Expect::Hit; // `loaded` events don't terminate the session
    let mut events = Vec::new();
    let outcome = run_rule_session(&client, &session, |e| events.push(e))
        .await
        .unwrap();
    assert!(outcome.success);

    // Exactly one retry happened, and it is observable.
    let retries = events
        .iter()
        .filter(|e| matches!(e, SessionEvent::PollRetry { .. }))
        .count();
    assert_eq!(retries, 1);

    // Every delivered event is reported exactly once, in order, no duplicates.
    let entries: Vec<_> = events
        .iter()
        .filter_map(|e| match e {
            SessionEvent::Entry(entry) => Some(entry.event_id),
            _ => None,
        })
        .collect();
    assert_eq!(entries, vec![5, 6, 7]);

    // The retried poll carried the SAME cursor as the failed one (no
    // duplication), and the following poll advanced it.
    let captured = agent.captured();
    let collects: Vec<_> = captured
        .iter()
        .filter(|r| r.method == "collect_logs")
        .collect();
    assert_eq!(collects.len(), 3);
    assert!(collects[0].params["after_event_id"].is_null()); // failed poll
    assert!(collects[1].params["after_event_id"].is_null()); // retry: same cursor
    assert_eq!(collects[2].params["after_event_id"], 6); // advanced only after success
    assert_eq!(outcome.polls, 2);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn reconnect_after_recovery_does_not_re_report_delivered_entries() {
    // Simulates a flaky agent that echoes an already-delivered entry after the
    // PC reconnects: the session must not report event 5 twice.
    let collect_calls = AtomicUsize::new(0);
    let agent = FakeAgent::start(move |captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(42)),
        "restart_targets" => restart_ok(),
        "collect_logs" => {
            let call = collect_calls.fetch_add(1, Ordering::SeqCst);
            match call {
                0 => collect_ok(json!([hook_entry("hook_loaded", 42, 5, "a")]), 5),
                1 => FakeResponse::HttpStatus(500, "transient".into()),
                2 => collect_ok(
                    json!([
                        hook_entry("hook_loaded", 42, 5, "a"),
                        hook_entry("hook_loaded", 42, 6, "b")
                    ]),
                    6,
                ),
                _ => collect_ok(json!([hook_entry("hook_hit", 42, 7, "c")]), 7),
            }
        }
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut session = params();
    session.expect = Expect::Hit;
    let mut events = Vec::new();
    run_rule_session(&client, &session, |e| events.push(e))
        .await
        .unwrap();

    let entries: Vec<_> = events
        .iter()
        .filter_map(|e| match e {
            SessionEvent::Entry(entry) => Some(entry.event_id),
            _ => None,
        })
        .collect();
    assert_eq!(entries, vec![5, 6, 7]);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn deploy_transport_failure_is_never_retried() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::HttpStatus(500, "agent broke mid-deploy".into()),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let err = run_rule_session(&client, &params(), |_| {})
        .await
        .unwrap_err();
    assert!(matches!(err, SessionError::Deploy(_)), "got {err:?}");

    // Exactly one deploy attempt: a duplicate deploy could double-apply scope.
    let captured = agent.captured();
    assert_eq!(
        captured
            .iter()
            .filter(|r| r.method == "deploy_rule")
            .count(),
        1
    );
    assert!(captured.iter().all(|r| r.method == "deploy_rule"));
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn unauthorized_collect_failure_is_not_retried() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(42)),
        "restart_targets" => restart_ok(),
        "collect_logs" => FakeResponse::HttpStatus(401, "token rotated".into()),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let err = run_rule_session(&client, &params(), |_| {})
        .await
        .unwrap_err();
    assert!(matches!(err, SessionError::Collect(_)), "got {err:?}");
    assert_eq!(agent.captured_method("collect_logs").len(), 1);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn retries_are_bounded_and_exhausted_without_success() {
    // Every collect poll fails with a transport error: the session must retry
    // at most `collect_retries` times per poll, then fail explicitly instead
    // of looping forever.
    let collect_calls = std::sync::Arc::new(AtomicUsize::new(0));
    let counter = std::sync::Arc::clone(&collect_calls);
    let agent = FakeAgent::start(move |captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(42)),
        "restart_targets" => restart_ok(),
        "collect_logs" => {
            counter.fetch_add(1, Ordering::SeqCst);
            FakeResponse::HttpStatus(500, "always failing".into())
        }
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut session = params();
    session.collect_retries = 2;
    let start = std::time::Instant::now();
    let err = run_rule_session(&client, &session, |_| {})
        .await
        .unwrap_err();
    assert!(
        std::time::Instant::now().duration_since(start) < Duration::from_secs(5),
        "bounded retries must not hang"
    );
    assert!(matches!(err, SessionError::Collect(_)), "got {err:?}");
    // First poll failed: 1 attempt + 2 retries = 3 collect calls, no more.
    assert_eq!(collect_calls.load(Ordering::SeqCst), 3);
    agent.join();
}
