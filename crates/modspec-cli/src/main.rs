mod commands;

use anyhow::Result;
use clap::{Parser, Subcommand};
use tracing_subscriber::EnvFilter;

#[derive(Parser)]
#[command(
    name = "modspec",
    about = "LSPosed profile & rule orchestration CLI",
    version
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
pub enum Commands {
    /// Parse and validate a profile or rule file locally
    Validate {
        /// Path to .mspec.toml or .rule.toml
        path: String,
    },
    /// Show profile or rule file as JSON (for AI / debugging)
    Show { path: String },
    /// List entries in community index
    Community {
        #[command(subcommand)]
        action: CommunityAction,
    },
    /// Pair with a device running modspec-agent
    Pair {
        #[command(subcommand)]
        action: PairAction,
    },
    /// Device management (list, status, inspect, logs, diagnostics)
    Device {
        #[command(subcommand)]
        action: DeviceAction,
    },
    /// Installed-app inventory and lifecycle (list, info, restart, trigger)
    App {
        #[command(subcommand)]
        action: AppAction,
    },
    /// Running-process inventory
    Process {
        #[command(subcommand)]
        action: ProcessAction,
    },
    /// Typed ADB transport helpers (discovery, forward, install, pull, ui-tree)
    Adb {
        #[command(subcommand)]
        action: AdbAction,
    },
    /// Ensure a reliable connection to a paired device (preflight → repair → bootstrap)
    Connect {
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward rebuild (default: the single online device)
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Preflight timeout in seconds
        #[arg(long, default_value_t = 3)]
        timeout: u64,
        /// Forward rebuild retries before bootstrap
        #[arg(long, default_value_t = 2)]
        retries: u32,
    },
    /// Profile management (validate, apply, diff)
    Profile {
        #[command(subcommand)]
        action: ProfileAction,
    },
    /// Rule library commands
    Rule {
        #[command(subcommand)]
        action: RuleAction,
    },
    /// Script engine commands (JS/Lua hook packages)
    Script {
        #[command(subcommand)]
        action: ScriptAction,
    },
    /// MCP server for AI assistants (stdio transport)
    Mcp {
        #[command(subcommand)]
        action: McpAction,
    },
}

#[derive(Subcommand)]
pub enum CommunityAction {
    /// Print bundled community index
    Index,
    /// Lint the community index (ids, packages, referenced paths)
    Lint {
        /// Repository root (default: repo root containing `community/index.toml`)
        #[arg(long)]
        root: Option<std::path::PathBuf>,
    },
}

#[derive(Subcommand)]
pub enum PairAction {
    /// Pair using a 6-digit code from the device
    Scan {
        /// Pairing code shown on device
        #[arg(long)]
        code: String,
        /// Device host/IP
        #[arg(long)]
        host: String,
        /// Agent HTTP port (use the adb-forwarded host port when applicable)
        #[arg(long, default_value_t = 8764)]
        port: u16,
        /// Agent WebSocket port stored for later use
        #[arg(long, default_value_t = 8765)]
        ws_port: u16,
        /// Simulate pairing without network I/O
        #[arg(long)]
        offline: bool,
    },
}

#[derive(Subcommand)]
pub enum DeviceAction {
    /// List paired devices
    List,
    /// Query device status via RPC (or print offline)
    Status {
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// Print RPC payload without connecting
        #[arg(long)]
        offline: bool,
    },
    /// Read structured hardware, software and optional installed-app inventory
    Inspect {
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// Include installed package details (the default only returns counts)
        #[arg(long)]
        apps: bool,
        /// Maximum package records returned with --apps
        #[arg(long, default_value_t = 200)]
        app_limit: u32,
        /// Print RPC payload without connecting
        #[arg(long)]
        offline: bool,
    },
    /// Bounded, filtered raw-logcat diagnostics (separate from hook events)
    Logs {
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// Only lines from this package's processes
        #[arg(long)]
        package: Option<String>,
        /// Only lines with this exact logcat tag
        #[arg(long)]
        tag: Option<String>,
        /// Max entries (capped at 1000 by the agent)
        #[arg(long, default_value_t = 200)]
        limit: u32,
        /// Only lines at/after this epoch-millis timestamp
        #[arg(long)]
        since_ms: Option<i64>,
        /// Print RPC payload without connecting
        #[arg(long)]
        offline: bool,
    },
    /// Read-only LSPosed/module diagnostics (framework, scope, rules)
    Diagnostics {
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// Print RPC payload without connecting
        #[arg(long)]
        offline: bool,
    },
}

