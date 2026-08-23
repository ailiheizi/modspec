use thiserror::Error;

pub type Result<T> = std::result::Result<T, ModspecError>;

#[derive(Debug, Error)]
pub enum ModspecError {
    #[error("unsupported mspec_version: {0}")]
    UnsupportedVersion(String),

    #[error("unsupported rule_version: {0}")]
    UnsupportedRuleVersion(String),

    #[error("unsupported script_version: {0}")]
    UnsupportedScriptVersion(String),

    #[error("duplicate mod id: {0}")]
    DuplicateModId(String),

    #[error("unknown mod id in dependency: {0}")]
    UnknownDependency(String),

    #[error("unknown category id: {0}")]
    UnknownCategory(String),

    #[error("duplicate category id: {0}")]
    DuplicateCategoryId(String),

    #[error("validation: {0}")]
    Validation(String),

    #[error("io: {0}")]
    Io(#[from] std::io::Error),

    #[error("toml parse: {0}")]
    TomlParse(#[from] toml::de::Error),

    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
}
