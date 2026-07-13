use anyhow::Result;

use crate::McpAction;

pub async fn run(action: McpAction) -> Result<()> {
    match action {
        McpAction::Serve => modspec_mcp::serve_stdio().await,
    }
}
