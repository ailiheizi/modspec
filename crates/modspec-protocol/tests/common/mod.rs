//! Shared loopback fake Agent used by protocol and rule-session tests.
//!
//! These tests exercise the real HTTP JSON-RPC transport against a scripted
//! in-process server; they do NOT require an Android device.
//!
//! Each consuming test binary uses only a subset of these helpers, so the
//! fixture is allowed to carry some unused surface per binary.
#![allow(dead_code)]

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use serde_json::{json, Value};

#[derive(Debug, Clone)]
pub struct CapturedRequest {
    pub method: String,
    pub params: Value,
    pub auth_header: Option<String>,
}

/// How the fake Agent answers the current request.
#[derive(Debug, Clone)]
pub enum FakeResponse {
    /// Successful JSON-RPC result.
    Result(Value),
    /// Plain HTTP error status (e.g. 401) with a short body.
    HttpStatus(u16, String),
    /// JSON-RPC error object.
    JsonRpcError(i32, String),
}

/// A scripted HTTP JSON-RPC agent on a loopback port.
pub struct FakeAgent {
    pub port: u16,
    requests: Arc<Mutex<Vec<CapturedRequest>>>,
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl FakeAgent {
    /// Start the server. `handler` is invoked for every request with the request
    /// and the zero-based call index (per method).
    pub fn start<F>(handler: F) -> Self
    where
        F: Fn(&CapturedRequest, usize) -> FakeResponse + Send + Sync + 'static,
    {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind fake agent");
        listener
            .set_nonblocking(true)
            .expect("set nonblocking fake agent");
        let port = listener.local_addr().unwrap().port();
        let stop = Arc::new(AtomicBool::new(false));
        let stop2 = Arc::clone(&stop);
        let requests = Arc::new(Mutex::new(Vec::new()));
        let requests2 = Arc::clone(&requests);
        let handle = thread::spawn(move || {
            serve(listener, &stop2, &requests2, &handler);
        });
        Self {
            port,
            requests,
            stop,
            handle: Some(handle),
        }
    }

    pub fn captured(&self) -> Vec<CapturedRequest> {
        self.requests.lock().unwrap().clone()
    }

    pub fn captured_method(&self, method: &str) -> Vec<CapturedRequest> {
        self.captured()
            .into_iter()
            .filter(|r| r.method == method)
            .collect()
    }

    /// Stop the server and return every captured request in order.
    pub fn join(mut self) -> Vec<CapturedRequest> {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
        self.captured()
    }
}

impl Drop for FakeAgent {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn serve<F>(
    listener: TcpListener,
    stop: &AtomicBool,
    requests: &Mutex<Vec<CapturedRequest>>,
    handler: &F,
) where
    F: Fn(&CapturedRequest, usize) -> FakeResponse,
{
    while !stop.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((mut stream, _)) => {
                // The listener is non-blocking; on macOS/BSD the accepted socket
                // INHERITS O_NONBLOCK, which would make read() return WouldBlock
                // before the request bytes arrive. Force blocking I/O here.
                let _ = stream.set_nonblocking(false);
                let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
                if let Some((id, body)) = read_http_request(&mut stream) {
                    let captured = CapturedRequest {
                        method: body["method"].as_str().unwrap_or("").to_string(),
                        params: body.get("params").cloned().unwrap_or(Value::Null),
                        auth_header: body
                            .get("_captured_auth")
                            .and_then(Value::as_str)
                            .map(str::to_string),
                    };
                    let index = {
                        let mut guard = requests.lock().unwrap();
                        guard.push(captured.clone());
                        guard.len() - 1
                    };
                    let response = handler(&captured, index);
                    write_http_response(&mut stream, id, &response);
                }
            }
            Err(_) => {
                // Transient errors (e.g. EMFILE under parallel test load) must
                // not kill the server thread and close the listener.
                thread::sleep(Duration::from_millis(5));
            }
        }
    }
}

