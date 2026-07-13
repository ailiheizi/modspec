//! JSON-RPC 2.0 message types for modspec-agent ↔ modspec-cli.
//! Transport: HTTP POST :8764/rpc (primary), WebSocket :8765/rpc (optional).

use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

use modspec_core::{Profile, ProfileState};

pub mod apply;
pub mod client;
pub mod pairing;
pub mod transport;

pub use apply::*;
pub use client::{RpcClient, RpcClientError};
pub use pairing::*;
pub use transport::{health_check, TransportKind};

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
    pub const APPLY_PROFILE: &str = "apply_profile";
    pub const TOGGLE_MOD: &str = "toggle_mod";
    pub const VERIFY: &str = "verify";
    pub const REAPPLY: &str = "reapply";
    pub const COLLECT_LOGS: &str = "collect_logs";
    pub const PING: &str = "ping";
}

/// Known push events (agent → PC).
pub mod events {
    pub const STATE_CHANGED: &str = "state_changed";
    pub const APPLY_PROGRESS: &str = "apply_progress";
    pub const APPLY_COMPLETED: &str = "apply_completed";
    pub const APPLY_FAILED: &str = "apply_failed";
    pub const BOOT_COMPLETED: &str = "boot_completed";
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
    pub lsposed_version: Option<String>,
    pub state: ProfileState,
}
