use std::collections::HashSet;
use std::path::PathBuf;

use anyhow::{Context, Result};
use modspec_core::{
    DeviceStore, ModEntry, Profile, ProfileState, validate_profile,
};
use modspec_protocol::{methods, ApplyProfileParams, RpcClient};

use crate::ProfileAction;

pub async fn run(action: ProfileAction) -> Result<()> {
    match action {
        ProfileAction::Validate { path } => validate_profile_file(&path),
        ProfileAction::Apply {
            path,
            device,
            dry_run,
            offline,
        } => profile_apply(&path, device.as_deref(), dry_run, offline).await,
        ProfileAction::Diff { path, device } => profile_diff(&path, device.as_deref()),
    }
}

fn validate_profile_file(path: &str) -> Result<()> {
    let profile = Profile::from_file(path).with_context(|| format!("read profile {path}"))?;
    validate_profile(&profile)?;
    println!("OK profile {} ({} mods)", profile.meta.id, profile.mods.len());
    Ok(())
}

async fn profile_apply(
    path: &str,
    device_id: Option<&str>,
    dry_run: bool,
    offline: bool,
) -> Result<()> {
    let profile = Profile::from_file(path).with_context(|| format!("read profile {path}"))?;
    validate_profile(&profile)?;

    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)?;

    let params = ApplyProfileParams {
        profile: profile.clone(),
        dry_run,
        only: vec![],
    };

    let mut client = RpcClient::from_device(&device.host, device.http_port, device.ws_port);
    client.set_offline(offline);

    if offline || dry_run {
        let req = client.build_request(
            methods::APPLY_PROFILE,
            serde_json::to_value(&params)?,
        );
        let label = if dry_run { "dry-run" } else { "offline" };
        println!("{label}: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        if dry_run {
            print_apply_plan(&profile);
        }
        return Ok(());
    }

    client
        .connect()
        .await
        .with_context(|| format!("connect to {}", device.host))?;
    match client.apply_profile(&params).await {
        Ok(resp) => {
            println!("apply started: job_id={}", resp.job_id);
            save_known_state(&device.id, &profile)?;
        }
        Err(e) => {
            anyhow::bail!(
                "apply failed on {} ({}): {e}",
                device.id,
                device.host
            );
        }
    }
    Ok(())
}

fn profile_diff(path: &str, device_id: Option<&str>) -> Result<()> {
    let profile = Profile::from_file(path).with_context(|| format!("read profile {path}"))?;
    validate_profile(&profile)?;

    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)?;

    let state_path = known_state_path(&device.id, &profile.meta.id);
    if !state_path.exists() {
        println!(
            "no last-known state at {} — all {} mods are new",
            state_path.display(),
            profile.mods.len()
        );
        for m in &profile.mods {
            println!("  + {} ({})", m.id(), if m.enabled() { "enabled" } else { "disabled" });
        }
        return Ok(());
    }

    let content = std::fs::read_to_string(&state_path)
        .with_context(|| format!("read state {}", state_path.display()))?;
    let known = ProfileState::from_json(&content)?;

    let mut diffs = Vec::new();
    for m in &profile.mods {
        let desired_enabled = m.enabled();
        match known.items.get(m.id()) {
            Some(item) if item.enabled != desired_enabled => {
                diffs.push(format!(
                    "  ~ {} enabled: {} → {}",
                    m.id(),
                    item.enabled,
                    desired_enabled
                ));
            }
            None => {
                diffs.push(format!(
                    "  + {} ({})",
                    m.id(),
                    if desired_enabled { "enabled" } else { "disabled" }
                ));
            }
            _ => {}
        }
    }

    let profile_ids: HashSet<_> = profile.mod_ids().into_iter().collect();
    for (mod_id, item) in &known.items {
        if !profile_ids.contains(mod_id.as_str()) {
            diffs.push(format!("  - {} (was enabled={})", mod_id, item.enabled));
        }
    }

    if known.active_profile.as_deref() != Some(profile.meta.id.as_str()) {
        diffs.insert(
            0,
            format!(
                "  ~ active_profile: {:?} → {}",
                known.active_profile, profile.meta.id
            ),
        );
    }

    if diffs.is_empty() {
        println!("profile {} matches last-known state", profile.meta.id);
    } else {
        println!("diff {} vs {}:", profile.meta.id, state_path.display());
        for d in diffs {
            println!("{d}");
        }
    }
    Ok(())
}

fn print_apply_plan(profile: &Profile) {
    println!("apply plan for {} ({} mods):", profile.meta.id, profile.mods.len());
    for m in &profile.mods {
        println!("  - {} [{}]", m.id(), mod_type_label(m));
    }
}

fn mod_type_label(m: &ModEntry) -> &'static str {
    match m {
        ModEntry::LsposedModule { .. } => "lsposed_module",
        ModEntry::Scope { .. } => "scope",
        ModEntry::ModuleRef { .. } => "module_ref",
        ModEntry::ModulePrefs { .. } => "module_prefs",
        ModEntry::RuleRef { .. } => "rule_ref",
        ModEntry::Hook { .. } => "hook",
        ModEntry::DynamicScope { .. } => "dynamic_scope",
        ModEntry::RemotePrefs { .. } => "remote_prefs",
        ModEntry::RemoteBlob { .. } => "remote_blob",
        ModEntry::LsposedRestore { .. } => "lsposed_restore",
        ModEntry::Reload { .. } => "reload",
        ModEntry::PostAction { .. } => "post_action",
    }
}

fn config_root() -> PathBuf {
    DeviceStore::default().path().parent().unwrap_or(std::path::Path::new(".")).to_path_buf()
}

fn known_state_path(device_id: &str, profile_id: &str) -> PathBuf {
    config_root().join("states").join(device_id).join(format!("{profile_id}.json"))
}

fn save_known_state(device_id: &str, profile: &Profile) -> Result<()> {
    let path = known_state_path(device_id, &profile.meta.id);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut state = ProfileState::default();
    state.active_profile = Some(profile.meta.id.clone());
    for m in &profile.mods {
        state.items.insert(
            m.id().to_string(),
            modspec_core::ItemState {
                enabled: m.enabled(),
                status: modspec_core::ApplyStatus::Pending,
                changes: vec![],
                hook_ids: vec![],
                last_verify: None,
                last_error: None,
            },
        );
    }
    std::fs::write(&path, state.to_json_pretty()?)?;
    Ok(())
}
