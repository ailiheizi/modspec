use anyhow::Result;

use crate::CommunityAction;

pub fn run(action: CommunityAction) -> Result<()> {
    match action {
        CommunityAction::Index => print_community_index(),
    }
}

fn print_community_index() -> Result<()> {
    let index = include_str!("../../../../community/index.toml");
    println!("{index}");
    Ok(())
}
