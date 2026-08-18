//! JSON-RPC client — HTTP (primary) and WebSocket transports.

use std::time::Duration;

use serde::de::DeserializeOwned;
use serde_json::Value;
use thiserror::Error;

use crate::{
    methods,
    transport::{self, TransportKind},
    AppInfoParams, AppInfoResponse, AppListParams, AppListResponse, ApplyProfileParams,
    ApplyProfileResponse, CollectLogsParams, CollectLogsResponse, DeployRuleParams,
    DeployRuleResponse, DeviceInspection, DeviceStatus, GetLogsParams, GetLogsResponse,
    InspectDeviceParams, InstallFridaGadgetParams, InstallFridaGadgetResponse, JsonRpcRequest,
    JsonRpcResponse, ModuleDiagnostics, PingResponse, ProcessListParams, ProcessListResponse,
    ReapplyParams, ReapplyResponse, RestartTargetsParams, RestartTargetsResponse,
    ScriptDeployParams, ScriptDeployResponse, ScriptDisableParams, ScriptDisableResponse,
    ScriptEnableParams, ScriptEnableResponse, ScriptListParams, ScriptListResponse,
    ScriptReloadParams, ScriptReloadResponse, ScriptRemoveParams, ScriptRemoveResponse,
    ScriptValidateParams, ScriptValidateResponse, SoftRestartParams, SoftRestartResponse,
    TriggerAppParams, TriggerAppResponse, VerifyParams, VerifyResponse,
};

#[derive(Debug, Error)]
pub enum RpcClientError {
    #[error("not connected to {url}")]
    NotConnected { url: String },

    #[error("offline mode — would send RPC {method}")]
    Offline { method: String },

    #[error("rpc error {code}: {message}")]
    Rpc { code: i32, message: String },

    #[error("unauthorized: {message} — re-pair with `modspec pair scan`")]
    Unauthorized { message: String },

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
    auth_token: Option<String>,
}

impl RpcClient {
    pub fn new(host: &str, http_port: u16, ws_port: u16) -> Self {
        Self {
            http_url: format!("http://{host}:{http_port}/rpc"),
            ws_url: format!("ws://{host}:{ws_port}/rpc"),
            transport: TransportKind::Http,
            connected: false,
            offline: false,
            auth_token: None,
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
            auth_token: None,
        }
    }

    pub fn with_transport(mut self, transport: TransportKind) -> Self {
        self.transport = transport;
        self
    }

