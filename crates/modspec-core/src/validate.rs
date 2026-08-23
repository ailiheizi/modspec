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

    validate_categories(profile)?;

    Ok(())
}

/// Max category hierarchy depth (`a/b` = 2 levels).
pub const MAX_CATEGORY_DEPTH: usize = 2;

/// Description longer than this yields a warning (not an error).
pub const DESCRIPTION_MAX_LEN: usize = 500;

fn category_depth(id: &str) -> usize {
    id.split('/').filter(|s| !s.is_empty()).count()
}

fn validate_categories(profile: &Profile) -> Result<()> {
    let mut declared: HashSet<&str> = HashSet::new();
    for cat in &profile.categories {
        let id = cat.id.as_str().trim();
        if id.is_empty() {
            return Err(ModspecError::Validation("category id is required".into()));
        }
        if !declared.insert(id) {
            return Err(ModspecError::DuplicateCategoryId(id.to_string()));
        }
        if category_depth(id) > MAX_CATEGORY_DEPTH {
            return Err(ModspecError::Validation(format!(
                "category `{id}` exceeds {MAX_CATEGORY_DEPTH} levels"
            )));
        }
    }
    let strict = !declared.is_empty();

    if !strict {
        // Implicit mode: no declaration section, category strings are display labels.
        for entry in &profile.mods {
            if let Some(cat) = mod_category(entry) {
                if category_depth(cat) > MAX_CATEGORY_DEPTH {
                    return Err(ModspecError::Validation(format!(
                        "category `{cat}` exceeds {MAX_CATEGORY_DEPTH} levels"
                    )));
                }
            }
        }
        return Ok(());
    }

    // Strict mode: child paths must have their parent path declared.
    for id in &declared {
        if let Some((parent, _)) = id.rsplit_once('/') {
            if !parent.is_empty() && !declared.contains(parent) {
                return Err(ModspecError::UnknownCategory(parent.to_string()));
            }
        }
    }
    for entry in &profile.mods {
        if let Some(cat) = mod_category(entry) {
            if !declared.contains(cat) {
                return Err(ModspecError::UnknownCategory(cat.to_string()));
            }
        }
    }
    Ok(())
}

fn mod_category(entry: &ModEntry) -> Option<&str> {
    entry
        .common()
        .category
        .as_deref()
        .map(str::trim)
        .filter(|c| !c.is_empty())
}

/// Non-fatal lint warnings (currently: overly long descriptions).
pub fn profile_lint_warnings(profile: &Profile) -> Vec<String> {
    profile
        .mods
        .iter()
        .filter_map(|entry| {
            let description = entry.common().description.as_deref()?;
            let len = description.chars().count();
            (len > DESCRIPTION_MAX_LEN).then(|| {
                format!(
                    "mod '{}': description is {len} characters (max {DESCRIPTION_MAX_LEN})",
                    entry.id()
                )
            })
        })
        .collect()
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

    #[test]
    fn strict_mode_accepts_declared_categories() {
        let profile: Profile = r#"
            mspec_version = "1"

            [meta]
            id = "cat-ok"
            name = "cats"

            [[categories]]
            id = "network"
            title = "网络"

            [[categories]]
            id = "network/hotspot"
            title = "热点"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            on_command = "on"
            off_command = "off"
            category = "network/hotspot"

            [[mods]]
            id = "u"
            type = "reload"
            packages = ["a"]
        "#
        .parse()
        .unwrap();
        validate_profile(&profile).unwrap();
    }

    #[test]
    fn strict_mode_rejects_unknown_category() {
        let profile: Profile = r#"
            mspec_version = "1"

            [meta]
            id = "cat-bad-ref"
            name = "cats"

            [[categories]]
            id = "network"
            title = "网络"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            on_command = "on"
            off_command = "off"
            category = "display/screen"
        "#
        .parse()
        .unwrap();
        assert!(matches!(
            validate_profile(&profile),
            Err(ModspecError::UnknownCategory(_))
        ));
    }

    #[test]
    fn strict_mode_requires_parent_path() {
        let profile: Profile = r#"
            mspec_version = "1"

            [meta]
            id = "cat-missing-parent"
            name = "cats"

            [[categories]]
            id = "network/hotspot"
            title = "热点"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            on_command = "on"
            off_command = "off"
            category = "network/hotspot"
        "#
        .parse()
        .unwrap();
        assert!(matches!(
            validate_profile(&profile),
            Err(ModspecError::UnknownCategory(id)) if id == "network"
        ));
    }

    #[test]
    fn duplicate_category_id_rejected() {
        let profile: Profile = r#"
            mspec_version = "1"

            [meta]
            id = "cat-dup"
            name = "cats"

            [[categories]]
            id = "network"
            title = "网络"

            [[categories]]
            id = "network"
            title = "网络二"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            on_command = "on"
            off_command = "off"
        "#
        .parse()
        .unwrap();
        assert!(matches!(
            validate_profile(&profile),
            Err(ModspecError::DuplicateCategoryId(_))
        ));
    }

    #[test]
    fn category_max_two_levels_enforced() {
        let profile: Profile = r#"
            mspec_version = "1"

            [meta]
            id = "cat-deep"
            name = "cats"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            on_command = "on"
            off_command = "off"
            category = "a/b/c"
        "#
        .parse()
        .unwrap();
        assert!(validate_profile(&profile).is_err());
    }

    #[test]
    fn description_length_warning() {
        let long = "长".repeat(DESCRIPTION_MAX_LEN + 1);
        let content = format!(
            r#"
            mspec_version = "1"

            [meta]
            id = "desc-warn"
            name = "d"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            description = "{long}"
            on_command = "on"
            off_command = "off"
            "#
        );
        let profile: Profile = content.parse().unwrap();
        // Warning, not an error.
        validate_profile(&profile).unwrap();
        let warnings = profile_lint_warnings(&profile);
        assert_eq!(warnings.len(), 1);
        assert!(warnings[0].contains("mod 't'"));
    }

    #[test]
    fn short_description_no_warning() {
        let profile: Profile = r#"
            mspec_version = "1"

            [meta]
            id = "desc-ok"
            name = "d"

            [[mods]]
            id = "t"
            type = "shell_toggle"
            title = "t"
            description = "简短描述"
            on_command = "on"
            off_command = "off"
        "#
        .parse()
        .unwrap();
        assert!(profile_lint_warnings(&profile).is_empty());
    }
}
