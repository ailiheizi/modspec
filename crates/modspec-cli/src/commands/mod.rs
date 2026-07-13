mod community;
mod device;
mod mcp;
mod pair;
mod profile;
mod rule;
mod validate;

use anyhow::Result;

use crate::Commands;

pub async fn dispatch(command: Commands) -> Result<()> {
    match command {
        Commands::Validate { path } => validate::validate_file(&path),
        Commands::Show { path } => validate::show_file(&path),
        Commands::Community { action } => community::run(action),
        Commands::Pair { action } => pair::run(action).await,
        Commands::Device { action } => device::run(action).await,
        Commands::Profile { action } => profile::run(action).await,
        Commands::Rule { action } => rule::run(action),
        Commands::Mcp { action } => mcp::run(action).await,
    }
}
