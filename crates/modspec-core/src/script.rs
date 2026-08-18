//! Script package schema and validation (`.script.toml` bundles).
//!
//! A script package is the first-class expert-engine artifact of ModSpec:
//! a TOML manifest describing the engine (`js` | `lua`), the hook processes it
//! loads into (`compatible.packages`), the apps it guards (`target_packages`),
//! resource limits and declared capabilities — plus the bundled source files.
//!
//! Validation is deterministic and happens on both PC (`modspec script
//! validate`) and Agent (`script_validate` / `script_deploy`) so a malformed
//! bundle can never reach a hooked process.

use std::collections::HashSet;
use std::fs;
use std::path::Path;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{ModspecError, Result};
use crate::validate::is_valid_package_name;

/// Current manifest schema version.
pub const SCRIPT_VERSION: &str = "1";

/// Maximum manifest TOML size (bytes).
pub const MAX_MANIFEST_BYTES: usize = 64 * 1024;
/// Maximum size of one bundled source file (bytes).
pub const MAX_FILE_BYTES: usize = 512 * 1024;
/// Maximum total bundle size (bytes).
pub const MAX_BUNDLE_BYTES: usize = 4 * 1024 * 1024;
/// Maximum number of bundled files.
pub const MAX_FILES: usize = 64;

/// Capabilities a script may declare. Only these are honored by the Agent;
/// anything else is rejected at validation time. There is deliberately no
/// filesystem / network / root / shell capability in this slice.
pub const ALLOWED_CAPABILITIES: [&str; 5] = ["emit", "log", "toast", "frida", "native_hook"];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptManifest {
    pub script_version: String,
    pub meta: ScriptMeta,
    #[serde(default)]
    pub compatible: ScriptCompatible,
    #[serde(default)]
    pub engine: ScriptEngineConfig,
    #[serde(default)]
    pub limits: ScriptLimits,
    #[serde(default)]
    pub permissions: ScriptPermissions,
    #[serde(default)]
    pub verify: Option<ScriptVerify>,
    /// Optional native (Frida gadget) companion script. Requires the `frida`
    /// capability; the gadget is deployed on demand by the PC during
    /// `modspec script run/deploy` (never bundled into the agent APK).
    #[serde(default)]
    pub frida: Option<ScriptFridaConfig>,
}

