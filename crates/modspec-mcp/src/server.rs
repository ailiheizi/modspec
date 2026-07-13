use std::io::{self, BufRead, Write};

use anyhow::{Context, Result};
use serde_json::{json, Value};
use tracing::info;

use crate::tools::{call_tool, tool_definitions};

/// Run MCP server on stdio (JSON-RPC lines). Logs go to stderr.
pub async fn serve_stdio() -> Result<()> {
    info!("modspec MCP server starting on stdio");
    let stdin = io::stdin();
    let mut stdout = io::stdout();

    for line in stdin.lock().lines() {
        let line = line.context("read stdin")?;
        if line.trim().is_empty() {
            continue;
        }
        let request: Value = serde_json::from_str(&line).context("parse MCP request")?;
        if let Some(response) = handle_request(&request).await {
            writeln!(stdout, "{}", serde_json::to_string(&response)?)?;
            stdout.flush()?;
        }
    }
    Ok(())
}

async fn handle_request(request: &Value) -> Option<Value> {
    let id = request.get("id").cloned();
    let method = request.get("method").and_then(|m| m.as_str())?;

    // Notifications have no id — no response
    if id.is_none() && method.starts_with("notifications/") {
        return None;
    }

    let result = match method {
        "initialize" => Ok(initialize_result()),
        "tools/list" => Ok(json!({ "tools": tool_definitions() })),
        "tools/call" => {
            let params = request.get("params").cloned().unwrap_or(json!({}));
            let name = params
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or_default();
            let arguments = params.get("arguments").cloned().unwrap_or(json!({}));
            match call_tool(name, &arguments).await {
                Ok(text) => Ok(json!({
                    "content": [{ "type": "text", "text": text }],
                    "isError": false
                })),
                Err(e) => Ok(json!({
                    "content": [{ "type": "text", "text": e.to_string() }],
                    "isError": true
                })),
            }
        }
        "ping" => Ok(json!({})),
        _ => Err(anyhow::anyhow!("method not found: {method}")),
    };

    match result {
        Ok(result) => Some(json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": result
        })),
        Err(e) => Some(json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": {
                "code": -32601,
                "message": e.to_string()
            }
        })),
    }
}

fn initialize_result() -> Value {
    json!({
        "protocolVersion": "2024-11-05",
        "capabilities": {
            "tools": {}
        },
        "serverInfo": {
            "name": "modspec",
            "version": env!("CARGO_PKG_VERSION")
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn initialize_returns_capabilities() {
        let req = json!({ "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {} });
        let resp = handle_request(&req).await.unwrap();
        assert!(resp["result"]["serverInfo"]["name"].as_str().unwrap() == "modspec");
    }

    #[tokio::test]
    async fn tools_list_not_empty() {
        let req = json!({ "jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {} });
        let resp = handle_request(&req).await.unwrap();
        let tools = resp["result"]["tools"].as_array().unwrap();
        assert!(!tools.is_empty());
    }
}
