//! PC rule-session protocol contract against a loopback fake Agent.
//! Verifies method names, request parameters, and the structured response
//! shape end-to-end over the real HTTP transport.

mod common;

use common::{hook_entry, ok_deploy, FakeAgent, FakeResponse};
use modspec_protocol::{
    AppInfoParams, AppListParams, AppScope, CollectLogsParams, DeployRuleParams, GetLogsParams,
    InspectDeviceParams, ProcessListParams, RestartTargetsParams, RpcClient, TriggerAppParams,
};
use serde_json::json;

#[tokio::test(flavor = "current_thread")]
async fn pc_rule_session_contract_against_fake_agent() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "deploy_rule" => FakeResponse::Result(ok_deploy(42)),
        "restart_targets" => FakeResponse::Result(json!({
            "restarted": ["com.example.target"],
            "needs_trigger": [],
            "not_installed": [],
            "launch_failed": [],
            "failed": {}
        })),
        "collect_logs" => FakeResponse::Result(json!({
            "entries": [hook_entry("hook_loaded", 42, 7, "com.example.Target.run (static)")],
            "next_event_id": 7,
            "first_event_id": 1,
            "truncated": false,
            "source": "journal"
        })),
        other => FakeResponse::HttpStatus(500, format!("unexpected method {other}")),
    });

    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port);
    client.connect().await.unwrap();

    let deployed = client
        .deploy_rule(&DeployRuleParams {
            rule_id: "test/smoke".into(),
            content: "rule_version = \"1\"".into(),
            packages: vec!["com.example.target".into()],
            ensure_scope: true,
        })
        .await
        .unwrap();
    assert!(deployed.stored);
    assert_eq!(deployed.scope_status, "applied");
    assert_eq!(deployed.generation, 42);

    let restarted = client
        .restart_targets(&RestartTargetsParams {
            packages: vec!["com.example.target".into()],
        })
        .await
        .unwrap();
    assert_eq!(restarted.restarted, vec!["com.example.target".to_string()]);
    assert!(restarted.failed.is_empty());

    let logs = client
        .collect_logs(&CollectLogsParams {
            after_event_id: Some(3),
            limit: 50,
            rule_id: Some("test/smoke".into()),
            exact_generation: Some(42),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(logs.entries.len(), 1);
    assert_eq!(logs.entries[0].event, "hook_loaded");
    assert_eq!(logs.entries[0].rule_id.as_deref(), Some("test/smoke"));
    assert_eq!(logs.entries[0].event_id, 7);
    assert_eq!(logs.next_event_id, 7);
    assert!(!logs.truncated);
    assert_eq!(logs.source, "journal");

    let captured = agent.join();
    assert_eq!(captured.len(), 3);
    assert_eq!(captured[0].method, "deploy_rule");
    assert_eq!(captured[0].params["rule_id"], "test/smoke");
    assert_eq!(captured[0].params["content"], "rule_version = \"1\"");
    assert_eq!(
        captured[0].params["packages"],
        json!(["com.example.target"])
    );
    assert_eq!(captured[0].params["ensure_scope"], true);
    assert_eq!(captured[1].method, "restart_targets");
    assert_eq!(
        captured[1].params["packages"],
        json!(["com.example.target"])
    );
    assert_eq!(captured[2].method, "collect_logs");
    assert_eq!(captured[2].params["after_event_id"], 3);
    assert_eq!(captured[2].params["limit"], 50);
    assert_eq!(captured[2].params["rule_id"], "test/smoke");
    assert_eq!(captured[2].params["exact_generation"], 42);
    assert!(captured[2].params["min_generation"].is_null());
}

#[tokio::test(flavor = "current_thread")]
async fn bearer_auth_header_is_sent_and_echoed() {
    let agent = FakeAgent::start(|_, _| FakeResponse::Result(json!({ "pong": true })));

    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port)
        .with_auth_token(Some("secret-token-abc".into()));
    client.connect().await.unwrap();
    let pong = client.ping().await.unwrap();
    assert!(pong.pong);

    let captured = agent.join();
    assert_eq!(captured.len(), 1);
    assert_eq!(
        captured[0].auth_header.as_deref(),
        Some("Bearer secret-token-abc")
    );
}

#[tokio::test(flavor = "current_thread")]
async fn no_auth_header_when_token_absent() {
    let agent = FakeAgent::start(|_, _| FakeResponse::Result(json!({ "pong": true })));

    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port);
    client.connect().await.unwrap();
    let _ = client.ping().await.unwrap();

    let captured = agent.join();
    assert_eq!(captured[0].auth_header, None);
}

