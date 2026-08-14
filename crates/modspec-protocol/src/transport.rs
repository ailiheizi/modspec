//! HTTP and WebSocket JSON-RPC transports.

use std::time::Duration;

use serde_json::Value;
use thiserror::Error;

use crate::{JsonRpcRequest, JsonRpcResponse, Result, RpcClientError};

/// Default per-call RPC timeout. Normal commands layer a short health
/// preflight on top (see `crate::connection`), so this only bounds the actual
/// RPC exchange after the connection is known to be healthy.
pub const DEFAULT_RPC_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TransportKind {
    #[default]
    /// POST `http://host:8764/rpc` (NanoHTTPD on agent)
    Http,
    /// WebSocket `ws://host:8765/rpc`
    WebSocket,
}

#[derive(Debug, Error)]
pub enum TransportError {
    #[error("http: {0}")]
    Http(String),
    #[error("websocket: {0}")]
    WebSocket(String),
}

impl From<TransportError> for RpcClientError {
    fn from(value: TransportError) -> Self {
        RpcClientError::Transport(value.to_string())
    }
}

pub async fn call_http(
    http_url: &str,
    auth_token: Option<&str>,
    request: &JsonRpcRequest,
) -> Result<Value> {
    call_http_with_timeout(http_url, auth_token, request, DEFAULT_RPC_TIMEOUT).await
}

/// Same as [`call_http`] with an explicit timeout (used by the connection
/// manager's short authorized probe).
pub async fn call_http_with_timeout(
    http_url: &str,
    auth_token: Option<&str>,
    request: &JsonRpcRequest,
    timeout: Duration,
) -> Result<Value> {
    // RPC is always loopback (ADB forward) — never route through an HTTP proxy,
    // otherwise a local proxy (e.g. 127.0.0.1:7890) can 502/race the connection.
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(timeout)
        .build()
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;

    let mut request_builder = client
        .post(http_url)
        .header("Content-Type", "application/json")
        .json(request);
    if let Some(token) = auth_token {
        request_builder = request_builder.bearer_auth(token);
    }
    let response = request_builder
        .send()
        .await
        .map_err(|e| RpcClientError::Transport(format!("HTTP request failed: {e}")))?;

    let status = response.status();
    if status == reqwest::StatusCode::UNAUTHORIZED || status == reqwest::StatusCode::FORBIDDEN {
        return Err(RpcClientError::Unauthorized {
            message: format!("HTTP {status} from {http_url}"),
        });
    }
    if !status.is_success() {
        return Err(RpcClientError::Transport(format!(
            "HTTP {status} from {http_url}"
        )));
    }

    let body = response
        .text()
        .await
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;

    parse_result(&body)
}

pub async fn call_websocket(
    ws_url: &str,
    auth_token: Option<&str>,
    request: &JsonRpcRequest,
) -> Result<Value> {
    use futures_util::{SinkExt, StreamExt};
    use tokio_tungstenite::{
        connect_async, tungstenite::client::IntoClientRequest, tungstenite::Message,
    };

    let mut connect_request = ws_url
        .into_client_request()
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;
    if let Some(token) = auth_token {
        connect_request.headers_mut().insert(
            "Authorization",
            format!("Bearer {token}")
                .parse()
                .map_err(|e| RpcClientError::Transport(format!("invalid auth token: {e}")))?,
        );
    }
    let (mut ws, _) = connect_async(connect_request)
        .await
        .map_err(|e| RpcClientError::Transport(format!("WebSocket connect failed: {e}")))?;

    let payload = serde_json::to_string(request)?;
    ws.send(Message::Text(payload))
        .await
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;

    let msg = ws
        .next()
        .await
        .ok_or_else(|| RpcClientError::Transport("WebSocket closed without response".into()))?
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;

    let body = match msg {
        Message::Text(text) => text.to_string(),
        Message::Binary(bin) => {
            String::from_utf8(bin.to_vec()).map_err(|e| RpcClientError::Transport(e.to_string()))?
        }
        _ => {
            return Err(RpcClientError::Transport(
                "unexpected WebSocket frame".into(),
            ))
        }
    };

    parse_result(&body)
}

fn parse_result(body: &str) -> Result<Value> {
    let envelope: JsonRpcResponse = serde_json::from_str(body)?;
    if let Some(err) = envelope.error {
        return Err(RpcClientError::Rpc {
            code: err.code,
            message: err.message,
        });
    }
    envelope
        .result
        .ok_or_else(|| RpcClientError::Transport("empty RPC result".into()))
}

pub async fn health_check(base_host: &str, http_port: u16) -> Result<bool> {
    let url = format!("http://{base_host}:{http_port}/health");
    let client = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;
    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;
    Ok(response.status().is_success())
}
