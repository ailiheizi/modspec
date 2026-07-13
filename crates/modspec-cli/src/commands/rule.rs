use anyhow::Result;

use crate::RuleAction;

pub fn run(action: RuleAction) -> Result<()> {
    match action {
        RuleAction::Validate { path } => super::validate::validate_rule_file(&path),
        RuleAction::List => list_rules_index(),
    }
}

fn list_rules_index() -> Result<()> {
    #[derive(serde::Deserialize)]
    struct Index {
        rules: Vec<RuleEntry>,
    }
    #[derive(serde::Deserialize)]
    struct RuleEntry {
        id: String,
        path: String,
        tags: Vec<String>,
    }

    let index: Index = toml::from_str(include_str!("../../../../rules/index.toml"))?;
    for r in index.rules {
        println!("{}\t{}\t{}", r.id, r.path, r.tags.join(","));
    }
    Ok(())
}
