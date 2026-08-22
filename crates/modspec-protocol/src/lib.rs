//! JSON-RPC 2.0 message types for modspec-agent ↔ modspec-cli.
//! Transport: HTTP POST :8764/rpc (primary), WebSocket :8765/rpc (optional).

use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

use modspec_core::{Profile, ProfileState};

pub mod apply;
pub mod apps;
pub mod client;
pub mod connection;
pub mod diagnostics;
pub mod exec;
pub mod inspect;
pub mod logs;
pub mod pairing;
pub mod process;
pub mod script;
pub mod session;
pub mod transport;
pub mod trigger;
pub mod verify;

pub use apply::*;
pub use apps::*;
pub use client::{RpcClient, RpcClientError};
pub use connection::*;
pub use diagnostics::*;
pub use exec::*;
pub use inspect::*;
pub use logs::*;
pub use pairing::*;
pub use process::*;
pub use script::{
    run_script_session, InstallFridaGadgetParams, InstallFridaGadgetResponse, ScriptDeployParams,
    ScriptDeployResponse, ScriptDisableParams, ScriptDisableResponse, ScriptEnableParams,
    ScriptEnableResponse, ScriptExpect, ScriptFileDto, ScriptInfo, ScriptListParams,
    ScriptListResponse, ScriptReloadParams, ScriptReloadResponse, ScriptRemoveParams,
    ScriptRemoveResponse, ScriptSessionError, ScriptSessionEvent, ScriptSessionOutcome,
    ScriptSessionParams, ScriptValidateParams, ScriptValidateResponse,
};
pub use session::{Expect, RuleSessionParams, SessionError, SessionEvent, SessionOutcome};
pub use transport::{health_check, TransportKind};
pub use trigger::*;
pub use verify::*;

pub type Result<T> = std::result::Result<T, RpcClientError>;

pub const DEFAULT_HTTP_PORT: u16 = 8764;
pub const DEFAULT_WS_PORT: u16 = 8765;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcRequest {
    pub jsonrpc: String,
    pub id: String,
    pub method: String,
    #[serde(default)]
    pub params: Value,
}

impl JsonRpcRequest {
    pub fn new(method: impl Into<String>, params: Value) -> Self {
        Self {
            jsonrpc: "2.0".into(),
            id: Uuid::new_v4().to_string(),
            method: method.into(),
            params,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcResponse {
    pub jsonrpc: String,
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<RpcError>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RpcError {
    pub code: i32,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcNotification {
    pub jsonrpc: String,
    pub method: String,
    pub params: Value,
}

/// Known RPC methods (agent implements these).
pub mod methods {
    pub const GET_STATUS: &str = "get_status";
    pub const INSPECT_DEVICE: &str = "inspect_device";
    pub const APP_LIST: &str = "app_list";
    pub const APP_INFO: &str = "app_info";
    pub const PROCESS_LIST: &str = "process_list";
    pub const TRIGGER_APP: &str = "trigger_app";
    pub const GET_LOGS: &str = "get_logs";
    pub const MODULE_DIAGNOSTICS: &str = "module_diagnostics";
    pub const APPLY_PROFILE: &str = "apply_profile";
    pub const TOGGLE_MOD: &str = "toggle_mod";
    pub const VERIFY: &str = "verify";
    pub const REAPPLY: &str = "reapply";
    pub const COLLECT_LOGS: &str = "collect_logs";
    pub const DEPLOY_RULE: &str = "deploy_rule";
    pub const RESTART_TARGETS: &str = "restart_targets";
    pub const SOFT_RESTART: &str = "soft_restart";
    pub const SCRIPT_VALIDATE: &str = "script_validate";
    pub const SCRIPT_DEPLOY: &str = "script_deploy";
    pub const SCRIPT_LIST: &str = "script_list";
    pub const SCRIPT_ENABLE: &str = "script_enable";
    pub const SCRIPT_DISABLE: &str = "script_disable";
    pub const SCRIPT_REMOVE: &str = "script_remove";
    pub const SCRIPT_RELOAD: &str = "script_reload";
    pub const INSTALL_FRIDA_GADGET: &str = "install_frida_gadget";
    pub const EXEC_SU: &str = "exec_su";
    pub const PING: &str = "ping";
}

/// Known push events (agent → PC).
pub mod events {
    pub const RULE_UPLOADED: &str = "rule_uploaded";
    pub const RELOAD_STARTED: &str = "reload_started";
    pub const HOOK_LOADED: &str = "hook_loaded";
    pub const TARGET_RESTARTED: &str = "target_restarted";
    pub const HOOK_HIT: &str = "hook_hit";
    pub const HOOK_ERROR: &str = "hook_error";
    pub const STATE_CHANGED: &str = "state_changed";
    pub const APPLY_PROGRESS: &str = "apply_progress";
    pub const APPLY_COMPLETED: &str = "apply_completed";
    pub const APPLY_FAILED: &str = "apply_failed";
    pub const BOOT_COMPLETED: &str = "boot_completed";
    pub const SCRIPT_UPLOADED: &str = "script_uploaded";
    pub const SCRIPT_ENABLED: &str = "script_enabled";
    pub const SCRIPT_DISABLED: &str = "script_disabled";
    pub const SCRIPT_RELOAD_STARTED: &str = "script_reload_started";
    pub const SCRIPT_LOADED: &str = "script_loaded";
    pub const SCRIPT_UNLOADED: &str = "script_unloaded";
    pub const SCRIPT_HIT: &str = "script_hit";
    pub const SCRIPT_MESSAGE: &str = "script_message";
    pub const SCRIPT_ERROR: &str = "script_error";
    pub const SESSION_SUCCESS: &str = "session_success";
    pub const SESSION_FAILURE: &str = "session_failure";
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApplyProfileParams {
    pub profile: Profile,
    #[serde(default)]
    pub dry_run: bool,
    #[serde(default)]
    pub only: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToggleModParams {
    pub mod_id: String,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceStatus {
    pub device_id: String,
    pub model: String,
    pub android_version: u32,
    pub agent_version: String,
    #[serde(default)]
    pub lsposed_version: Option<String>,
    pub state: ProfileState,
    /// Agent-side server supervisor health (absent on pre-supervisor agents).
    #[serde(default)]
    pub server_http_alive: Option<bool>,
    #[serde(default)]
    pub server_ws_alive: Option<bool>,
}