/// Read one HTTP request; returns the JSON-RPC `id` and parsed body.
/// The `Authorization` header is folded into the body as `_captured_auth`.
fn read_http_request(stream: &mut TcpStream) -> Option<(Option<Value>, Value)> {
    let mut bytes = Vec::new();
    let mut chunk = [0_u8; 8192];
    let header_end;
    loop {
        let read = stream.read(&mut chunk).ok()?;
        if read == 0 {
            return None;
        }
        bytes.extend_from_slice(&chunk[..read]);
        if let Some(index) = bytes.windows(4).position(|window| window == b"\r\n\r\n") {
            header_end = index + 4;
            break;
        }
    }
    let header_text = String::from_utf8_lossy(&bytes[..header_end]).to_string();
    let auth_header = header_text.lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        name.eq_ignore_ascii_case("authorization")
            .then_some(value.trim().to_string())
    });
    let content_length: usize = header_text
        .lines()
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("content-length")
                .then_some(value.trim())
        })?
        .parse()
        .ok()?;
    while bytes.len() < header_end + content_length {
        let read = stream.read(&mut chunk).ok()?;
        if read == 0 {
            return None;
        }
        bytes.extend_from_slice(&chunk[..read]);
    }
    let mut body: Value =
        serde_json::from_slice(&bytes[header_end..header_end + content_length]).ok()?;
    if let Some(map) = body.as_object_mut() {
        map.insert(
            "_captured_auth".into(),
            auth_header.map(Value::String).unwrap_or(Value::Null),
        );
    }
    let id = body.get("id").cloned();
    Some((id, body))
}

fn write_http_response(stream: &mut TcpStream, id: Option<Value>, response: &FakeResponse) {
    let id = id.unwrap_or(Value::Null);
    let (status, payload) = match response {
        FakeResponse::Result(result) => {
            let body = json!({ "jsonrpc": "2.0", "id": id, "result": result });
            ("200 OK".to_string(), serde_json::to_vec(&body).unwrap())
        }
        FakeResponse::JsonRpcError(code, message) => {
            let body = json!({ "jsonrpc": "2.0", "id": id, "error": { "code": code, "message": message } });
            ("200 OK".to_string(), serde_json::to_vec(&body).unwrap())
        }
        FakeResponse::HttpStatus(code, message) => {
            let reason = match code {
                401 => "Unauthorized",
                _ => "Error",
            };
            (format!("{code} {reason}"), message.clone().into_bytes())
        }
    };
    let head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        payload.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(&payload);
}

/// Default deploy response for session tests.
pub fn ok_deploy(generation: i64) -> Value {
    json!({
        "rule_id": "test/smoke",
        "stored": true,
        "publish_mode": "remote_file",
        "generation": generation,
        "scope_status": "applied",
        "scope_packages": ["com.example.target"],
        "message": "scope appended and generation published"
    })
}

/// A structured hook log entry JSON.
pub fn hook_entry(event: &str, generation: i64, event_id: i64, message: &str) -> Value {
    json!({
        "event_id": event_id,
        "timestamp_ms": 1_700_000_000_000_i64 + event_id,
        "level": "I",
        "tag": "ModspecRuleEngine",
        "event": event,
        "generation": generation,
        "rule_id": "test/smoke",
        "package": "com.example.target",
        "message": message,
        "raw": "logcat-line"
    })
}

