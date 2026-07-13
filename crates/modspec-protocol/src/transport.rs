//! HTTP and WebSocket JSON-RPC transports.

use serde_json::Value;
use thiserror::Error;

use crate::{JsonRpcRequest, JsonRpcResponse, RpcClientError, Result};

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

pub async fn call_http(http_url: &str, request: &JsonRpcRequest) -> Result<Value> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;

    let response = client
        .post(http_url)
        .header("Content-Type", "application/json")
        .json(request)
        .send()
        .await
        .map_err(|e| RpcClientError::Transport(format!("HTTP request failed: {e}")))?;

    if !response.status().is_success() {
        return Err(RpcClientError::Transport(format!(
            "HTTP {} from {}",
            response.status(),
            http_url
        )));
    }

    let body = response
        .text()
        .await
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;

    parse_result(&body)
}

pub async fn call_websocket(ws_url: &str, request: &JsonRpcRequest) -> Result<Value> {
    use futures_util::{SinkExt, StreamExt};
    use tokio_tungstenite::{connect_async, tungstenite::Message};

    let (mut ws, _) = connect_async(ws_url)
        .await
        .map_err(|e| RpcClientError::Transport(format!("WebSocket connect failed: {e}")))?;

    let payload = serde_json::to_string(request)?;
    ws.send(Message::Text(payload.into()))
        .await
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;

    let msg = ws
        .next()
        .await
        .ok_or_else(|| RpcClientError::Transport("WebSocket closed without response".into()))?
        .map_err(|e| RpcClientError::Transport(e.to_string()))?;

    let body = match msg {
        Message::Text(text) => text.to_string(),
        Message::Binary(bin) => String::from_utf8(bin.to_vec())
            .map_err(|e| RpcClientError::Transport(e.to_string()))?,
        _ => return Err(RpcClientError::Transport("unexpected WebSocket frame".into())),
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
