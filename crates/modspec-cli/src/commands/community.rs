use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

use crate::CommunityAction;

pub fn run(action: CommunityAction) -> Result<()> {
    match action {
        CommunityAction::Index => print_community_index(),
        CommunityAction::Lint { root } => {
            let root = root.unwrap_or_else(default_repo_root);
            lint_community_index(&root)
        }
    }
}

/// Repo root relative to this crate: `<repo>/crates/modspec-cli/../..`.
fn default_repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn print_community_index() -> Result<()> {
    let index = include_str!("../../../../community/index.toml");
    println!("{index}");
    Ok(())
}

fn lint_community_index(repo_root: &Path) -> Result<()> {
    let root = repo_root
        .canonicalize()
        .with_context(|| format!("cannot resolve root {}", repo_root.display()))?;
    let index_path = root.join("community/index.toml");
    let content = std::fs::read_to_string(&index_path)
        .with_context(|| format!("cannot read {}", index_path.display()))?;
    let index: modspec_core::CommunityIndex = content
        .parse()
        .with_context(|| format!("cannot parse {}", index_path.display()))?;

    let issues = modspec_core::lint_community_index(&index, Some(&root));
    let mut errors = 0usize;
    for issue in &issues {
        let mark = if issue.ok { "ok" } else { "error" };
        println!(
            "{mark:<6} [{}/{}] {}",
            issue.section, issue.id, issue.message
        );
        if !issue.ok {
            errors += 1;
        }
    }
    if errors > 0 {
        anyhow::bail!("community lint failed: {errors} issue(s) in {}", index_path.display());
    }
    println!(
        "community index OK: {} entries checked in {}",
        issues.len(),
        index_path.display()
    );
    Ok(())
}
