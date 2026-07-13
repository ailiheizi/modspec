//! ModSpec core: TOML schemas for profiles (`.mspec.toml`) and hook rules (`.rule.toml`).

pub mod devices;
pub mod error;
pub mod profile;
pub mod rule;
pub mod state;
pub mod validate;

pub use devices::{DeviceStore, DevicesConfig, StoredDevice};
pub use error::{ModspecError, Result};
pub use profile::{ModEntry, Profile, ReapplyConfig, VerifyConfig};
pub use rule::{HookAction, HookPhase, HookRule, HookTarget, RuleFile, RuleVariant};
pub use state::{ApplyStatus, ItemState, ProfileState, VerifyStatus};
pub use validate::{validate_profile, validate_rule};