/// Loopback HTTP server for `/health` preflight tests.
///
/// Can be started "hanging": it accepts connections but never answers, which
/// mimics a stale `adb forward` whose local port still accepts but never
/// completes (the real-device hang). [`FakeHealthServer::heal`] makes it
/// answer normally again, letting a fake forwarder/bootstrapper simulate a
/// repaired transport.
pub struct FakeHealthServer {
    pub port: u16,
    stop: Arc<AtomicBool>,
    hang: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl FakeHealthServer {
    pub fn start(hang: bool) -> Self {
        Self::start_with_flag(Arc::new(AtomicBool::new(hang)))
    }

    /// Start with a caller-supplied hang flag (shared with fakes that heal it).
    pub fn start_with_flag(hang: Arc<AtomicBool>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind fake health server");
        listener
            .set_nonblocking(true)
            .expect("set nonblocking fake health server");
        let port = listener.local_addr().unwrap().port();
        let stop = Arc::new(AtomicBool::new(false));
        let handle = thread::spawn({
            let stop = Arc::clone(&stop);
            let hang_flag = Arc::clone(&hang);
            move || {
                while !stop.load(Ordering::SeqCst) {
                    match listener.accept() {
                        Ok((mut stream, _)) => {
                            let hang_flag = Arc::clone(&hang_flag);
                            thread::spawn(move || {
                                if hang_flag.load(Ordering::SeqCst) {
                                    // Long enough to outlast the probe timeout,
                                    // short enough that a healed server frees up.
                                    thread::sleep(Duration::from_secs(10));
                                }
                                let _ = stream.write_all(
                                    b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok",
                                );
                            });
                        }
                        Err(_) => thread::sleep(Duration::from_millis(5)),
                    }
                }
            }
        });
        Self {
            port,
            stop,
            hang,
            handle: Some(handle),
        }
    }

    /// Stop hanging: subsequent connections are answered normally.
    pub fn heal(&self) {
        self.hang.store(false, Ordering::SeqCst);
    }

    /// Whether the server is currently in hanging mode.
    pub fn is_hanging(&self) -> bool {
        self.hang.load(Ordering::SeqCst)
    }
}

impl Drop for FakeHealthServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

/// Scripted [`modspec_protocol::connection::ForwardManager`]: records calls,
/// optionally fails, and can heal the paired [`FakeHealthServer`].
#[derive(Clone)]
pub struct FakeForwarder {
    pub calls: Arc<Mutex<Vec<String>>>,
    heal_hang: Arc<AtomicBool>,
    fail_forward: Arc<AtomicBool>,
    fail_bootstrap: Arc<AtomicBool>,
    heal_on_forward: Arc<AtomicBool>,
}

impl FakeForwarder {
    pub fn new(health: &FakeHealthServer) -> Self {
        Self {
            calls: Arc::new(Mutex::new(Vec::new())),
            heal_hang: Arc::clone(&health.hang),
            fail_forward: Arc::new(AtomicBool::new(false)),
            fail_bootstrap: Arc::new(AtomicBool::new(false)),
            heal_on_forward: Arc::new(AtomicBool::new(true)),
        }
    }

    /// Build from a shared hang flag (used with `FakeHealthServer::start_with_flag`).
    pub fn new_shared(hang: &Arc<AtomicBool>) -> Self {
        Self {
            calls: Arc::new(Mutex::new(Vec::new())),
            heal_hang: Arc::clone(hang),
            fail_forward: Arc::new(AtomicBool::new(false)),
            fail_bootstrap: Arc::new(AtomicBool::new(false)),
            heal_on_forward: Arc::new(AtomicBool::new(true)),
        }
    }

    pub fn log(&self) -> Vec<String> {
        self.calls.lock().unwrap().clone()
    }

    pub fn set_fail_forward(&self, fail: bool) {
        self.fail_forward.store(fail, Ordering::SeqCst);
    }

    pub fn set_fail_bootstrap(&self, fail: bool) {
        self.fail_bootstrap.store(fail, Ordering::SeqCst);
    }

    /// When false, `ensure_forward` no longer heals the health server (only
    /// `bootstrap` does), simulating "the agent is really down".
    pub fn set_heal_on_forward(&self, heal: bool) {
        self.heal_on_forward.store(heal, Ordering::SeqCst);
    }
}

impl modspec_protocol::connection::ForwardManager for FakeForwarder {
    fn describe(&self) -> String {
        "fake-forwarder".into()
    }

    fn ensure_forward(&self, _local: u16, _remote: u16) -> std::result::Result<(), String> {
        self.calls.lock().unwrap().push("ensure_forward".into());
        if self.fail_forward.load(Ordering::SeqCst) {
            return Err("adb forward failed".into());
        }
        if self.heal_on_forward.load(Ordering::SeqCst) {
            self.heal_hang.store(false, Ordering::SeqCst);
        }
        Ok(())
    }

    fn bootstrap(&self) -> std::result::Result<(), String> {
        self.calls.lock().unwrap().push("bootstrap".into());
        if self.fail_bootstrap.load(Ordering::SeqCst) {
            return Err("am start failed".into());
        }
        self.heal_hang.store(false, Ordering::SeqCst);
        Ok(())
    }

    fn adb_hint(&self) -> Option<String> {
        Some("adb devices: R58M2ABCD=device".into())
    }
}
