//! End-to-end tests of the PC script session orchestration against a loopback
//! fake Agent: full deploy → enable → restart → loaded → hit → logs lifecycle,
//! mutation idempotency by request_id, reconnect/cursor continuation, script
//! error aborts, and timeouts — all without an Android device.

mod common;

use std::time::Duration;

use common::{CapturedRequest, FakeAgent, FakeResponse};
use modspec_protocol::script::run_script_session;
use modspec_protocol::{
    RpcClient, ScriptDeployParams, ScriptDeployResponse, ScriptEnableParams, ScriptExpect,
    ScriptFileDto, ScriptSessionError, ScriptSessionEvent, ScriptSessionParams,
};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicUsize, Ordering};

const SCRIPT_ID: &str = "xiaomi/security-center/macro-gate";
const PKG: &str = "com.miui.securitycenter";

const MANIFEST: &str = r#"
script_version = "1"
[meta]
id = "xiaomi/security-center/macro-gate"
name = "Macro gate"
[compatible]
packages = ["com.miui.securitycenter"]
target_packages = ["com.ChillyRoom.DungeonShooter"]
[engine]
runtime = "js"
[permissions]
capabilities = ["emit", "log"]
"#;

fn files() -> Vec<ScriptFileDto> {
    vec![ScriptFileDto {
        name: "src/main.js".into(),
        content: "modspec.emit('macro_allowed', { game: 'com.ChillyRoom.DungeonShooter' });".into(),
    }]
}

fn ok_deploy_script(generation: i64) -> Value {
    json!({
        "script_id": SCRIPT_ID,
        "stored": true,
        "publish_mode": "remote_file",
        "generation": generation,
        "engine": "js",
        "content_hash": "aabbccdd",
        "scope_status": "applied",
        "scope_packages": [PKG],
        "message": "stored mode=remote_file scope=applied"
    })
}

fn collect_ok(entries: Value, next_event_id: i64) -> FakeResponse {
    FakeResponse::Result(json!({
        "entries": entries,
        "next_event_id": next_event_id,
        "first_event_id": 1,
        "truncated": false,
        "source": "journal"
    }))
}

fn script_entry(event: &str, generation: i64, event_id: i64, message: &str) -> Value {
    json!({
        "event_id": event_id,
        "timestamp_ms": 1_700_000_000_000_i64 + event_id,
        "level": "I",
        "tag": "ModspecScript",
        "event": event,
        "generation": generation,
        "rule_id": Value::Null,
        "script_id": SCRIPT_ID,
        "package": PKG,
        "message": message,
        "raw": "logcat-line"
    })
}

