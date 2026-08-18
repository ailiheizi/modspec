//! End-to-end tests of the PC rule session orchestration against a loopback
//! fake Agent. Covers success (loaded/hit), hook_error, restart failure,
//! needs_trigger, exact generation isolation, timeout/deadline, and request
//! parameter correctness — all without an Android device.

mod common;

use std::time::Duration;

use common::{hook_entry, ok_deploy, CapturedRequest, FakeAgent, FakeResponse};
use modspec_protocol::session::run_rule_session;
use modspec_protocol::RpcClient;
use modspec_protocol::{Expect, RuleSessionParams, SessionError, SessionEvent};
use serde_json::json;

const RULE_ID: &str = "test/smoke";
const PKG: &str = "com.example.target";

fn params(expect: Expect) -> RuleSessionParams {
    RuleSessionParams {
        rule_id: RULE_ID.into(),
        content: "rule_version = \"1\"".into(),
        packages: vec![PKG.into()],
        wait: Duration::from_secs(5),
        expect,
        no_restart: false,
        poll_limit: 50,
        poll_interval: Duration::from_millis(5),
        collect_retries: 3,
    }
}

async fn connect(agent: &FakeAgent) -> RpcClient {
    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port);
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

#[tokio::test(flavor = "current_thread")]
async fn success_when_hook_loaded_observed() {
    let collect_calls = std::sync::atomic::AtomicUsize::new(0);
    let agent = FakeAgent::start(move |captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(42)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        "collect_logs" => {
            let call = collect_calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            if call == 0 {
                collect_ok(json!([]), 0)
            } else {
                collect_ok(json!([hook_entry("hook_loaded", 42, 5, "loaded")]), 5)
            }
        }
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut events = Vec::new();
    let outcome = run_rule_session(&client, &params(Expect::Loaded), |e| events.push(e))
        .await
        .unwrap();
    assert!(outcome.success);
    assert_eq!(outcome.generation, 42);
    assert_eq!(outcome.polls, 2);
    assert!(events
        .iter()
        .any(|e| matches!(e, SessionEvent::TargetRestarted { package } if package == PKG)));
    assert!(events.iter().any(|e| matches!(e, SessionEvent::Entry(_))));
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn success_when_hook_hit_observed_with_expect_hit() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(7)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        "collect_logs" => collect_ok(json!([hook_entry("hook_hit", 7, 9, "hit!")]), 9),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let outcome = run_rule_session(&client, &params(Expect::Hit), |_| {})
        .await
        .unwrap();
    assert!(outcome.success);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn hook_error_aborts_session_with_structured_event() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(11)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        "collect_logs" => collect_ok(
            json!([hook_entry("hook_error", 11, 3, "target not found: Foo.bar")]),
            3,
        ),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut events = Vec::new();
    let err = run_rule_session(&client, &params(Expect::Loaded), |e| events.push(e))
        .await
        .unwrap_err();
    assert!(matches!(err, SessionError::HookError(_)));
    assert!(events
        .iter()
        .any(|e| matches!(e, SessionEvent::Entry(entry) if entry.event == "hook_error")));
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn restart_failure_is_not_mislabeled_as_hook_error() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(13)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [], "needs_trigger": [], "not_installed": [], "launch_failed": [],
            "failed": { PKG: "force-stop failed" }
        })),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut events = Vec::new();
    let err = run_rule_session(&client, &params(Expect::Loaded), |e| events.push(e))
        .await
        .unwrap_err();
    assert!(matches!(err, SessionError::RestartFailed { count: 1 }));
    assert!(events
        .iter()
        .any(|e| matches!(e, SessionEvent::RestartFailed { package, .. } if package == PKG)));
    // Restart failures must not surface as hook_error events.
    assert!(!events
        .iter()
        .any(|e| matches!(e, SessionEvent::Entry(entry) if entry.event == "hook_error")));
    // No collect polls after a restart failure.
    assert!(agent.captured_method("collect_logs").is_empty());
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn needs_trigger_reports_target_stopped_and_continues() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(20)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [], "needs_trigger": [PKG], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        "collect_logs" => collect_ok(json!([hook_entry("hook_loaded", 20, 1, "loaded")]), 1),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut events = Vec::new();
    let outcome = run_rule_session(&client, &params(Expect::Loaded), |e| events.push(e))
        .await
        .unwrap();
    assert!(outcome.success);
    assert!(events
        .iter()
        .any(|e| matches!(e, SessionEvent::TargetStopped { package } if package == PKG)));
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn not_installed_and_launch_failed_are_reported_without_aborting() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(21)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [], "needs_trigger": [], "not_installed": ["com.missing.app"],
            "launch_failed": [PKG], "failed": {}
        })),
        "collect_logs" => collect_ok(json!([hook_entry("hook_loaded", 21, 2, "loaded")]), 2),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut events = Vec::new();
    let outcome = run_rule_session(&client, &params(Expect::Loaded), |e| events.push(e))
        .await
        .unwrap();
    assert!(outcome.success);
    assert!(events.iter().any(|e| matches!(e, SessionEvent::TargetNotInstalled { package } if package == "com.missing.app")));
    assert!(events
        .iter()
        .any(|e| matches!(e, SessionEvent::LaunchFailed { package, .. } if package == PKG)));
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn exact_generation_isolation_ignores_stale_events() {
    let index_collects = std::sync::atomic::AtomicUsize::new(0);
    let agent = FakeAgent::start(move |captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(99)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        // First poll returns only a stale generation event; second returns the match.
        "collect_logs" => {
            let call = index_collects.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            if call == 0 {
                collect_ok(
                    json!([hook_entry("hook_loaded", 7, 1, "stale-generation-load")]),
                    1,
                )
            } else {
                collect_ok(
                    json!([hook_entry("hook_loaded", 99, 2, "current-generation-load")]),
                    2,
                )
            }
        }
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut events = Vec::new();
    let outcome = run_rule_session(&client, &params(Expect::Loaded), |e| events.push(e))
        .await
        .unwrap();
    assert!(outcome.success);
    let entries: Vec<_> = events
        .iter()
        .filter_map(|e| match e {
            SessionEvent::Entry(entry) => Some(entry),
            _ => None,
        })
        .collect();
    assert_eq!(entries.len(), 1, "stale generation event must be dropped");
    assert_eq!(entries[0].message, "current-generation-load");
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn timeout_returns_deadline_error_without_sleeping_forever() {
    let agent = FakeAgent::start(|captured, index| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(1)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        // Never deliver the expected event; echo the cursor back.
        "collect_logs" => collect_ok(json!([]), index as i64),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    // Modest poll interval keeps the number of loopback connections bounded
    // (each collect opens a Connection: close socket; a zero-interval loop can
    // exhaust macOS ephemeral ports / TIME_WAIT under repeated runs).
    let mut session = params(Expect::Loaded);
    session.wait = Duration::from_millis(150);
    session.poll_interval = Duration::from_millis(5);

    let mut events = Vec::new();
    let start = std::time::Instant::now();
    let err = run_rule_session(&client, &session, |e| events.push(e))
        .await
        .unwrap_err();
    assert!(
        elapsed(start) < Duration::from_secs(5),
        "timeout must not hang"
    );
    assert!(matches!(err, SessionError::Timeout { .. }));
    assert!(events
        .iter()
        .any(|e| matches!(e, SessionEvent::Timeout { .. })));
    assert!(agent.captured_method("collect_logs").len() >= 2);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn cursor_progression_and_request_parameters_are_correct() {
    let collect_calls = std::sync::atomic::AtomicUsize::new(0);
    let agent = FakeAgent::start(move |captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(42)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        "collect_logs" => {
            let call = collect_calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            if call == 0 {
                collect_ok(json!([]), 3)
            } else {
                collect_ok(json!([hook_entry("hook_loaded", 42, 4, "loaded")]), 4)
            }
        }
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    run_rule_session(&client, &params(Expect::Loaded), |_| {})
        .await
        .unwrap();

    let captured = agent.captured();
    let deploy = captured.iter().find(|r| r.method == "deploy_rule").unwrap();
    assert_eq!(deploy.params["rule_id"], RULE_ID);
    assert_eq!(deploy.params["content"], "rule_version = \"1\"");
    assert_eq!(deploy.params["packages"], json!([PKG]));
    assert_eq!(deploy.params["ensure_scope"], true);

    let restart = captured
        .iter()
        .find(|r| r.method == "restart_targets")
        .unwrap();
    assert_eq!(restart.params["packages"], json!([PKG]));

    let collects: Vec<&CapturedRequest> = captured
        .iter()
        .filter(|r| r.method == "collect_logs")
        .collect();
    assert_eq!(collects.len(), 2);
    // First poll: no cursor yet.
    assert!(collects[0].params["after_event_id"].is_null());
    // Second poll: cursor advances to the previous next_event_id.
    assert_eq!(collects[1].params["after_event_id"], 3);
    for c in &collects {
        assert_eq!(c.params["rule_id"], RULE_ID);
        assert_eq!(c.params["exact_generation"], 42);
        assert_eq!(c.params["limit"], 50);
    }
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn no_restart_deploys_only_and_skips_restart_rpc() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(5)),
        "collect_logs" => collect_ok(json!([hook_entry("hook_loaded", 5, 1, "loaded")]), 1),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut session = params(Expect::Loaded);
    session.no_restart = true;
    session.packages = vec!["android".into()];

    let mut events = Vec::new();
    let outcome = run_rule_session(&client, &session, |e| events.push(e))
        .await
        .unwrap();
    assert!(outcome.success);
    assert!(events
        .iter()
        .any(|e| matches!(e, SessionEvent::DeployOnly { .. })));
    assert!(agent.captured_method("restart_targets").is_empty());
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn unscoped_session_is_rejected_before_any_rpc() {
    let agent = FakeAgent::start(|_, _| FakeResponse::HttpStatus(500, "must not be called".into()));
    let client = connect(&agent).await;

    let mut session = params(Expect::Loaded);
    session.packages.clear();
    let err = run_rule_session(&client, &session, |_| {})
        .await
        .unwrap_err();
    assert!(matches!(err, SessionError::Invalid(_)));
    assert!(agent.captured().is_empty());
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn system_server_restart_is_rejected_unless_no_restart() {
    let agent = FakeAgent::start(|_, _| FakeResponse::HttpStatus(500, "must not be called".into()));
    let client = connect(&agent).await;

    let mut session = params(Expect::Loaded);
    session.packages = vec!["android".into()];
    let err = run_rule_session(&client, &session, |_| {})
        .await
        .unwrap_err();
    assert!(matches!(err, SessionError::Invalid(_)));
    assert!(agent.captured().is_empty());
    agent.join();
}

fn elapsed(start: std::time::Instant) -> Duration {
    std::time::Instant::now().duration_since(start)
}
