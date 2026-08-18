//! Typed app inventory + lifecycle: `modspec app list|info|restart|trigger`.

use anyhow::{bail, Context, Result};
use modspec_protocol::{
    is_valid_package_name, methods, validate_trigger_target, AppInfoParams, AppListParams,
    AppScope, RestartTargetsParams, TriggerAppParams,
};

use crate::commands::resolve_authorized_client;
use crate::AppAction;

pub async fn run(action: AppAction) -> Result<()> {
    match action {
        AppAction::List {
            device,
            scope,
            limit,
            filter,
            offline,
        } => app_list(device.as_deref(), &scope, limit, filter.as_deref(), offline).await,
        AppAction::Info {
            package,
            device,
            offline,
        } => app_info(&package, device.as_deref(), offline).await,
        AppAction::Restart { package, device } => app_restart(&package, device.as_deref()).await,
        AppAction::Trigger {
            package,
            component,
            device,
        } => app_trigger(&package, component.as_deref(), device.as_deref()).await,
    }
}

fn require_valid_package(package: &str) -> Result<()> {
    if !is_valid_package_name(package) {
        bail!("invalid package name: {package:?}");
    }
    Ok(())
}

async fn app_list(
    device_id: Option<&str>,
    scope: &str,
    limit: u32,
    filter: Option<&str>,
    offline: bool,
) -> Result<()> {
    let (device, mut client) = resolve_authorized_client(device_id, offline)?;
    let params = AppListParams {
        scope: match scope {
            "system" => AppScope::System,
            "user" => AppScope::User,
            _ => AppScope::All,
        },
        limit: limit.clamp(1, 2000),
        filter: filter.map(str::to_string),
    };
    if offline {
        let req = client.build_request(methods::APP_LIST, serde_json::to_value(&params)?);
        println!("offline: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        return Ok(());
    }
    client.connect().await?;
    let apps = client.app_list(&params).await?;
    println!(
        "device={} scope={} total={} system={} user={} returned={} truncated={}",
        device.id,
        apps.scope.as_str(),
        apps.total,
        apps.system,
        apps.user,
        apps.returned,
        apps.truncated
    );
    for app in apps.entries {
        println!(
            "{}\t{}\t{}\t{}\t{}",
            app.package,
            app.version_name.as_deref().unwrap_or("-"),
            app.version_code,
            if app.system { "system" } else { "user" },
            if app.enabled { "enabled" } else { "disabled" },
        );
    }
    Ok(())
}

async fn app_info(package: &str, device_id: Option<&str>, offline: bool) -> Result<()> {
    require_valid_package(package)?;
    let (_device, mut client) = resolve_authorized_client(device_id, offline)?;
    let params = AppInfoParams {
        package: package.into(),
    };
    if offline {
        let req = client.build_request(methods::APP_INFO, serde_json::to_value(&params)?);
        println!("offline: would send RPC to {}", client.http_url());
        println!("{}", serde_json::to_string_pretty(&req)?);
        return Ok(());
    }
    client.connect().await?;
    let info = client.app_info(&params).await?;
    println!("{}", serde_json::to_string_pretty(&info)?);
    Ok(())
}

async fn app_restart(package: &str, device_id: Option<&str>) -> Result<()> {
    require_valid_package(package)?;
    if matches!(package, "system" | "android") {
        bail!("system-server packages cannot be force-stopped by `app restart`");
    }
    let (device, mut client) = resolve_authorized_client(device_id, false)?;
    device.require_auth()?;
    client.connect().await?;
    let resp = client
        .restart_targets(&RestartTargetsParams {
            packages: vec![package.into()],
        })
        .await
        .with_context(|| format!("restart {package}"))?;
    for restarted in &resp.restarted {
        println!("target_restarted package={restarted}");
    }
    for stopped in &resp.needs_trigger {
        println!("target_stopped package={stopped} action=trigger_manually (no launcher; use `modspec app trigger {stopped}` or `--component`)");
    }
    for missing in &resp.not_installed {
        println!("target_not_installed package={missing}");
    }
    for failed in &resp.launch_failed {
        println!("launch_failed package={failed}");
    }
    if !resp.failed.is_empty() {
        for (package, message) in &resp.failed {
            println!("restart_failed package={package} message={message}");
        }
        bail!("failed to restart {package}");
    }
    Ok(())
}

async fn app_trigger(
    package: &str,
    component: Option<&str>,
    device_id: Option<&str>,
) -> Result<()> {
    validate_trigger_target(package, component).map_err(anyhow::Error::msg)?;
    let (device, mut client) = resolve_authorized_client(device_id, false)?;
    device.require_auth()?;
    client.connect().await?;
    let resp = client
        .trigger_app(&TriggerAppParams {
            package: package.into(),
            component: component.map(str::to_string),
        })
        .await
        .with_context(|| format!("trigger {package}"))?;
    println!(
        "trigger package={} launched={} method={} needs_trigger={} message={}",
        resp.package, resp.launched, resp.method, resp.needs_trigger, resp.message
    );
    if resp.needs_trigger {
        bail!(
            "{} has no launcher activity and no --component was given; supply `--component {}/.Class` explicitly",
            resp.package, resp.package
        );
    }
    if !resp.launched {
        bail!("{} could not be started: {}", resp.package, resp.message);
    }
    Ok(())
}