fn params(expect: ScriptExpect, request_id: Option<String>) -> ScriptSessionParams {
    ScriptSessionParams {
        script_id: SCRIPT_ID.into(),
        manifest: MANIFEST.into(),
        files: files(),
        packages: vec![PKG.into()],
        wait: Duration::from_secs(5),
        expect,
        no_restart: false,
        request_id,
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

#[tokio::test(flavor = "current_thread")]
async fn full_lifecycle_deploy_enable_restart_loaded_hit_logs_disable() {
    let store_calls = std::sync::Arc::new(AtomicUsize::new(0));
    let enable_calls = std::sync::Arc::new(AtomicUsize::new(0));
    let disable_calls = std::sync::Arc::new(AtomicUsize::new(0));
    let collect_calls = std::sync::Arc::new(AtomicUsize::new(0));
    let agent = FakeAgent::start({
        let store_calls = std::sync::Arc::clone(&store_calls);
        let enable_calls = std::sync::Arc::clone(&enable_calls);
        let disable_calls = std::sync::Arc::clone(&disable_calls);
        let collect_calls = std::sync::Arc::clone(&collect_calls);
        move |captured, _| match captured.method.as_str() {
            "script_deploy" => {
                let n = store_calls.fetch_add(1, Ordering::SeqCst);
                FakeResponse::Result(ok_deploy_script(100 + n as i64))
            }
            "restart_targets" => FakeResponse::Result(json!({
                "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
            })),
            "collect_logs" => {
                let call = collect_calls.fetch_add(1, Ordering::SeqCst);
                match call {
                    0 => collect_ok(json!([]), 0),
                    1 => collect_ok(
                        json!([script_entry("script_loaded", 100, 6, "runtime ready")]),
                        6,
                    ),
                    _ => collect_ok(
                        json!([script_entry(
                            "script_hit",
                            100,
                            9,
                            "macro_allowed for DungeonShooter"
                        )]),
                        9,
                    ),
                }
            }
            "script_enable" => {
                enable_calls.fetch_add(1, Ordering::SeqCst);
                FakeResponse::Result(json!({
                    "script_id": SCRIPT_ID, "enabled": true, "disabled": ["test/old-script"], "generation": 101
                }))
            }
            "script_disable" => {
                disable_calls.fetch_add(1, Ordering::SeqCst);
                FakeResponse::Result(json!({
                    "script_id": SCRIPT_ID, "disabled": true, "generation": 102
                }))
            }
            other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
        }
    });
    let client = connect(&agent).await;

    // 1. run_script_session: deploy → restart → script_loaded observed.
    let mut session_events = Vec::new();
    let outcome = run_script_session(&client, &params(ScriptExpect::Loaded, None), |e| {
        session_events.push(e)
    })
    .await
    .unwrap();
    assert!(outcome.success);
    assert_eq!(outcome.generation, 100);
    assert!(session_events.iter().any(|e| matches!(
        e,
        ScriptSessionEvent::ScriptUploaded { publish_mode, .. } if publish_mode == "remote_file"
    )));
    assert!(session_events.iter().any(|e| matches!(
        e,
        ScriptSessionEvent::TargetRestarted { package } if package == PKG
    )));

    // The deploy params carried a request_id and activate=true.
    let captured = agent.captured();
    let deploy = captured
        .iter()
        .find(|r| r.method == "script_deploy")
        .unwrap();
    assert!(!deploy.params["request_id"].as_str().unwrap().is_empty());
    assert_eq!(deploy.params["activate"], json!(true));
    assert_eq!(deploy.params["script_id"], json!(SCRIPT_ID));

    // 2. Explicit enable with an exclusive switch (first-class active selection).
    let enabled = client
        .script_enable(&ScriptEnableParams {
            request_id: "req-enable-1".into(),
            script_id: SCRIPT_ID.into(),
            exclusive: true,
        })
        .await
        .unwrap();
    assert!(enabled.enabled);
    assert_eq!(enabled.disabled, vec!["test/old-script".to_string()]);
    assert_eq!(enable_calls.load(Ordering::SeqCst), 1);

    // 3. Disable (no empty-profile workaround needed).
    let disabled = client
        .script_disable(&modspec_protocol::ScriptDisableParams {
            request_id: "req-disable-1".into(),
            script_id: SCRIPT_ID.into(),
        })
        .await
        .unwrap();
    assert!(disabled.disabled);
    assert_eq!(disable_calls.load(Ordering::SeqCst), 1);

    // 4. Logs were filtered by script_id + exact generation.
    let logs = captured
        .iter()
        .rev()
        .find(|r| r.method == "collect_logs")
        .unwrap();
    assert_eq!(logs.params["script_id"], json!(SCRIPT_ID));
    assert_eq!(logs.params["exact_generation"], json!(100));

    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn mutation_idempotency_request_id_reused() {
    // The fake replays the stored response when the same request_id arrives —
    // the idempotency contract the PC relies on after a reconnect.
    let deployed_once = std::sync::Arc::new(AtomicUsize::new(0));
    let agent = FakeAgent::start({
        let deployed_once = std::sync::Arc::clone(&deployed_once);
        move |captured, _| match captured.method.as_str() {
            "script_deploy" => {
                if deployed_once.fetch_add(1, Ordering::SeqCst) > 0 {
                    // Second identical request must carry the SAME request_id.
                    FakeResponse::JsonRpcError(
                        -32099,
                        "duplicate request without same request_id".into(),
                    )
                } else {
                    FakeResponse::Result(ok_deploy_script(5))
                }
            }
            other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
        }
    });
    let client = connect(&agent).await;

    let request_id = "stable-request-id-42".to_string();
    let first = client
        .script_deploy(&ScriptDeployParams {
            request_id: request_id.clone(),
            script_id: SCRIPT_ID.into(),
            manifest: MANIFEST.into(),
            files: files(),
            ensure_scope: true,
            activate: true,
        })
        .await
        .unwrap();
    assert!(first.stored);

    // Same logical mutation re-sent after a transport loss: same request_id.
    let second = client
        .script_deploy(&ScriptDeployParams {
            request_id: request_id.clone(),
            script_id: SCRIPT_ID.into(),
            manifest: MANIFEST.into(),
            files: files(),
            ensure_scope: true,
            activate: true,
        })
        .await
        .unwrap_err();
    assert!(matches!(
        second,
        modspec_protocol::RpcClientError::Rpc { code: -32099, .. }
    ));

    let deploys = agent.captured_method("script_deploy");
    assert_eq!(deploys.len(), 2);
    assert_eq!(deploys[0].params["request_id"], json!(request_id));
    assert_eq!(deploys[1].params["request_id"], json!(request_id));
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn session_uses_stable_request_id_and_reports_it() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "script_deploy" => FakeResponse::Result(ok_deploy_script(1)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        "collect_logs" => collect_ok(json!([script_entry("script_loaded", 1, 2, "ok")]), 2),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let outcome = run_script_session(&client, &params(ScriptExpect::Loaded, None), |_| {})
        .await
        .unwrap();
    let deploy = agent.captured_method("script_deploy");
    assert_eq!(deploy[0].params["request_id"], json!(outcome.request_id));
    assert!(!outcome.request_id.is_empty());
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn reconnect_cursor_continuation_no_duplicates() {
    // First collect_logs transport-fails (HTTP 500): the session retries the
    // READ-ONLY poll with the same cursor and reports poll_retry; a subsequent
    // agent re-echo of an already-delivered entry is deduplicated.
    let collect_calls = std::sync::Arc::new(AtomicUsize::new(0));
    let agent = FakeAgent::start({
        let collect_calls = std::sync::Arc::clone(&collect_calls);
        move |captured, _| match captured.method.as_str() {
            "script_deploy" => FakeResponse::Result(ok_deploy_script(3)),
            "restart_targets" => FakeResponse::Result(json!({
                "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
            })),
            "collect_logs" => {
                let call = collect_calls.fetch_add(1, Ordering::SeqCst);
                match call {
                    0 => FakeResponse::HttpStatus(500, "transient failure".into()),
                    1 => collect_ok(json!([script_entry("script_loaded", 3, 4, "ok")]), 4),
                    // Reconnect race: the agent echoes an already-delivered entry.
                    2 => collect_ok(
                        json!([
                            script_entry("script_loaded", 3, 4, "ok"),
                            script_entry("script_hit", 3, 5, "hit")
                        ]),
                        5,
                    ),
                    _ => collect_ok(json!([]), 5),
                }
            }
            other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
        }
    });
    let client = connect(&agent).await;

    let mut events = Vec::new();
    let outcome = run_script_session(&client, &params(ScriptExpect::Hit, None), |e| {
        events.push(e)
    })
    .await
    .unwrap();
    assert!(outcome.success);
    assert_eq!(
        events
            .iter()
            .filter(|e| matches!(e, ScriptSessionEvent::PollRetry { .. }))
            .count(),
        1
    );
    // script_loaded delivered exactly once despite the re-echo.
    let loaded_count = events
        .iter()
        .filter(|e| matches!(e, ScriptSessionEvent::Entry(entry) if entry.event == "script_loaded"))
        .count();
    assert_eq!(loaded_count, 1);
    let hit_count = events
        .iter()
        .filter(|e| matches!(e, ScriptSessionEvent::Entry(entry) if entry.event == "script_hit"))
        .count();
    assert_eq!(hit_count, 1);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn script_error_aborts_session() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "script_deploy" => FakeResponse::Result(ok_deploy_script(9)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        "collect_logs" => collect_ok(
            json!([script_entry(
                "script_error",
                9,
                3,
                "circuit open after 10 consecutive failures"
            )]),
            3,
        ),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut events = Vec::new();
    let err = run_script_session(&client, &params(ScriptExpect::Loaded, None), |e| {
        events.push(e)
    })
    .await
    .unwrap_err();
    assert!(
        matches!(err, ScriptSessionError::ScriptError(message) if message.contains("circuit open"))
    );
    assert!(events
        .iter()
        .any(|e| matches!(e, ScriptSessionEvent::ScriptError { .. })));
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn session_times_out_with_script_deployed() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "script_deploy" => FakeResponse::Result(ok_deploy_script(2)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": [PKG], "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        "collect_logs" => collect_ok(json!([]), 0),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let mut p = params(ScriptExpect::Loaded, None);
    p.wait = Duration::from_millis(50);
    let err = run_script_session(&client, &p, |_| {}).await.unwrap_err();
    assert!(matches!(
        err,
        ScriptSessionError::Timeout { expected: "loaded" }
    ));
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn unscoped_session_is_refused() {
    let agent =
        FakeAgent::start(|_, _| FakeResponse::HttpStatus(500, "must not be reached".into()));
    let client = connect(&agent).await;
    let mut p = params(ScriptExpect::Loaded, None);
    p.packages.clear();
    let err = run_script_session(&client, &p, |_| {}).await.unwrap_err();
    assert!(matches!(err, ScriptSessionError::Invalid(_)));
    assert!(agent.captured().is_empty());
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn deploy_transport_failure_is_never_retried() {
    let deploy_calls = std::sync::Arc::new(AtomicUsize::new(0));
    let agent = FakeAgent::start({
        let deploy_calls = std::sync::Arc::clone(&deploy_calls);
        move |captured, _| {
            if captured.method == "script_deploy" {
                let n = deploy_calls.fetch_add(1, Ordering::SeqCst);
                if n == 0 {
                    return FakeResponse::HttpStatus(500, "agent restarted mid-request".into());
                }
            }
            FakeResponse::HttpStatus(500, format!("unexpected {}", captured.method))
        }
    });
    let client = connect(&agent).await;
    let err = run_script_session(&client, &params(ScriptExpect::Loaded, None), |_| {})
        .await
        .unwrap_err();
    assert!(matches!(err, ScriptSessionError::Deploy(_)));
    // Exactly one deploy attempt; never blindly retried.
    assert_eq!(deploy_calls.load(Ordering::SeqCst), 1);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn list_and_reload_rpcs_roundtrip() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "script_list" => FakeResponse::Result(json!({
            "active_script": SCRIPT_ID,
            "scripts": [{
                "script_id": SCRIPT_ID,
                "name": "Macro gate",
                "engine": "js",
                "version": "1.0.0",
                "content_hash": "aabbccdd",
                "active": true,
                "generation": 5,
                "last_loaded_ms": 1_700_000_000_000_i64,
                "last_hit_ms": 1_700_000_000_001_i64,
                "last_error": null,
                "hit_count": 3,
                "error_count": 0,
                "packages": [PKG],
                "target_packages": ["com.ChillyRoom.DungeonShooter"]
            }]
        })),
        "script_reload" => FakeResponse::Result(json!({
            "script_id": SCRIPT_ID,
            "reload_started": true,
            "generation": 6,
            "restarted": [PKG],
            "needs_trigger": [], "not_installed": [], "launch_failed": [], "failed": {}
        })),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let listed = client.script_list(&Default::default()).await.unwrap();
    assert_eq!(listed.active_script.as_deref(), Some(SCRIPT_ID));
    assert_eq!(listed.scripts.len(), 1);
    assert_eq!(
        listed.scripts[0].target_packages,
        vec!["com.ChillyRoom.DungeonShooter".to_string()]
    );
    assert_eq!(listed.scripts[0].hit_count, 3);

    let reloaded = client
        .script_reload(&modspec_protocol::ScriptReloadParams {
            request_id: "req-reload-1".into(),
            script_id: SCRIPT_ID.into(),
            restart: true,
        })
        .await
        .unwrap();
    assert!(reloaded.reload_started);
    assert_eq!(reloaded.restarted, vec![PKG.to_string()]);
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn validate_rpc_reports_agent_side_errors() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "script_validate" => FakeResponse::Result(json!({
            "ok": false,
            "errors": ["entrypoint not found in bundle: src/main.js"]
        })),
        other => FakeResponse::HttpStatus(500, format!("unexpected {other}")),
    });
    let client = connect(&agent).await;

    let validated = client
        .script_validate(&modspec_protocol::ScriptValidateParams {
            manifest: MANIFEST.into(),
            files: vec![],
        })
        .await
        .unwrap();
    assert!(!validated.ok);
    assert_eq!(validated.errors.len(), 1);
    agent.join();
}

// Ensure the response shapes stay decodable even when optional fields are absent.
#[test]
fn deploy_response_parses_without_optional_fields() {
    let body = r#"{
        "jsonrpc":"2.0","id":"1",
        "result":{"script_id":"a/b","stored":true,"publish_mode":"remote_file",
                  "generation":3,"engine":"js","content_hash":"h",
                  "scope_status":"already","message":"ok"}
    }"#;
    let response: ScriptDeployResponse = RpcClient::parse_response(body).unwrap();
    assert!(response.stored);
    assert!(response.scope_packages.is_empty());
}

#[test]
fn session_request_serializes_request_id() {
    let captured: CapturedRequest = CapturedRequest {
        method: "script_deploy".into(),
        params: serde_json::to_value(&ScriptDeployParams {
            request_id: "req-xyz".into(),
            script_id: SCRIPT_ID.into(),
            manifest: MANIFEST.into(),
            files: files(),
            ensure_scope: true,
            activate: true,
        })
        .unwrap(),
        auth_header: None,
    };
    assert_eq!(captured.params["request_id"], json!("req-xyz"));
    assert_eq!(captured.params["activate"], json!(true));
}
