mod commands;

use anyhow::Result;
use clap::{Parser, Subcommand};
use tracing_subscriber::EnvFilter;

#[derive(Parser)]
#[command(name = "modspec", about = "LSPosed profile & rule orchestration CLI", version)]
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
    Show {
        path: String,
    },
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
    /// Device management (list, status)
    Device {
        #[command(subcommand)]
        action: DeviceAction,
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
}

#[derive(Subcommand)]
pub enum RuleAction {
    /// Validate a rule file
    Validate { path: String },
    /// List rules from bundled index
    List,
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