#[derive(Subcommand)]
pub enum AppAction {
    /// List installed packages (bounded; filter with --scope/--filter)
    List {
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// Package universe: all | system | user
        #[arg(long, default_value = "all", value_parser = ["all", "system", "user"])]
        scope: String,
        /// Max records returned
        #[arg(long, default_value_t = 200)]
        limit: u32,
        /// Case-insensitive substring filter on package names
        #[arg(long)]
        filter: Option<String>,
        /// Print RPC payload without connecting
        #[arg(long)]
        offline: bool,
    },
    /// Structured detail for one installed package
    Info {
        /// Android package name
        package: String,
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// Print RPC payload without connecting
        #[arg(long)]
        offline: bool,
    },
    /// Force-stop a target app and relaunch its launcher (mutating)
    Restart {
        /// Android package name
        package: String,
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
    },
    /// Start an app: launcher by default, or an explicit --component
    Trigger {
        /// Android package name
        package: String,
        /// Explicit component `package/.Class` (used when no launcher exists)
        #[arg(long)]
        component: Option<String>,
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
    },
}

#[derive(Subcommand)]
pub enum ProcessAction {
    /// List running processes (optionally for one package)
    List {
        /// Restrict to one package
        #[arg(long)]
        package: Option<String>,
        /// Device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// Max records returned
        #[arg(long, default_value_t = 200)]
        limit: u32,
        /// Print RPC payload without connecting
        #[arg(long)]
        offline: bool,
    },
}

#[derive(Subcommand)]
pub enum AdbAction {
    /// List devices via `adb devices`
    Devices,
    /// Forward tcp:<local> to tcp:<remote> on the target device
    Forward {
        /// Local host port
        local: u16,
        /// Device port
        remote: u16,
        /// Target serial (default: the single online device)
        #[arg(long)]
        serial: Option<String>,
    },
    /// Remove an existing tcp forward
    ForwardRemove {
        /// Local host port to remove
        local: u16,
        /// Target serial (default: the single online device)
        #[arg(long)]
        serial: Option<String>,
    },
    /// Install (replace) an APK on the target device
    Install {
        /// Path to a .apk file
        apk: String,
        /// Target serial (default: the single online device)
        #[arg(long)]
        serial: Option<String>,
    },
    /// Pull a file from the device
    Pull {
        /// Absolute remote path
        remote: String,
        /// Local destination
        local: String,
        /// Target serial (default: the single online device)
        #[arg(long)]
        serial: Option<String>,
    },
    /// Capture the current UI hierarchy as XML (snapshot; use scrcpy for streaming)
    UiTree {
        /// Target serial (default: the single online device)
        #[arg(long)]
        serial: Option<String>,
    },
}

#[derive(Subcommand)]
pub enum ProfileAction {
    /// Validate a profile file
    Validate { path: String },
    /// Apply profile to a paired device
    Apply {
        /// Path to .mspec.toml
        path: String,
        /// Target device id
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Validate and print plan without applying
        #[arg(long)]
        dry_run: bool,
        /// Print RPC payload without connecting
        #[arg(long)]
        offline: bool,
    },
    /// Compare local profile to last-known state on PC
    Diff {
        /// Path to .mspec.toml
        path: String,
        /// Device id for state file lookup
        #[arg(long)]
        device: Option<String>,
    },
    /// Verify a profile against live device state (read-only drift + checks)
    Verify {
        /// Path to .mspec.toml
        path: String,
        /// Target device id
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
    },
}

