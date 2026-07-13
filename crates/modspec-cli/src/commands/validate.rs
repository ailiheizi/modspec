use anyhow::{Context, Result};
use modspec_core::{Profile, RuleFile, validate_profile, validate_rule};

pub fn validate_file(path: &str) -> Result<()> {
    if path.ends_with(".rule.toml") {
        validate_rule_file(path)
    } else {
        validate_profile_file(path)
    }
}

pub fn validate_profile_file(path: &str) -> Result<()> {
    let profile = Profile::from_file(path).with_context(|| format!("read profile {path}"))?;
    validate_profile(&profile)?;
    println!("OK profile {} ({} mods)", profile.meta.id, profile.mods.len());
    Ok(())
}

pub fn validate_rule_file(path: &str) -> Result<()> {
    let rule = RuleFile::from_file(path).with_context(|| format!("read rule {path}"))?;
    validate_rule(&rule)?;
    println!(
        "OK rule {} ({} hooks, {} variants)",
        rule.meta.id,
        rule.hooks.len(),
        rule.variants.len()
    );
    Ok(())
}

pub fn show_file(path: &str) -> Result<()> {
    if path.ends_with(".rule.toml") {
        let rule = RuleFile::from_file(path)?;
        println!("{}", serde_json::to_string_pretty(&rule)?);
    } else {
        let profile = Profile::from_file(path)?;
        println!("{}", serde_json::to_string_pretty(&profile)?);
    }
    Ok(())
}
