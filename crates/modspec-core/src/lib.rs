//! ModSpec core: TOML schemas for profiles (`.mspec.toml`) and hook rules (`.rule.toml`).

pub mod community;
pub mod devices;
pub mod error;
pub mod profile;
pub mod rule;
pub mod script;
pub mod state;
pub mod validate;

pub use community::{lint_community_index, CommunityIndex, CommunityIssue};
pub use devices::{DeviceStore, DevicesConfig, StoredDevice};
pub use error::{ModspecError, Result};
pub use profile::{ModEntry, Profile, ReapplyConfig, VerifyCheck, VerifyConfig, VerifySource};
pub use rule::{HookAction, HookPhase, HookRule, HookTarget, RuleFile, RuleVariant};
pub use script::{
    validate_script_bundle, ScriptBundle, ScriptCompatible, ScriptEngineConfig, ScriptFile,
    ScriptLimits, ScriptManifest, ScriptMeta, ScriptPermissions, ScriptVerify,
};
pub use state::{ApplyStatus, ItemState, ProfileState, VerifyStatus};
pub use validate::{validate_profile, validate_rule};