#[derive(Subcommand)]
pub enum RuleAction {
    /// Validate a rule file
    Validate { path: String },
    /// Generate a starter `.rule.toml` template
    Init {
        /// Namespaced rule id, e.g. `xiaomi/joyose/block-cloud-fetch`
        #[arg(long)]
        id: String,
        /// Primary hooked package, e.g. `com.xiaomi.joyose`
        #[arg(long)]
        package: String,
        /// Output path (default: `<repo>/rules/<id>.rule.toml`; missing parent dirs are created)
        #[arg(long)]
        output: Option<String>,
    },
    /// List rules from bundled index
    List,
    /// Deploy one PC-side rule, restart its target apps, and follow structured hook logs
    Run {
        /// Path to .rule.toml
        path: String,
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Seconds to wait for hook_loaded/hook_hit events
        #[arg(long, default_value_t = 15)]
        wait: u64,
        /// Success requires `loaded` (default) or an actual `hit`
        #[arg(long, default_value = "loaded", value_parser = ["loaded", "hit"])]
        expect: String,
        /// Deploy only; do not restart targets. Restart the target app(s)/framework externally.
        #[arg(long)]
        no_restart: bool,
    },
}

#[derive(Subcommand)]
pub enum ScriptAction {
    /// Validate a script package locally (manifest + bundle)
    Validate {
        /// Path to a directory containing manifest.toml
        path: String,
        /// Emit machine-readable JSON instead of human output
        #[arg(long)]
        json: bool,
    },
    /// Deploy a script package to the Agent and publish it to hook processes
    Deploy {
        /// Path to a directory containing manifest.toml
        path: String,
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Store without making the script the active one
        #[arg(long)]
        no_activate: bool,
        /// Emit machine-readable JSON instead of human output
        #[arg(long)]
        json: bool,
    },
    /// Deploy, restart the hook processes, and follow structured script events
    Run {
        /// Path to a directory containing manifest.toml
        path: String,
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Seconds to wait for script_loaded/script_hit events
        #[arg(long, default_value_t = 20)]
        wait: u64,
        /// Success requires `loaded` (default) or an actual `hit`
        #[arg(long, default_value = "loaded", value_parser = ["loaded", "hit"])]
        expect: String,
        /// Deploy only; do not restart targets. Restart the target app(s)/framework externally.
        #[arg(long)]
        no_restart: bool,
        /// Emit NDJSON structured events instead of human lines
        #[arg(long)]
        json: bool,
    },
    /// List stored script packages and their lifecycle state
    List {
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Emit machine-readable JSON instead of human output
        #[arg(long)]
        json: bool,
    },
    /// Make a script the active one (exclusive by default)
    Enable {
        /// Script id (e.g. xiaomi/security-center/macro-gate)
        script_id: String,
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Keep other scripts active instead of switching exclusively
        #[arg(long)]
        keep_others: bool,
        /// Emit machine-readable JSON instead of human output
        #[arg(long)]
        json: bool,
    },
    /// Deactivate a script without removing its files
    Disable {
        /// Script id (e.g. xiaomi/security-center/macro-gate)
        script_id: String,
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Emit machine-readable JSON instead of human output
        #[arg(long)]
        json: bool,
    },
    /// Delete a script package and its state
    Remove {
        /// Script id (e.g. xiaomi/security-center/macro-gate)
        script_id: String,
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Emit machine-readable JSON instead of human output
        #[arg(long)]
        json: bool,
    },
    /// Re-publish a stored script (optionally restarting its hook processes)
    Reload {
        /// Script id (e.g. xiaomi/security-center/macro-gate)
        script_id: String,
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Force-stop and relaunch the script's hook processes after publishing
        #[arg(long)]
        restart: bool,
        /// Emit machine-readable JSON instead of human output
        #[arg(long)]
        json: bool,
    },
    /// Follow the structured event stream (cursor-resuming) until Ctrl-C
    Follow {
        /// Target device id (default: configured default device)
        #[arg(long)]
        device: Option<String>,
        /// ADB serial for forward repair when the agent is unreachable
        #[arg(long)]
        serial: Option<String>,
        /// Do not relaunch the Agent app when it is unreachable
        #[arg(long)]
        no_bootstrap: bool,
        /// Only show events for this script id
        #[arg(long)]
        script: Option<String>,
        /// Resume from a journal cursor (event_id)
        #[arg(long)]
        cursor: Option<i64>,
        /// Emit NDJSON structured events instead of human lines
        #[arg(long)]
        json: bool,
    },
}

#[derive(Subcommand)]
pub enum McpAction {
    /// Start MCP stdio server (for Cursor / Claude Desktop)
    Serve,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("modspec=info".parse()?))
        .init();

    let cli = Cli::parse();
    commands::dispatch(cli.command).await
}
