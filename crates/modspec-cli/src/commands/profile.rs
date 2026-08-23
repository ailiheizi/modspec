use std::collections::HashSet;
use std::path::PathBuf;

use anyhow::{Context, Result};
use modspec_core::{validate_profile, DeviceStore, ModEntry, Profile, ProfileState, VerifySource};
use modspec_protocol::{methods, ApplyProfileParams, CollectLogsParams, VerifyParams};

use crate::commands::connect_device;
use crate::ProfileAction;

pub async fn run(action: ProfileAction) -> Result<()> {
    match action {
        ProfileAction::Validate { path } => validate_profile_file(&path),
        ProfileAction::Apply {
            path,
            device,
            serial,
            dry_run,
            offline,
        } => {
            profile_apply(
                &path,
                device.as_deref(),
                serial.as_deref(),
                dry_run,
                offline,
            )
            .await
        }
        ProfileAction::Diff { path, device } => profile_diff(&path, device.as_deref()),
        ProfileAction::Verify {
            path,
            device,
            serial,
            no_bootstrap,
        } => profile_verify(&path, device.as_deref(), serial.as_deref(), no_bootstrap).await,
    }
}

fn validate_profile_file(path: &str) -> Result<()> {
    let profile = Profile::from_file(path).with_context(|| format!("read profile {path}"))?;
    validate_profile(&profile)?;
    println!(
        "OK profile {} ({} mods)",
        profile.meta.id,
        profile.mods.len()
    );
    for warning in modspec_core::profile_lint_warnings(&profile) {
        println!("warning: {warning}");
    }
    Ok(())
}

async fn profile_apply(
    path: &str,
    device_id: Option<&str>,
    serial: Option<&str>,
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
    if !dry_run {
        // Applying a profile mutates device state — require an active pairing token.
        device.require_auth()?;
    }

    if offline || dry_run {
        let mut client = modspec_protocol::RpcClient::from_device(
            &device.host,
            device.http_port,
            device.ws_port,
        )
        .with_auth_token(device.auth_token.clone());
        client.set_offline(offline);
        let req = client.build_request(methods::APPLY_PROFILE, serde_json::to_value(&params)?);
        let label = if dry_run { "dry-run" } else { "offline" };
        println!("{label}: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        if dry_run {
            print_apply_plan(&profile);
        }
        return Ok(());
    }

    // Preflight + repair before a mutating apply. Bootstrap is intentionally
    // NOT automatic here: an apply must not silently relaunch the Agent app.
    let (client, status) = connect_device(
        device,
        serial.map(str::to_string),
        false,
        std::time::Duration::from_secs(3),
        2,
    )
    .await?;
    if !status.is_healthy() {
        anyhow::bail!(
            "connection failed (issue={}): {}",
            status.issue.as_str(),
            status.detail
        );
    }

    match client.apply_profile(&params).await {
        Ok(resp) => {
            println!("apply started: job_id={}", resp.job_id);
            save_known_state(&device.id, &profile)?;
        }
        Err(e) => {
            anyhow::bail!("apply failed on {} ({}): {e}", device.id, device.host);
        }
    }
    Ok(())
}