    pub fn with_auth_token(mut self, auth_token: Option<String>) -> Self {
        self.auth_token = auth_token;
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

    pub fn auth_token(&self) -> Option<&str> {
        self.auth_token.as_deref()
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

    /// Authorized ping with a short, explicit timeout (connection-manager probe).
    pub async fn ping_with_timeout(&self, timeout: Duration) -> Result<PingResponse> {
        self.call_with_timeout(methods::PING, Value::Null, timeout)
            .await
    }

    pub async fn get_status(&self) -> Result<DeviceStatus> {
        self.call(methods::GET_STATUS, Value::Null).await
    }

    pub async fn inspect_device(&self, params: &InspectDeviceParams) -> Result<DeviceInspection> {
        self.call(methods::INSPECT_DEVICE, serde_json::to_value(params)?)
            .await
    }

    pub async fn apply_profile(&self, params: &ApplyProfileParams) -> Result<ApplyProfileResponse> {
        let params = serde_json::to_value(params)?;
        self.call(methods::APPLY_PROFILE, params).await
    }

    pub async fn deploy_rule(&self, params: &DeployRuleParams) -> Result<DeployRuleResponse> {
        self.call(methods::DEPLOY_RULE, serde_json::to_value(params)?)
            .await
    }

    pub async fn restart_targets(
        &self,
        params: &RestartTargetsParams,
    ) -> Result<RestartTargetsResponse> {
        self.call(methods::RESTART_TARGETS, serde_json::to_value(params)?)
            .await
    }

    pub async fn collect_logs(&self, params: &CollectLogsParams) -> Result<CollectLogsResponse> {
        self.call(methods::COLLECT_LOGS, serde_json::to_value(params)?)
            .await
    }

    /// Agent-side drift report for a profile (read-only).
    pub async fn verify(&self, params: &VerifyParams) -> Result<VerifyResponse> {
        self.call(methods::VERIFY, serde_json::to_value(params)?)
            .await
    }

    pub async fn reapply(&self, params: &ReapplyParams) -> Result<ReapplyResponse> {
        self.call(methods::REAPPLY, serde_json::to_value(params)?)
            .await
    }

    pub async fn soft_restart(&self, params: &SoftRestartParams) -> Result<SoftRestartResponse> {
        self.call(methods::SOFT_RESTART, serde_json::to_value(params)?)
            .await
    }

    pub async fn app_list(&self, params: &AppListParams) -> Result<AppListResponse> {
        self.call(methods::APP_LIST, serde_json::to_value(params)?)
            .await
    }

    pub async fn app_info(&self, params: &AppInfoParams) -> Result<AppInfoResponse> {
        self.call(methods::APP_INFO, serde_json::to_value(params)?)
            .await
    }

    pub async fn process_list(&self, params: &ProcessListParams) -> Result<ProcessListResponse> {
        self.call(methods::PROCESS_LIST, serde_json::to_value(params)?)
            .await
    }

    pub async fn trigger_app(&self, params: &TriggerAppParams) -> Result<TriggerAppResponse> {
        self.call(methods::TRIGGER_APP, serde_json::to_value(params)?)
            .await
    }

    pub async fn get_logs(&self, params: &GetLogsParams) -> Result<GetLogsResponse> {
        self.call(methods::GET_LOGS, serde_json::to_value(params)?)
            .await
    }

    pub async fn module_diagnostics(&self) -> Result<ModuleDiagnostics> {
        self.call(methods::MODULE_DIAGNOSTICS, Value::Null).await
    }

    pub async fn script_validate(
        &self,
        params: &ScriptValidateParams,
    ) -> Result<ScriptValidateResponse> {
        self.call(methods::SCRIPT_VALIDATE, serde_json::to_value(params)?)
            .await
    }

    pub async fn script_deploy(&self, params: &ScriptDeployParams) -> Result<ScriptDeployResponse> {
        self.call(methods::SCRIPT_DEPLOY, serde_json::to_value(params)?)
            .await
    }

    pub async fn script_list(&self, params: &ScriptListParams) -> Result<ScriptListResponse> {
        self.call(methods::SCRIPT_LIST, serde_json::to_value(params)?)
            .await
    }

    pub async fn script_enable(&self, params: &ScriptEnableParams) -> Result<ScriptEnableResponse> {
        self.call(methods::SCRIPT_ENABLE, serde_json::to_value(params)?)
            .await
    }

    pub async fn script_disable(
        &self,
        params: &ScriptDisableParams,
    ) -> Result<ScriptDisableResponse> {
        self.call(methods::SCRIPT_DISABLE, serde_json::to_value(params)?)
            .await
    }

    pub async fn script_remove(&self, params: &ScriptRemoveParams) -> Result<ScriptRemoveResponse> {
        self.call(methods::SCRIPT_REMOVE, serde_json::to_value(params)?)
            .await
    }

    pub async fn script_reload(&self, params: &ScriptReloadParams) -> Result<ScriptReloadResponse> {
        self.call(methods::SCRIPT_RELOAD, serde_json::to_value(params)?)
            .await
    }

    pub async fn install_frida_gadget(
        &self,
        params: &InstallFridaGadgetParams,
    ) -> Result<InstallFridaGadgetResponse> {
        self.call(methods::INSTALL_FRIDA_GADGET, serde_json::to_value(params)?)
            .await
    }

    pub fn build_request(&self, method: &str, params: Value) -> JsonRpcRequest {
        JsonRpcRequest::new(method, params)
    }

    async fn call<T: DeserializeOwned>(&self, method: &str, params: Value) -> Result<T> {
        self.call_with_timeout(method, params, crate::transport::DEFAULT_RPC_TIMEOUT)
            .await
    }

    async fn call_with_timeout<T: DeserializeOwned>(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<T> {
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
            TransportKind::Http => {
                transport::call_http_with_timeout(
                    &self.http_url,
                    self.auth_token.as_deref(),
                    &request,
                    timeout,
                )
                .await?
            }
            TransportKind::WebSocket => {
                transport::call_websocket(&self.ws_url, self.auth_token.as_deref(), &request)
                    .await?
            }
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
        let profile: Profile = r#"
mspec_version = "1"
[meta]
id = "test"
name = "Test"
"#
        .parse()
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
