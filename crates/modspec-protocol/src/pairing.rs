//! HTTP pairing types (port 8764) — see docs/protocol.md.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// PC → agent: initiate pairing with a 6-digit code shown on device.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PairRequest {
    pub code: String,
    #[serde(default)]
    pub client_name: Option<String>,
}

/// Agent → PC: user confirmed pairing on phone.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PairConfirm {
    pub request_id: String,
    pub device_id: String,
    pub device_name: String,
    pub model: String,
    pub auth_token: String,
    #[serde(default)]
    pub android_version: Option<u32>,
}

/// Persisted paired device record (wire + storage).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PairedDevice {
    pub id: String,
    pub name: String,
    pub host: String,
    #[serde(default = "default_ws_port")]
    pub ws_port: u16,
    #[serde(default = "default_http_port")]
    pub http_port: u16,
    pub paired_at: DateTime<Utc>,
}

fn default_ws_port() -> u16 {
    crate::DEFAULT_WS_PORT
}

fn default_http_port() -> u16 {
    crate::DEFAULT_HTTP_PORT
}

impl PairedDevice {
    pub fn ws_url(&self) -> String {
        format!("ws://{}:{}/rpc", self.host, self.ws_port)
    }

    pub fn http_base_url(&self) -> String {
        format!("http://{}:{}", self.host, self.http_port)
    }
}
