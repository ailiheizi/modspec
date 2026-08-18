use std::collections::HashSet;

use crate::error::{ModspecError, Result};
use crate::profile::{ModEntry, Profile, PROFILE_VERSION};
use crate::rule::{RuleFile, RULE_VERSION};

pub fn validate_profile(profile: &Profile) -> Result<()> {
    if profile.mspec_version != PROFILE_VERSION {
        return Err(ModspecError::UnsupportedVersion(
            profile.mspec_version.clone(),
        ));
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
        return Err(ModspecError::UnsupportedRuleVersion(
            rule.rule_version.clone(),
        ));
    }

    if rule.meta.id.trim().is_empty() {
        return Err(ModspecError::Validation("meta.id is required".into()));
    }
    if !is_valid_rule_id(&rule.meta.id) {
        return Err(ModspecError::Validation(format!(
            "invalid rule id: {}",
            rule.meta.id
        )));
    }
    for package in &rule.compatible.packages {
        if !is_valid_package_name(package) {
            return Err(ModspecError::Validation(format!(
                "invalid compatible package: {package}"
            )));
        }
    }

    if rule.hooks.is_empty() && rule.variants.is_empty() {
        return Err(ModspecError::Validation(
            "rule must define hooks or variants".into(),
        ));
    }

    Ok(())
}

pub fn is_valid_rule_id(value: &str) -> bool {
    !value.is_empty()
        && value.split('/').all(|segment| {
            !segment.is_empty()
                && segment
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.'))
        })
}

pub(crate) fn is_valid_package_name(value: &str) -> bool {
    if matches!(value, "system" | "android") {
        return true;
    }
    let segments: Vec<_> = value.split('.').collect();
    let valid_segment = |segment: &str| {
        !segment.is_empty()
            && segment
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_')
    };
    segments.len() >= 2 && segments.into_iter().all(valid_segment)
}

fn entry_depends_on(entry: &ModEntry) -> &[String] {
    match entry {
        ModEntry::Reload { depends_on, .. } | ModEntry::PostAction { depends_on, .. } => depends_on,
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
        let profile: Profile = include_str!("../../../profiles/xiaomi/hyper-perf-pack.mspec.toml")
            .parse()
            .unwrap();
        validate_profile(&profile).unwrap();
    }

    #[test]
    fn validate_example_rule() {
        let rule: RuleFile =
            include_str!("../../../rules/universal/system/skip-kill-background.rule.toml")
                .parse()
                .unwrap();
        validate_rule(&rule).unwrap();
    }

    #[test]
    fn validate_smoke_observe_rule() {
        let rule: RuleFile = include_str!("../../../rules/test/smoke-joyose.rule.toml")
            .parse()
            .unwrap();
        validate_rule(&rule).unwrap();
    }
}
