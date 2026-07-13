use std::collections::HashSet;

use crate::error::{ModspecError, Result};
use crate::profile::{ModEntry, Profile, PROFILE_VERSION};
use crate::rule::{RuleFile, RULE_VERSION};

pub fn validate_profile(profile: &Profile) -> Result<()> {
    if profile.mspec_version != PROFILE_VERSION {
        return Err(ModspecError::UnsupportedVersion(profile.mspec_version.clone()));
    }

    if profile.meta.id.trim().is_empty() {
        return Err(ModspecError::Validation("meta.id is required".into()));
    }

    let mut ids = HashSet::new();
    for entry in &profile.mods {
        let id = entry.id();
        if !ids.insert(id.to_string()) {
            return Err(ModspecError::DuplicateModId(id.to_string()));
        }
    }

    let known: HashSet<_> = ids.iter().cloned().collect();
    for entry in &profile.mods {
        for dep in entry_depends_on(entry) {
            if !known.contains(dep) {
                return Err(ModspecError::UnknownDependency(dep.to_string()));
            }
        }
    }

    Ok(())
}

pub fn validate_rule(rule: &RuleFile) -> Result<()> {
    if rule.rule_version != RULE_VERSION {
        return Err(ModspecError::UnsupportedRuleVersion(rule.rule_version.clone()));
    }

    if rule.meta.id.trim().is_empty() {
        return Err(ModspecError::Validation("meta.id is required".into()));
    }

    if rule.hooks.is_empty() && rule.variants.is_empty() {
        return Err(ModspecError::Validation(
            "rule must define hooks or variants".into(),
        ));
    }

    Ok(())
}

fn entry_depends_on(entry: &ModEntry) -> &[String] {
    match entry {
        ModEntry::Reload { depends_on, .. } | ModEntry::PostAction { depends_on, .. } => {
            depends_on
        }
        _ => &[],
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profile::Profile;
    use crate::rule::RuleFile;

    #[test]
    fn validate_example_profile() {
        let profile = Profile::from_str(include_str!(
            "../../../profiles/xiaomi/hyper-perf-pack.mspec.toml"
        ))
        .unwrap();
        validate_profile(&profile).unwrap();
    }

    #[test]
    fn validate_example_rule() {
        let rule = RuleFile::from_str(include_str!(
            "../../../rules/xiaomi/joyose/block-cloud-fetch.rule.toml"
        ))
        .unwrap();
        validate_rule(&rule).unwrap();
    }
}
