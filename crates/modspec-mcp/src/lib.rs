//! MCP stdio server — exposes modspec validate/apply/device tools to AI clients.

mod server;
mod tools;

pub use server::serve_stdio;