#[tokio::test(flavor = "current_thread")]
async fn unauthorized_http_response_is_mapped() {
    let agent = FakeAgent::start(|_, _| FakeResponse::HttpStatus(401, "bad token".into()));

    let mut client =
        RpcClient::new("127.0.0.1", agent.port, agent.port).with_auth_token(Some("expired".into()));
    client.connect().await.unwrap();
    let err = client.ping().await.unwrap_err();
    assert!(
        matches!(err, modspec_protocol::RpcClientError::Unauthorized { .. }),
        "expected Unauthorized, got {err}"
    );
    agent.join();
}

#[tokio::test(flavor = "current_thread")]
async fn jsonrpc_error_is_mapped() {
    let agent =
        FakeAgent::start(|_, _| FakeResponse::JsonRpcError(-32010, "scope not ensured".into()));

    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port);
    client.connect().await.unwrap();
    let err = client
        .deploy_rule(&DeployRuleParams {
            rule_id: "a/b".into(),
            content: "rule_version = \"1\"".into(),
            packages: vec!["com.example.target".into()],
            ensure_scope: true,
        })
        .await
        .unwrap_err();
    assert!(matches!(
        err,
        modspec_protocol::RpcClientError::Rpc { code: -32010, .. }
    ));
    agent.join();
}