async fn profile_verify(
    path: &str,
    device_id: Option<&str>,
    serial: Option<&str>,
    no_bootstrap: bool,
) -> Result<()> {
    let profile = Profile::from_file(path).with_context(|| format!("read profile {path}"))?;
    validate_profile(&profile)?;

    let store = DeviceStore::default();
    let config = store.load()?;
    let device = DeviceStore::resolve_device(&config, device_id)?;
    device.require_auth()?;

    // Preflight + repair (and optional Agent bootstrap) before the read-only
    // verify, so a stale adb forward cannot hang the RPC below.
    let (client, status) = connect_device(
        device,
        serial.map(str::to_string),
        !no_bootstrap,
        std::time::Duration::from_secs(3),
        2,
    )
    .await?;
    if !status.is_healthy() {
        anyhow::bail!(
            "connection failed (issue={}): {}",
            status.issue.as_str(),
            status.detail
        );
    }

    let mut ok = 0_u32;
    let mut failed = 0_u32;
    let mut skipped = 0_u32;

    // 1. Agent-side drift report.
    let drift = client
        .verify(&VerifyParams {
            profile_id: Some(profile.meta.id.clone()),
        })
        .await
        .map_err(|e| anyhow::anyhow!("verify failed on {} ({}): {e}", device.id, device.host))?
        .drift;
    for item in &drift {
        println!(
            "verify drift mod_id={} kind={} expected={} actual={} reason={}",
            item.mod_id.as_deref().unwrap_or("-"),
            item.kind.as_deref().unwrap_or("-"),
            item.expected.as_deref().unwrap_or("-"),
            item.actual.as_deref().unwrap_or("-"),
            item.reason.as_deref().unwrap_or("-"),
        );
        failed += 1;
    }

    // 2. Read-only diagnostics checks against the local profile expectations.
    let diagnostics = client.module_diagnostics().await?;
    if let Some(generation) = diagnostics.rules_generation {
        println!("verify info rules_generation={generation}");
        ok += 1;
    }
    let scope: HashSet<&str> = diagnostics.scope.iter().map(String::as_str).collect();
    let active_rules: HashSet<&str> = diagnostics
        .active_rules
        .iter()
        .map(String::as_str)
        .collect();
    for m in &profile.mods {
        if !m.enabled() {
            continue;
        }
        match m {
            ModEntry::Scope { id, apps, .. } => {
                let missing: Vec<&str> = apps
                    .iter()
                    .filter(|app| !scope.contains(app.as_str()))
                    .map(String::as_str)
                    .collect();
                if missing.is_empty() {
                    println!("verify ok scope {id}");
                    ok += 1;
                } else {
                    println!("verify failed scope {id} missing={}", missing.join(","));
                    failed += 1;
                }
            }
            ModEntry::RuleRef { id, rule, .. } => {
                if active_rules.contains(rule.as_str()) {
                    println!("verify ok rule {id}");
                    ok += 1;
                } else {
                    println!("verify failed rule {id} missing={rule}");
                    failed += 1;
                }
            }
            _ => {}
        }
    }

    // 3. `[verify]` section: lsposed_log pattern checks against the hook journal.
    if let Some(config) = &profile.verify {
        for check in &config.checks {
            if check.source != VerifySource::LsposedLog {
                skipped += 1;
                continue;
            }
            let Some(pattern) = check.pattern.as_deref() else {
                skipped += 1;
                continue;
            };
            let logs = client
                .collect_logs(&CollectLogsParams {
                    exact_generation: diagnostics.rules_generation,
                    limit: 500,
                    ..Default::default()
                })
                .await
                .map_err(|e| {
                    anyhow::anyhow!(
                        "collect_logs failed on {} ({}): {e}",
                        device.id,
                        device.host
                    )
                })?;
            let found = logs.entries.iter().any(|entry| {
                entry
                    .message
                    .to_lowercase()
                    .contains(&pattern.to_lowercase())
            });
            if found {
                println!("verify ok lsposed_log {} pattern={}", check.mod_id, pattern);
                ok += 1;
            } else {
                println!(
                    "verify failed lsposed_log {} pattern={}",
                    check.mod_id, pattern
                );
                failed += 1;
            }
        }
    }

    println!("verify_result ok={ok} failed={failed} skipped={skipped}");
    if failed > 0 {
        anyhow::bail!("verify failed: {failed} check(s) drifted or missing");
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
            println!(
                "  + {} ({})",
                m.id(),
                if m.enabled() { "enabled" } else { "disabled" }
            );
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
                    if desired_enabled {
                        "enabled"
                    } else {
                        "disabled"
                    }
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
    println!(
        "apply plan for {} ({} mods):",
        profile.meta.id,
        profile.mods.len()
    );
    for m in &profile.mods {
        let category = m
            .common()
            .category
            .as_deref()
            .map(str::trim)
            .filter(|c| !c.is_empty())
            .unwrap_or("-");
        println!("  - {} [{}] @{}", m.id(), mod_type_label(m), category);
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
        ModEntry::ShellToggle { .. } => "shell_toggle",
    }
}

fn config_root() -> PathBuf {
    DeviceStore::default()
        .path()
        .parent()
        .unwrap_or(std::path::Path::new("."))
        .to_path_buf()
}

fn known_state_path(device_id: &str, profile_id: &str) -> PathBuf {
    config_root()
        .join("states")
        .join(device_id)
        .join(format!("{profile_id}.json"))
}

fn save_known_state(device_id: &str, profile: &Profile) -> Result<()> {
    let path = known_state_path(device_id, &profile.meta.id);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut state = ProfileState {
        active_profile: Some(profile.meta.id.clone()),
        ..ProfileState::default()
    };
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
