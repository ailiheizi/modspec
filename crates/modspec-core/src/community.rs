//! Community catalog index (`community/index.toml`) schema + lint.

use std::collections::HashSet;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::Result;

/// Root document: `community/index.toml`
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct CommunityIndex {
    #[serde(default)]
    pub profiles: Vec<CommunityProfile>,
    #[serde(default)]
    pub rules: Vec<CommunityRule>,
    #[serde(default)]
    pub references: CommunityReferences,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CommunityProfile {
    pub id: String,
    /// Repo-relative path, e.g. `profiles/xiaomi/hyper-perf-pack.mspec.toml`
    pub path: String,
    #[serde(default)]
    pub oem: Vec<String>,
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CommunityRule {
    pub id: String,
    /// Repo-relative path, e.g. `rules/xiaomi/joyose/block-cloud-fetch.rule.toml`
    #[serde(rename = "ref")]
    pub reference: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
pub struct CommunityReferences {
    #[serde(default)]
    pub modules: Vec<CommunityReferenceModule>,
}

/// Reference-only module (user installs it separately; not shipped).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CommunityReferenceModule {
    pub package: String,
    pub name: String,
    pub url: String,
}

/// One lint finding. `ok == true` entries are informational (path checked, etc.);
/// `ok == false` entries are actionable problems.
#[derive(Debug, Clone, PartialEq)]
pub struct CommunityIssue {
    pub ok: bool,
    pub section: String,
    pub id: String,
    pub message: String,
}

impl CommunityIssue {
    fn error(section: &str, id: &str, message: impl Into<String>) -> Self {
        Self {
            ok: false,
            section: section.into(),
            id: id.into(),
            message: message.into(),
        }
    }

    fn ok(section: &str, id: &str, message: impl Into<String>) -> Self {
        Self {
            ok: true,
            section: section.into(),
            id: id.into(),
            message: message.into(),
        }
    }
}

impl std::str::FromStr for CommunityIndex {
    type Err = crate::error::ModspecError;

    fn from_str(content: &str) -> Result<Self> {
        Ok(toml::from_str(content)?)
    }
}

/// Lint a community index. When `repo_root` is `Some`, every profile path and
/// rule ref is checked for existence (repo-relative). Returns all findings;
/// callers decide the exit code via `issues.iter().any(|i| !i.ok)`.
pub fn lint_community_index(index: &CommunityIndex, repo_root: Option<&Path>) -> Vec<CommunityIssue> {
    let mut issues = Vec::new();

    let mut seen_profile_ids = HashSet::new();
    for profile in &index.profiles {
        if !seen_profile_ids.insert(profile.id.as_str()) {
            issues.push(CommunityIssue::error(
                "profiles",
                &profile.id,
                "duplicate profile id",
            ));
        }
        if profile.id.is_empty() {
            issues.push(CommunityIssue::error("profiles", "<empty>", "empty id"));
        }
        for oem in &profile.oem {
            if !is_valid_oem(oem) {
                issues.push(CommunityIssue::error(
                    "profiles",
                    &profile.id,
                    format!("invalid oem `{oem}` (expected lowercase alphanumeric)"),
                ));
            }
        }
        if let Some(root) = repo_root {
            let path = root.join(&profile.path);
            if !path.is_file() {
                issues.push(CommunityIssue::error(
                    "profiles",
                    &profile.id,
                    format!("referenced path `{}` does not exist", profile.path),
                ));
            } else {
                issues.push(CommunityIssue::ok(
                    "profiles",
                    &profile.id,
                    format!("path `{}` exists", profile.path),
                ));
            }
        }
    }

    let mut seen_rule_ids = HashSet::new();
    for rule in &index.rules {
        if !seen_rule_ids.insert(rule.id.as_str()) {
            issues.push(CommunityIssue::error(
                "rules",
                &rule.id,
                "duplicate rule id",
            ));
        }
        if !crate::validate::is_valid_rule_id(&rule.id) {
            issues.push(CommunityIssue::error(
                "rules",
                &rule.id,
                format!("invalid rule id `{}` (expected slash-separated segments)", rule.id),
            ));
        }
        if let Some(root) = repo_root {
            let path = root.join(&rule.reference);
            if !path.is_file() {
                issues.push(CommunityIssue::error(
                    "rules",
                    &rule.id,
                    format!("referenced path `{}` does not exist", rule.reference),
                ));
            } else {
                issues.push(CommunityIssue::ok(
                    "rules",
                    &rule.id,
                    format!("path `{}` exists", rule.reference),
                ));
            }
        }
    }

    for module in &index.references.modules {
        if !crate::validate::is_valid_package_name(&module.package) {
            issues.push(CommunityIssue::error(
                "references.modules",
                &module.package,
                format!("invalid package name `{}`", module.package),
            ));
        }
        if module.url.is_empty() {
            issues.push(CommunityIssue::error(
                "references.modules",
                &module.package,
                "empty url",
            ));
        }
    }

    issues
}

/// Lowercase alphanumeric (with hyphens), non-empty.
fn is_valid_oem(oem: &str) -> bool {
    !oem.is_empty()
        && oem
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-')
        && oem == oem.to_ascii_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_index() -> CommunityIndex {
        CommunityIndex {
            profiles: vec![
                CommunityProfile {
                    id: "hyper-perf-pack".into(),
                    path: "profiles/xiaomi/hyper-perf-pack.mspec.toml".into(),
                    oem: vec!["xiaomi".into()],
                    tags: vec![],
                },
                CommunityProfile {
                    id: "hyper-perf-pack".into(),
                    path: "profiles/oneplus/notification-pack.mspec.toml".into(),
                    oem: vec!["OnePlus".into()],
                    tags: vec![],
                },
            ],
            rules: vec![CommunityRule {
                id: "bad id".into(),
                reference: "rules/nope.rule.toml".into(),
            }],
            references: CommunityReferences {
                modules: vec![CommunityReferenceModule {
                    package: "com.rdstory.miuiperfsaver".into(),
                    name: "MIUI 性能救星".into(),
                    url: "https://github.com/rdtoy/MIUIPerfSaver".into(),
                }],
            },
        }
    }

    #[test]
    fn lint_reports_duplicates_and_format_issues() {
        let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../");
        let issues = lint_community_index(&sample_index(), Some(&repo));
        assert!(issues.iter().any(|i| !i.ok && i.message.contains("duplicate profile id")));
        assert!(issues.iter().any(|i| !i.ok && i.message.contains("invalid oem `OnePlus`")));
        assert!(issues.iter().any(|i| !i.ok && i.message.contains("invalid rule id `bad id`")));
        assert!(issues.iter().any(|i| !i.ok && i.message.contains("does not exist")));
        assert!(!issues.iter().any(|i| !i.ok && i.section == "references.modules"));
    }

    #[test]
    fn lint_clean_index_has_no_errors() {
        let index = CommunityIndex {
            profiles: vec![CommunityProfile {
                id: "hyper-perf-pack".into(),
                path: "profiles/xiaomi/hyper-perf-pack.mspec.toml".into(),
                oem: vec!["xiaomi".into()],
                tags: vec![],
            }],
            rules: vec![CommunityRule {
                id: "universal/system/skip-kill-background".into(),
                reference: "rules/universal/system/skip-kill-background.rule.toml".into(),
            }],
            references: CommunityReferences::default(),
        };
        let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../");
        let issues = lint_community_index(&index, Some(&repo));
        assert!(!issues.iter().any(|i| !i.ok), "unexpected errors: {issues:?}");
    }

    #[test]
    fn bundled_index_lints_clean() {
        let content = include_str!("../../../community/index.toml");
        let index: CommunityIndex = content.parse().expect("parse bundled index");
        let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../");
        let issues = lint_community_index(&index, Some(&repo));
        let errors: Vec<_> = issues.iter().filter(|i| !i.ok).collect();
        assert!(errors.is_empty(), "bundled index has issues: {errors:?}");
    }

    #[test]
    fn oem_validity() {
        assert!(is_valid_oem("xiaomi"));
        assert!(is_valid_oem("redmi-k40"));
        assert!(!is_valid_oem(""));
        assert!(!is_valid_oem("Xiaomi"));
        assert!(!is_valid_oem("one plus"));
    }
}
