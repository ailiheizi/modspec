//! JSON-RPC client — HTTP (primary) and WebSocket transports.

use serde::de::DeserializeOwned;
use serde_json::Value;
use thiserror::Error;

use crate::{
    ApplyProfileParams, ApplyProfileResponse, DeviceStatus, JsonRpcRequest, JsonRpcResponse,
    PingResponse, methods, transport::{self, TransportKind},
};

#[derive(Debug, Error)]
pub enum RpcClientError {
    #[error("not connected to {url}")]
    NotConnected { url: String },

    #[error("offline mode — would send RPC {method}")]
    Offline { method: String },

    #[error("rpc error {code}: {message}")]
    Rpc { code: i32, message: String },

    #[error("transport: {0}")]
    Transport(String),

    #[error("decode: {0}")]
    Decode(#[from] serde_json::Error),
}

pub type Result<T> = std::result::Result<T, RpcClientError>;

#[derive(Debug, Clone)]
pub struct RpcClient {
    http_url: String,
    ws_url: String,
    transport: TransportKind,
    connected: bool,
    offline: bool,
}

impl RpcClient {
    pub fn new(host: &str, http_port: u16, ws_port: u16) -> Self {
        Self {
            http_url: format!("http://{host}:{http_port}/rpc"),
            ws_url: format!("ws://{host}:{ws_port}/rpc"),
            transport: TransportKind::Http,
            connected: false,
            offline: false,
        }
    }

    pub fn from_device(host: &str, http_port: u16, ws_port: u16) -> Self {
        Self::new(host, http_port, ws_port)
    }

    pub fn from_ws_url(url: impl Into<String>) -> Self {
        let url = url.into();
        Self {
            http_url: url.replace("ws://", "http://").replace(":8765", ":8764"),
            ws_url: url,
            transport: TransportKind::Http,
            connected: false,
            offline: false,
        }
    }

    pub fn with_transport(mut self, transport: TransportKind) -> Self {
        self.transport = transport;
        self
    }

    pub fn http_url(&self) -> &str {
        &self.http_url
    }

    pub fn ws_url(&self) -> &str {
        &self.ws_url
    }

    pub fn url(&self) -> &str {
        match self.transport {
            TransportKind::Http => &self.http_url,
            TransportKind::WebSocket => &self.ws_url,
        }
    }

    pub fn set_offline(&mut self, offline: bool) {
        self.offline = offline;
    }

    pub fn is_offline(&self) -> bool {
        self.offline
    }

    /// Mark client ready (HTTP is stateless; WebSocket validates on first call).
    pub async fn connect(&mut self) -> Result<()> {
        if self.offline {
            return Err(RpcClientError::Offline {
                method: "connect".into(),
            });
        }
        self.connected = true;
        Ok(())
    }

    pub fn disconnect(&mut self) {
        self.connected = false;
    }

    pub async fn ping(&self) -> Result<PingResponse> {
        self.call(methods::PING, Value::Null).await
    }

    pub async fn get_status(&self) -> Result<DeviceStatus> {
        self.call(methods::GET_STATUS, Value::Null).await
    }

    pub async fn apply_profile(&self, params: &ApplyProfileParams) -> Result<ApplyProfileResponse> {
        let params = serde_json::to_value(params)?;
        self.call(methods::APPLY_PROFILE, params).await
    }

    pub fn build_request(&self, method: &str, params: Value) -> JsonRpcRequest {
        JsonRpcRequest::new(method, params)
    }

    async fn call<T: DeserializeOwned>(&self, method: &str, params: Value) -> Result<T> {
        if self.offline {
            return Err(RpcClientError::Offline {
                method: method.into(),
            });
        }
        if !self.connected {
            return Err(RpcClientError::NotConnected {
                url: self.url().to_string(),
            });
        }

        let request = self.build_request(method, params);
        let result = match self.transport {
            TransportKind::Http => transport::call_http(&self.http_url, &request).await?,
            TransportKind::WebSocket => transport::call_websocket(&self.ws_url, &request).await?,
        };
        Ok(serde_json::from_value(result)?)
    }

    pub fn parse_response<T: DeserializeOwned>(body: &str) -> Result<T> {
        let envelope: JsonRpcResponse = serde_json::from_str(body)?;
        if let Some(err) = envelope.error {
            return Err(RpcClientError::Rpc {
                code: err.code,
                message: err.message,
            });
        }
        let result = envelope
            .result
            .ok_or_else(|| RpcClientError::Transport("empty RPC result".into()))?;
        Ok(serde_json::from_value(result)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ApplyProfileParams;
    use modspec_core::Profile;

    #[tokio::test]
    async fn offline_mode_returns_error() {
        let mut client = RpcClient::new("127.0.0.1", 8764, 8765);
        client.set_offline(true);
        let err = client.ping().await.unwrap_err();
        assert!(matches!(err, RpcClientError::Offline { .. }));
    }

    #[tokio::test]
    async fn not_connected_returns_error() {
        let client = RpcClient::new("127.0.0.1", 8764, 8765);
        let err = client.get_status().await.unwrap_err();
        assert!(matches!(err, RpcClientError::NotConnected { .. }));
    }

    #[tokio::test]
    async fn build_request_has_jsonrpc_fields() {
        let client = RpcClient::new("192.168.1.10", 8764, 8765);
        let req = client.build_request(methods::PING, Value::Null);
        assert_eq!(req.jsonrpc, "2.0");
        assert_eq!(req.method, methods::PING);
        assert!(!req.id.is_empty());
    }

    #[test]
    fn parse_response_ok() {
        let body = r#"{"jsonrpc":"2.0","id":"1","result":{"pong":true}}"#;
        let pong: PingResponse = RpcClient::parse_response(body).unwrap();
        assert!(pong.pong);
    }

    #[test]
    fn parse_response_rpc_error() {
        let body = r#"{"jsonrpc":"2.0","id":"1","error":{"code":-32601,"message":"not found"}}"#;
        let err = RpcClient::parse_response::<PingResponse>(body).unwrap_err();
        assert!(matches!(err, RpcClientError::Rpc { code: -32601, .. }));
    }

    #[test]
    fn apply_request_serializes() {
        let profile = Profile::from_str(
            r#"
mspec_version = "1"
[meta]
id = "test"
name = "Test"
"#,
        )
        .unwrap();
        let params = ApplyProfileParams {
            profile,
            dry_run: true,
            only: vec![],
        };
        let client = RpcClient::new("10.0.0.1", 8764, 8765);
        let req = client.build_request(
            methods::APPLY_PROFILE,
            serde_json::to_value(&params).unwrap(),
        );
        assert_eq!(req.method, methods::APPLY_PROFILE);
    }
}