/// Contract test for the read-only inventory/status RPC family:
/// `inspect_device`, `app_list`, `app_info`, `process_list`, `get_logs`,
/// `module_diagnostics` and the explicit `trigger_app`.
#[tokio::test(flavor = "current_thread")]
async fn read_only_inventory_contract_against_fake_agent() {
    let agent = FakeAgent::start(|captured, _| match captured.method.as_str() {
        "inspect_device" => FakeResponse::Result(json!({
            "hardware": {
                "manufacturer": "Xiaomi", "brand": "Xiaomi", "model": "M2102J2SC",
                "device": "haydn", "product": "haydn", "board": "kona", "hardware": "kona",
                "soc_manufacturer": "Qualcomm", "soc_model": "SM8350",
                "cpu_abis": ["arm64-v8a"], "cpu_cores": 8
            },
            "software": {
                "android_release": "13", "sdk_int": 33, "security_patch": "2023-01-01",
                "build_id": "TKQ1", "incremental": "V14", "display_build": "TKQ1",
                "fingerprint": "x/y/z:13/abc/1:user/release-keys"
            },
            "display": { "width_pixels": 1080, "height_pixels": 2400, "density_dpi": 440, "refresh_rate_hz": 120.0 },
            "memory": { "total_bytes": 1000, "available_bytes": 500, "low_memory": false },
            "storage": { "internal_total_bytes": 1000, "internal_available_bytes": 500 },
            "runtime": {
                "root_available": true, "xposed_service_bound": true,
                "lsposed_framework": "LSPosed-mod 1.10.1 (7024)", "agent_version": "0.1.0"
            },
            "apps": {
                "total": 2, "system": 1, "user": 1, "returned": 2, "truncated": false,
                "entries": [
                    { "package": "com.android.settings", "version_name": "13", "version_code": 33, "system": true, "enabled": true },
                    { "package": "com.example.app", "version_name": null, "version_code": 42, "system": false, "enabled": false }
                ]
            }
        })),
        "app_list" => FakeResponse::Result(json!({
            "total": 2, "system": 1, "user": 1, "returned": 2, "truncated": false,
            "scope": "user",
            "entries": [
                { "package": "com.example.app", "version_name": "1.2.3", "version_code": 12, "system": false, "enabled": true }
            ]
        })),
        "app_info" => FakeResponse::Result(json!({
            "package": "com.example.app", "version_name": "1.2.3", "version_code": 12,
            "system": false, "enabled": true, "installer": "com.android.vending",
            "launchable": true, "primary_activity": "com.example.app/.MainActivity",
            "uid": 10123, "first_install_ms": 1_700_000_000_000_i64, "last_update_ms": 1_700_000_000_000_i64,
            "components": { "activities": 3, "services": 1, "receivers": 2, "providers": 0 }
        })),
        "process_list" => FakeResponse::Result(json!({
            "processes": [
                { "package": "com.example.target", "pid": 1234, "uid": 10123, "user": "u0_a123", "state": "S", "name": "com.example.target" }
            ],
            "total": 1, "truncated": false, "source": "ps"
        })),
        "get_logs" => FakeResponse::Result(json!({
            "entries": [
                { "timestamp_ms": 1_700_000_000_123_i64, "level": "E", "tag": "AndroidRuntime", "pid": 1234, "tid": 1234, "message": "boom" }
            ],
            "truncated": false, "source": "logcat", "root_available": true, "resolved_pids": [1234]
        })),
        "module_diagnostics" => FakeResponse::Result(json!({
            "lsposed_framework": "LSPosed-mod 1.10.1 (7024)", "xposed_service_bound": true,
            "scope": ["system", "com.example.target"], "active_rules": ["test/smoke-joyose"],
            "rules_generation": 1_700_000_000_000_i64, "lsposed_cli_available": true,
            "root_available": true, "event_source": "journal", "tailer_running": true
        })),
        "trigger_app" => FakeResponse::Result(json!({
            "package": "com.example.app", "launched": true, "method": "component",
            "needs_trigger": false, "message": "started com.example.app/.MainActivity"
        })),
        other => FakeResponse::HttpStatus(500, format!("unexpected method {other}")),
    });

    let mut client = RpcClient::new("127.0.0.1", agent.port, agent.port);
    client.connect().await.unwrap();

    // inspect_device with apps requested.
    let inspection = client
        .inspect_device(&InspectDeviceParams {
            include_apps: true,
            app_limit: 50,
        })
        .await
        .unwrap();
    assert_eq!(inspection.hardware.manufacturer, "Xiaomi");
    assert_eq!(inspection.hardware.cpu_cores, 8);
    assert_eq!(inspection.apps.total, 2);
    assert_eq!(inspection.apps.entries.len(), 2);
    assert!(!inspection.apps.entries[0].enabled || !inspection.apps.entries[1].enabled);
    assert!(inspection.runtime.root_available);

    // app_list with scope filter.
    let apps = client
        .app_list(&AppListParams {
            scope: AppScope::User,
            limit: 100,
            filter: Some("example".into()),
        })
        .await
        .unwrap();
    assert_eq!(apps.scope, AppScope::User);
    assert_eq!(apps.entries.len(), 1);
    assert_eq!(apps.entries[0].package, "com.example.app");

    // app_info.
    let info = client
        .app_info(&AppInfoParams {
            package: "com.example.app".into(),
        })
        .await
        .unwrap();
    assert_eq!(info.installer.as_deref(), Some("com.android.vending"));
    assert!(info.launchable);
    assert_eq!(info.components.activities, 3);
    assert_eq!(
        info.primary_activity.as_deref(),
        Some("com.example.app/.MainActivity")
    );

    // process_list filtered by package.
    let procs = client
        .process_list(&ProcessListParams {
            package: Some("com.example.target".into()),
            limit: 50,
        })
        .await
        .unwrap();
    assert_eq!(procs.processes.len(), 1);
    assert_eq!(procs.processes[0].pid, 1234);
    assert_eq!(
        procs.processes[0].package.as_deref(),
        Some("com.example.target")
    );

    // get_logs with package + tag filters.
    let logs = client
        .get_logs(&GetLogsParams {
            package: Some("com.example.target".into()),
            tag: Some("AndroidRuntime".into()),
            limit: 100,
            since_ms: Some(1_000_000),
        })
        .await
        .unwrap();
    assert_eq!(logs.source, "logcat");
    assert_eq!(logs.entries[0].tag, "AndroidRuntime");
    assert_eq!(logs.resolved_pids, vec![1234]);

    // module_diagnostics.
    let diagnostics = client.module_diagnostics().await.unwrap();
    assert!(diagnostics.xposed_service_bound);
    assert_eq!(diagnostics.scope.len(), 2);
    assert_eq!(diagnostics.active_rules, vec!["test/smoke-joyose"]);
    assert_eq!(diagnostics.event_source, "journal");

    // trigger_app with explicit component.
    let triggered = client
        .trigger_app(&TriggerAppParams {
            package: "com.example.app".into(),
            component: Some("com.example.app/.MainActivity".into()),
        })
        .await
        .unwrap();
    assert!(triggered.launched);
    assert_eq!(triggered.method, "component");

    let captured = agent.join();
    assert_eq!(captured.len(), 7);
    let by_method = |method: &str| -> &serde_json::Value {
        let req = captured
            .iter()
            .find(|r| r.method == method)
            .expect("captured request");
        &req.params
    };
    assert_eq!(by_method("inspect_device")["include_apps"], true);
    assert_eq!(by_method("inspect_device")["app_limit"], 50);
    assert_eq!(by_method("app_list")["scope"], "user");
    assert_eq!(by_method("app_list")["limit"], 100);
    assert_eq!(by_method("app_list")["filter"], "example");
    assert_eq!(by_method("app_info")["package"], "com.example.app");
    assert_eq!(by_method("process_list")["package"], "com.example.target");
    assert_eq!(by_method("get_logs")["tag"], "AndroidRuntime");
    assert_eq!(by_method("get_logs")["since_ms"], 1_000_000);
    assert_eq!(
        by_method("trigger_app")["component"],
        "com.example.app/.MainActivity"
    );
}
