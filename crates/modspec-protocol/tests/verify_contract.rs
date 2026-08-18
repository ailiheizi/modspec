//! Contract tests for the verify / reapply / soft_restart RPC family against
//! a loopback fake Agent: method names, request parameters, bearer auth, and
//! tolerant deserialization of the response shapes.

mod common;

use common::{FakeAgent, FakeResponse};
use modspec_protocol::{ReapplyParams, RpcClient, SoftRestartParams, VerifyParams};
use serde_json::json;

#[tokio::test(flavor = "current_thread")]
async fn verify_reapply_soft_restart_contract_against_fake_agent() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "verify" => FakeResponse::Result(json!({
            "drift": [
                {
                    "mod_id": "hyper-perf-pack/joyose",
                    "kind": "rule_ref",
                    "expected": "rule_version = \"1\"",
                    "actual": "rule_version = \"2\"",
                    "reason": "generation mismatch"
                }
            ]
        })),
        "reapply" => FakeResponse::Result(json!({ "job_id": "job-42" })),
        "soft_restart" => FakeResponse::Result(json!({
            "hot_reload_ok": true,
            "hot_reload_failed": false,
            "hot_reload_unsupported": false,
            "running_targets": ["com.example.target"],
            "restarted_packages": ["com.example.target"],
            "message": "soft restart done"
        })),
        other => FakeResponse::HttpStatus(500, format!("unexpected method {other}")),
    });

    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port)
        .with_auth_token(Some("secret-token-abc".into()));
    client.connect().await.unwrap();

    // (b) Drift array deserializes into the typed VerifyResponse.
    let verified = client
        .verify(&VerifyParams {
            profile_id: Some("x".into()),
        })
        .await
        .unwrap();
    assert_eq!(verified.drift.len(), 1);
    assert_eq!(
        verified.drift[0].mod_id.as_deref(),
        Some("hyper-perf-pack/joyose")
    );
    assert_eq!(verified.drift[0].kind.as_deref(), Some("rule_ref"));
    assert_eq!(
        verified.drift[0].expected.as_deref(),
        Some("rule_version = \"1\"")
    );
    assert_eq!(
        verified.drift[0].actual.as_deref(),
        Some("rule_version = \"2\"")
    );
    assert_eq!(
        verified.drift[0].reason.as_deref(),
        Some("generation mismatch")
    );

    let reapplied = client
        .reapply(&ReapplyParams { only_failed: true })
        .await
        .unwrap();
    assert_eq!(reapplied.job_id, "job-42");

    let restarted = client
        .soft_restart(&SoftRestartParams { rules_only: true })
        .await
        .unwrap();
    assert!(restarted.hot_reload_ok);
    assert!(!restarted.hot_reload_failed);
    assert!(!restarted.hot_reload_unsupported);
    assert_eq!(restarted.running_targets, vec!["com.example.target"]);
    assert_eq!(restarted.restarted_packages, vec!["com.example.target"]);
    assert_eq!(restarted.message, "soft restart done");

    let captured = agent.join();
    assert_eq!(captured.len(), 3);

    // (a) verify: correct method name, params and Bearer auth.
    assert_eq!(captured[0].method, "verify");
    assert_eq!(captured[0].params["profile_id"], "x");
    assert_eq!(
        captured[0].auth_header.as_deref(),
        Some("Bearer secret-token-abc")
    );

    // (d) reapply carries only_failed.
    assert_eq!(captured[1].method, "reapply");
    assert_eq!(captured[1].params["only_failed"], true);

    // (c) soft_restart carries rules_only.
    assert_eq!(captured[2].method, "soft_restart");
    assert_eq!(captured[2].params["rules_only"], true);
}

/// The agent's drift items are still evolving (parallel agent work): responses
/// with partial or unknown fields must still deserialize.
#[tokio::test(flavor = "current_thread")]
async fn verify_response_tolerates_partial_drift_from_fake_agent() {
    let agent = FakeAgent::start(|captured, _| {
        if captured.method == "verify" {
            FakeResponse::Result(json!({
                "drift": [
                    { "mod_id": "foo" },
                    { "mod_id": "bar", "kind": "rule_ref", "reason": "missing" },
                    { "some_future_field": 42 }
                ],
                "extra_field": "ignored"
            }))
        } else {
            FakeResponse::HttpStatus(500, format!("unexpected method {}", captured.method))
        }
    });

    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port);
    client.connect().await.unwrap();
    let verified = client
        .verify(&VerifyParams { profile_id: None })
        .await
        .unwrap();
    assert_eq!(verified.drift.len(), 3);
    assert_eq!(verified.drift[0].kind, None);
    assert_eq!(verified.drift[2].mod_id, None);
    agent.join();
}

/// soft_restart responses from an older agent may omit most fields.
#[tokio::test(flavor = "current_thread")]
async fn soft_restart_response_tolerates_minimal_payload() {
    let agent = FakeAgent::start(|captured, _| {
        if captured.method == "soft_restart" {
            FakeResponse::Result(json!({ "message": "restarting" }))
        } else {
            FakeResponse::HttpStatus(500, format!("unexpected method {}", captured.method))
        }
    });

    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port);
    client.connect().await.unwrap();
    let restarted = client
        .soft_restart(&SoftRestartParams { rules_only: false })
        .await
        .unwrap();
    assert!(!restarted.hot_reload_ok);
    assert!(restarted.running_targets.is_empty());
    assert_eq!(restarted.message, "restarting");
    agent.join();
}