/// Native-layer companion via Frida gadget (see `capabilities: ["frida"]`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptFridaConfig {
    /// Bundle-relative Frida script, e.g. `src/frida.js` (runs in the gadget's
    /// QuickJS engine; `console.log` JSON lines are ingested as events).
    pub script: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptMeta {
    /// Namespaced id, e.g. `xiaomi/security-center/macro-gate`.
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct ScriptCompatible {
    /// Hook processes the script loads into and that must be in LSPosed scope.
    #[serde(default)]
    pub packages: Vec<String>,
    /// Apps the script guards (its behavioral targets). Read by the script via
    /// `modspec.getTargets()`; never a scope requirement by itself.
    #[serde(default)]
    pub target_packages: Vec<String>,
    #[serde(default)]
    pub oem: Vec<String>,
    #[serde(default)]
    pub min_android: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptEngineConfig {
    /// `js` (Rhino) or `lua` (LuaJ).
    pub runtime: String,
    /// Entrypoint relative to the bundle root; defaults to `src/main.js` /
    /// `src/main.lua` per runtime.
    #[serde(default)]
    pub entrypoint: Option<String>,
}

impl Default for ScriptEngineConfig {
    fn default() -> Self {
        Self {
            runtime: "js".into(),
            entrypoint: None,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct ScriptLimits {
    /// Entrypoint evaluation budget in ms (default 10_000).
    #[serde(default)]
    pub execution_ms: Option<u32>,
    /// Per hook-callback budget in ms (default 50).
    #[serde(default)]
    pub callback_ms: Option<u32>,
    /// `waitForClass` budget in ms (default 15_000).
    #[serde(default)]
    pub wait_class_ms: Option<u32>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct ScriptPermissions {
    /// Declared capabilities; subset of [`ALLOWED_CAPABILITIES`].
    #[serde(default)]
    pub capabilities: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptVerify {
    /// Structured event name the script emits on success (e.g. `macro_allowed`).
    #[serde(default)]
    pub log_success: Option<String>,
    /// Structured event name the script emits on failure.
    #[serde(default)]
    pub log_failure: Option<String>,
}

/// One bundled source file.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScriptFile {
    /// Bundle-relative path, e.g. `src/main.js`. Must be a plain relative path.
    pub name: String,
    pub content: String,
}

impl ScriptFile {
    pub fn new(name: impl Into<String>, content: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            content: content.into(),
        }
    }
}

/// A complete, validated-able script package: raw manifest TOML plus files.
#[derive(Debug, Clone)]
pub struct ScriptBundle {
    pub manifest: ScriptManifest,
    /// The original manifest TOML text (preserved for deterministic hashing).
    pub manifest_raw: String,
    pub files: Vec<ScriptFile>,
}

impl ScriptBundle {
    /// Build a bundle from raw manifest TOML and files (no validation here).
    pub fn from_parts(manifest_raw: String, files: Vec<ScriptFile>) -> Result<Self> {
        let manifest: ScriptManifest = toml::from_str(&manifest_raw)
            .map_err(|e| ModspecError::Validation(format!("invalid script manifest: {e}")))?;
        Ok(Self {
            manifest,
            manifest_raw,
            files,
        })
    }

    /// Build a bundle from a directory containing `manifest.toml` plus sources.
    pub fn from_dir(dir: &Path) -> Result<Self> {
        let manifest_path = dir.join("manifest.toml");
        let manifest_raw = fs::read_to_string(&manifest_path)
            .map_err(|e| ModspecError::Validation(format!("read {manifest_path:?}: {e}")))?;
        let mut files = Vec::new();
        collect_files(dir, dir, &mut files)?;
        Ok(Self {
            manifest: toml::from_str(&manifest_raw)
                .map_err(|e| ModspecError::Validation(format!("invalid script manifest: {e}")))?,
            manifest_raw,
            files,
        })
    }

    /// The resolved entrypoint name (default depends on the runtime).
    pub fn entrypoint(&self) -> String {
        match &self.manifest.engine.entrypoint {
            Some(name) => name.clone(),
            None => match self.manifest.engine.runtime.as_str() {
                "lua" => "src/main.lua".into(),
                _ => "src/main.js".into(),
            },
        }
    }

    /// Deterministic content hash over the manifest text and every file
    /// (sorted by name, fixed encoding) — stable across machines and rebuilds.
    pub fn content_hash(&self) -> String {
        let mut hasher = Sha256::new();
        hasher.update(b"manifest=");
        hasher.update(self.manifest_raw.as_bytes());
        hasher.update(b"\n");
        let mut files: Vec<&ScriptFile> = self.files.iter().collect();
        files.sort_by(|a, b| a.name.cmp(&b.name));
        for file in files {
            hasher.update(b"file=");
            hasher.update(file.name.as_bytes());
            hasher.update(b"=");
            hasher.update(file.content.as_bytes());
            hasher.update(b"\n");
        }
        format!("{:x}", hasher.finalize())
    }
}

/// Validate a complete script bundle. Returns the first problem as an error;
/// all checks are deterministic and shared with the Agent implementation.
pub fn validate_script_bundle(bundle: &ScriptBundle) -> Result<()> {
    let manifest = &bundle.manifest;
    if manifest.script_version != SCRIPT_VERSION {
        return Err(ModspecError::UnsupportedScriptVersion(
            manifest.script_version.clone(),
        ));
    }
    if manifest.meta.id.trim().is_empty() {
        return Err(ModspecError::Validation(
            "script meta.id is required".into(),
        ));
    }
    if !valid_script_id(&manifest.meta.id) {
        return Err(ModspecError::Validation(format!(
            "invalid script id: {}",
            manifest.meta.id
        )));
    }
    if manifest.meta.name.trim().is_empty() {
        return Err(ModspecError::Validation(
            "script meta.name is required".into(),
        ));
    }

    let runtime = manifest.engine.runtime.trim();
    if !matches!(runtime, "js" | "lua") {
        return Err(ModspecError::Validation(format!(
            "unsupported engine runtime: {runtime:?} (expected js|lua)"
        )));
    }

    for package in &manifest.compatible.packages {
        if !is_valid_package_name(package) {
            return Err(ModspecError::Validation(format!(
                "invalid compatible package: {package}"
            )));
        }
    }
    for package in &manifest.compatible.target_packages {
        if !is_valid_package_name(package) {
            return Err(ModspecError::Validation(format!(
                "invalid target package: {package}"
            )));
        }
    }
    for oem in &manifest.compatible.oem {
        if oem.trim().is_empty()
            || !oem
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        {
            return Err(ModspecError::Validation(format!("invalid oem: {oem:?}")));
        }
    }

    let manifest_bytes = bundle.manifest_raw.len();
    if manifest_bytes > MAX_MANIFEST_BYTES {
        return Err(ModspecError::Validation(format!(
            "manifest exceeds {MAX_MANIFEST_BYTES} bytes"
        )));
    }
    if bundle.files.is_empty() {
        return Err(ModspecError::Validation(
            "script must bundle at least one source file".into(),
        ));
    }
    if bundle.files.len() > MAX_FILES {
        return Err(ModspecError::Validation(format!(
            "script bundles more than {MAX_FILES} files"
        )));
    }

    let mut total = manifest_bytes;
    let mut seen = HashSet::new();
    for file in &bundle.files {
        if !valid_file_name(&file.name) {
            return Err(ModspecError::Validation(format!(
                "invalid file name in bundle: {:?}",
                file.name
            )));
        }
        if !seen.insert(file.name.clone()) {
            return Err(ModspecError::Validation(format!(
                "duplicate file in bundle: {}",
                file.name
            )));
        }
        if file.content.len() > MAX_FILE_BYTES {
            return Err(ModspecError::Validation(format!(
                "file {} exceeds {MAX_FILE_BYTES} bytes",
                file.name
            )));
        }
        total += file.content.len();
    }
    if total > MAX_BUNDLE_BYTES {
        return Err(ModspecError::Validation(format!(
            "script bundle exceeds {MAX_BUNDLE_BYTES} bytes"
        )));
    }

    let entrypoint = bundle.entrypoint();
    if !seen.contains(&entrypoint) {
        return Err(ModspecError::Validation(format!(
            "entrypoint not found in bundle: {entrypoint}"
        )));
    }

    let allowed: HashSet<&str> = ALLOWED_CAPABILITIES.iter().copied().collect();
    for capability in &manifest.permissions.capabilities {
        if !allowed.contains(capability.as_str()) {
            return Err(ModspecError::Validation(format!(
                "undeclared capability not allowed: {capability} (allowed: {})",
                ALLOWED_CAPABILITIES.join(", ")
            )));
        }
    }

    // The `frida` capability requires a declared native companion script that
    // is actually present in the bundle.
    if manifest.permissions.capabilities.iter().any(|c| c == "frida") {
        let frida = manifest.frida.as_ref().ok_or_else(|| {
            ModspecError::Validation(
                "capability `frida` requires a [frida] section with `script`".into(),
            )
        })?;
        if !valid_file_name(&frida.script) {
            return Err(ModspecError::Validation(format!(
                "invalid frida script path: {}",
                frida.script
            )));
        }
        if !seen.contains(&frida.script) {
            return Err(ModspecError::Validation(format!(
                "frida script not found in bundle: {}",
                frida.script
            )));
        }
    }

    Ok(())
}

/// Validate only the manifest (used by `script_validate` when files arrive
/// separately); entrypoint existence is checked against the file set.
pub fn validate_script_bundle_with_entrypoint(
    manifest_raw: &str,
    files: &[ScriptFile],
) -> Result<()> {
    let bundle = ScriptBundle::from_parts(manifest_raw.to_string(), files.to_vec())?;
    validate_script_bundle(&bundle)
}

fn valid_script_id(value: &str) -> bool {
    !value.is_empty()
        && value.split('/').all(|segment| {
            !segment.is_empty()
                && segment
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.'))
        })
}

/// Bundle file names must be plain relative paths: no absolute paths, no `..`
/// segments, no backslashes, only safe characters.
fn valid_file_name(name: &str) -> bool {
    if name.is_empty() || name.len() > 256 {
        return false;
    }
    if name.starts_with('/') || name.contains('\\') || name.contains('\0') {
        return false;
    }
    for segment in name.split('/') {
        if segment.is_empty() || segment == "." || segment == ".." {
            return false;
        }
        if !segment
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.'))
        {
            return false;
        }
    }
    true
}

fn collect_files(root: &Path, dir: &Path, out: &mut Vec<ScriptFile>) -> Result<()> {
    let mut entries: Vec<_> = fs::read_dir(dir)
        .map_err(|e| ModspecError::Validation(format!("read dir {dir:?}: {e}")))?
        .collect::<std::result::Result<_, _>>()
        .map_err(|e| ModspecError::Validation(format!("read dir {dir:?}: {e}")))?;
    entries.sort_by_key(|e| e.file_name());
    for entry in entries {
        let path = entry.path();
        if path.is_dir() {
            collect_files(root, &path, out)?;
            continue;
        }
        let name = path
            .strip_prefix(root)
            .map_err(|e| ModspecError::Validation(format!("path {path:?}: {e}")))?
            .to_string_lossy()
            .replace('\\', "/");
        if name == "manifest.toml" {
            continue;
        }
        let content = fs::read_to_string(&path)
            .map_err(|e| ModspecError::Validation(format!("read {path:?}: {e}")))?;
        out.push(ScriptFile { name, content });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_manifest(id: &str, runtime: &str) -> String {
        format!(
            r#"
script_version = "1"
[meta]
id = "{id}"
name = "Test script"
[compatible]
packages = ["com.example.target"]
target_packages = ["com.example.game"]
[engine]
runtime = "{runtime}"
[permissions]
capabilities = ["emit", "log"]
"#
        )
    }

    fn bundle_of(manifest: &str, files: Vec<(&str, &str)>) -> ScriptBundle {
        ScriptBundle::from_parts(
            manifest.to_string(),
            files
                .into_iter()
                .map(|(name, content)| ScriptFile::new(name, content))
                .collect(),
        )
        .unwrap()
    }

    #[test]
    fn valid_js_bundle_passes() {
        let bundle = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/main.js", "modspec.log('hi');")],
        );
        validate_script_bundle(&bundle).unwrap();
    }

    #[test]
    fn valid_lua_bundle_with_default_entrypoint_passes() {
        let bundle = bundle_of(
            &valid_manifest("vendor/test/script", "lua"),
            vec![("src/main.lua", "modspec.log('hi')")],
        );
        validate_script_bundle(&bundle).unwrap();
    }

    #[test]
    fn missing_entrypoint_is_rejected() {
        let bundle = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/other.js", "modspec.log('hi');")],
        );
        let error = validate_script_bundle(&bundle).unwrap_err();
        assert!(error.to_string().contains("entrypoint not found"));
    }

    #[test]
    fn unsupported_runtime_is_rejected() {
        let manifest = valid_manifest("vendor/test/script", "python");
        let bundle = bundle_of(&manifest, vec![("src/main.py", "print(1)")]);
        let error = validate_script_bundle(&bundle).unwrap_err();
        assert!(error.to_string().contains("unsupported engine runtime"));
    }

    #[test]
    fn unknown_capability_is_rejected() {
        let manifest = r#"
script_version = "1"
[meta]
id = "vendor/test/script"
name = "Test script"
[engine]
runtime = "js"
[permissions]
capabilities = ["shell"]
"#;
        let bundle = bundle_of(manifest, vec![("src/main.js", "modspec.log('hi');")]);
        let error = validate_script_bundle(&bundle).unwrap_err();
        assert!(error.to_string().contains("undeclared capability"));
    }

    #[test]
    fn path_traversal_file_is_rejected() {
        let bundle = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/main.js", "x"), ("../evil.js", "y")],
        );
        let error = validate_script_bundle(&bundle).unwrap_err();
        assert!(error.to_string().contains("invalid file name"));
    }

    #[test]
    fn absolute_path_file_is_rejected() {
        let bundle = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/main.js", "x"), ("/etc/hosts", "y")],
        );
        assert!(validate_script_bundle(&bundle).is_err());
    }

    #[test]
    fn duplicate_files_are_rejected() {
        let bundle = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/main.js", "x"), ("src/main.js", "y")],
        );
        let error = validate_script_bundle(&bundle).unwrap_err();
        assert!(error.to_string().contains("duplicate file"));
    }

    #[test]
    fn oversized_file_is_rejected() {
        let big = "x".repeat(MAX_FILE_BYTES + 1);
        let bundle = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/main.js", &big)],
        );
        assert!(validate_script_bundle(&bundle).is_err());
    }

    #[test]
    fn oversized_bundle_total_is_rejected() {
        let manifest = valid_manifest("vendor/test/script", "js");
        let files: Vec<ScriptFile> = (0..MAX_FILES)
            .map(|i| {
                let name = format!("src/f{i:02}.js");
                let content = "x".repeat(MAX_FILE_BYTES);
                ScriptFile { name, content }
            })
            .collect();
        let bundle = ScriptBundle {
            manifest: toml::from_str(&manifest).unwrap(),
            manifest_raw: manifest,
            files,
        };
        assert!(validate_script_bundle(&bundle).is_err());
    }

    #[test]
    fn invalid_package_is_rejected() {
        let manifest = r#"
script_version = "1"
[meta]
id = "vendor/test/script"
name = "Test script"
[compatible]
packages = [".. bad .."]
[engine]
runtime = "js"
"#;
        let bundle = bundle_of(manifest, vec![("src/main.js", "x")]);
        assert!(validate_script_bundle(&bundle).is_err());
    }

    #[test]
    fn content_hash_is_deterministic_and_order_independent() {
        let a = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/main.js", "x"), ("src/lib.js", "y")],
        );
        let b = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/lib.js", "y"), ("src/main.js", "x")],
        );
        assert_eq!(a.content_hash(), b.content_hash());

        let changed = bundle_of(
            &valid_manifest("vendor/test/script", "js"),
            vec![("src/main.js", "z"), ("src/lib.js", "y")],
        );
        assert_ne!(a.content_hash(), changed.content_hash());
    }

    #[test]
    fn from_dir_round_trip() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/script-bundle");
        if !dir.exists() {
            return; // fixture absent — nothing to assert
        }
        let bundle = ScriptBundle::from_dir(&dir).unwrap();
        validate_script_bundle(&bundle).unwrap();
        assert_eq!(bundle.entrypoint(), "src/main.js");
    }
}
