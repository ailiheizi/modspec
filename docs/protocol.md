# ModSpec Protocol (draft v0.1)

## Transport

| Channel | Port | Purpose |
|---------|------|---------|
| HTTP | 8764 | Pairing, `/health`, JSON-RPC 2.0 `/rpc`（当前主路径） |
| WebSocket | 8765 | JSON-RPC 2.0 `/rpc`（可用，暂非 CLI 默认） |

开发版 Agent 只监听 `127.0.0.1`。PC 通过 `adb forward` 访问，避免未认证 RPC 暴露到局域网：

```bash
adb forward tcp:9876 tcp:8764
modspec pair scan --code 123456 --host 127.0.0.1 --port 9876
```

配对使用手机显示的 6 位码并返回高熵 `auth_token`；CLI 保存 token，后续 HTTP 使用 `Authorization: Bearer ...`，WS 握手使用同名 header。重新配对会轮换 token。TLS 和显式吊销 UX 属于后续 transport hardening。

未携带 token / token 失效的 RPC 返回 HTTP 401/403，客户端映射为 `Unauthorized` 错误并提示重新配对。旧版（pre-token）`devices.toml` 记录仍可正常解析，但 `auth_token == None` 时 PC 拒绝执行危险 RPC（`deploy_rule`、`restart_targets`、`apply_profile`、`toggle_mod`），必须重新配对。

## 连接可靠性与故障分类（`modspec connect`）

PC 侧连接管理器（`modspec-protocol::connection` + CLI `modspec connect`）用 **短超时健康预检** 取代裸 RPC 的 30s 悬挂：

1. `GET /health`（默认 3s 超时，`--timeout` 可调）→ 2. 授权 `ping` 探针（检测 token 失效）→ 3. 按主机分类。

| 分类 (`issue`) | 触发 | PC 动作 |
|----------------|------|---------|
| `healthy` | `/health` + 授权 ping 通过 | 无 |
| `stale_forward` | loopback（`127.0.0.1`/`localhost`/`::1`）不可达（超时或拒连） | 重建 `adb forward tcp:<local> tcp:8764`（最多 `--retries` 次）并重试预检 |
| `agent_unreachable` | 非 loopback 不可达，或重建后仍不可达 | 提示检查 Agent App / USB |
| `unauthorized` | 可达但授权 ping 401 | 提示重新配对（`modspec pair scan`） |

重建 forward 仍不可达时，`modspec connect`（以及 `rule run`，`--no-bootstrap` 可关）**可选地拉起 Agent 的导出 MainActivity**（`adb shell am start -n com.modspec.agent/.MainActivity`，`modspec-adb::bootstrap_agent`），然后按预算（默认 8s）轮询直到 Agent 恢复。`profile apply` 也做预检+forward 修复，但**不**自动 bootstrap（变更操作不隐式拉活 App）。原始 `adb forward` 只是实现细节。

**重试策略**：只有**只读、幂等**的调用（`ping`、`get_status`、`inspect_device`、`app_list/info`、`process_list`、`get_logs`、`module_diagnostics`、`collect_logs`、`script_list`、`script_validate`）在**传输层**错误（`Transport`）下可自动重试（有界）。`deploy_rule`/`apply_profile`/`restart_targets`/`trigger_app`/`soft_restart`/`script_deploy`/`script_enable`/`script_disable`/`script_remove`/`script_reload` 等变更调用**绝不自动重试**：变更携带 `request_id` 幂等键，Agent 对已见过的 `request_id` 重放已存响应（LRU 64），因此重连后重发同一变更不会产生重复副作用。认证失败（`Unauthorized`）任何情况下都不重试。

## RPC Methods

| Method | Params | Result |
|--------|--------|--------|
| `ping` | — | `{ "pong": true }` |
| `get_status` | — | `DeviceStatus`（含 `server_http_alive` / `server_ws_alive`） |
| `inspect_device` | `{ include_apps?, app_limit? }` | 硬件/软件/显示/内存/存储/运行时 + 有界 app 摘要 |
| `app_list` | `{ scope?: all\|system\|user, limit?, filter? }` | 有界包清单 + total/system/user/truncated |
| `app_info` | `{ package }` | 单包结构化详情（版本/system/enabled/installer/Launcher/组件统计/uid） |
| `process_list` | `{ package?, limit? }` | `ps` 进程清单（package/pid/uid/state/name） |
| `trigger_app` | `{ package, component? }` | 显式启动：Launcher 或 `--component`；无 Launcher → `needs_trigger` |
| `get_logs` | `{ package?, tag?, limit?, since_ms? }` | 有界原始 logcat 诊断（与 Hook 事件环分离） |
| `module_diagnostics` | — | LSPosed/模块只读诊断（框架、scope、active_rules、generation、事件源、**服务器健康**：`server_http_alive`/`server_ws_alive`/`server_http_restarts`/`server_ws_restarts`/`server_last_error`） |
| `apply_profile` | `ApplyProfileParams` | `{ job_id }` |
| `toggle_mod` | `ToggleModParams` | `{ ok }` |
| `verify` | `{ profile_id? }` | drift list（Agent 侧真实评估：active_profile 匹配、item 状态、rule_ref∈active_rules、scope CLI 可用性） |
| `reapply` | `{ only_failed? }` | `{ job_id }` |
| `soft_restart` | `{ rules_only? }` | `{ hot_reload_ok, hot_reload_failed, hot_reload_unsupported, running_targets[], restarted_packages[], message }` |
| `collect_logs` | `{ after_event_id?, limit?, rule_id?, script_id?, min_generation?, exact_generation?, since_ms? }` | structured entries + event_id cursor |
| `deploy_rule` | `{ rule_id, content, packages, ensure_scope }` | durable/publish/scope ack |
| `restart_targets` | `{ packages }` | restarted / needs_trigger / not_installed / launch_failed / failed |
| `soft_restart` | `{ rules_only? }` | hot reload / fallback summary |
| `script_validate` | `{ manifest, files[] }` | `{ ok, errors[] }`（只读，可重试） |
| `script_deploy` | `{ request_id, script_id, manifest, files[], ensure_scope?, activate? }` | stored / publish_mode / generation / engine / content_hash / scope_status |
| `script_list` | — | `{ scripts[], active_script }`（只读，可重试） |
| `script_enable` | `{ request_id, script_id, exclusive? }` | enabled / disabled[] / generation |
| `script_disable` | `{ request_id, script_id }` | disabled / generation |
| `script_remove` | `{ request_id, script_id }` | removed / generation |
| `script_reload` | `{ request_id, script_id, restart? }` | reload_started / generation / restart 诊断字段 |
| `install_frida_gadget` | `{ }` | 按需安装 Frida gadget（从 `/data/local/tmp/modspec/frida/` staging 移入模块 APK 目录并打 `apk_data_file` 标签）；PC 侧由 `modspec script run/deploy` 在检测到 `frida` 能力时自动完成 push+调用 |

### Frida 原生层（按需下发，不内置 APK）

脚本包可声明 `capabilities = ["frida"]` + `[frida] script = "src/frida.js"`：

```toml
[permissions]
capabilities = ["emit", "log", "frida"]

[frida]
script = "src/frida.js"
```

- **下发流程**：PC 缓存 `~/.cache/modspec/libfrida-gadget-<abi>.so` → `adb push` 到设备 staging → RPC `install_frida_gadget` → agent（root）移入模块 APK 目录（`modspec_lib`/`apk_data_file` 标签，与 DexKit 同模式）→ 同时写入 gadget 配置（指向 active 脚本的 `frida.js`）。
- **执行**：hook 进程加载含 `frida` 能力的脚本时 `System.load` gadget → gadget 自动执行 `frida.js`（QuickJS 引擎，可 `Interceptor.attach` 任意 so 导出/地址、读写内存）。
- **事件回传**：`frida.js` 内用 `android.util.Log.i("ModspecScript", <JSON>)` 写结构化事件，经现有 logcat→journal 管道摄入（无需新通道）；`Java.perform` 可用于 Java 互操作。
- **边界**：gadget（LGPL-2.1）按需下发、显式能力声明；未声明 `frida` 能力的脚本永不加载 gadget；不支持时降级为 `script_message`。


### 只读清单/诊断的通用约定

- **默认只读**：`inspect_device`、`app_list`、`app_info`、`process_list`、`get_logs`、`module_diagnostics` 不修改任何设备状态。
- **有界**：`app_limit`/`limit` 由 Agent 硬上限（app/process 2000，logs 1000），超限时 `truncated=true`。
- **不暴露个人数据**：清单只含 package/version/system/enabled/组件统计/安装来源；不含电池、流量、权限明细、配对密钥。
- **包名校验**：PC 与 Agent 双侧校验（`[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+`）；`trigger` 的 `component` 必须属于目标包（`pkg/.Class` 或 `pkg/pkg.Class`），`system`/`android`/`system_server` 一律拒绝。
- **Hook 事件与原始日志分离**：结构化 Hook 事件走 `collect_logs`（event_id 游标、代次隔离）；原始 logcat 走 `get_logs`（package/tag/since 过滤 + `resolved_pids` + `root_available`）。

### inspect_device 结果形状（节选）

```json
{
  "hardware": { "manufacturer": "Xiaomi", "model": "M2102J2SC", "soc_manufacturer": "Qualcomm", "soc_model": "SM8350", "cpu_abis": ["arm64-v8a"], "cpu_cores": 8, "…": "…" },
  "software": { "android_release": "13", "sdk_int": 33, "security_patch": "2023-01-01", "fingerprint": "…" },
  "display": { "width_pixels": 1080, "height_pixels": 2400, "density_dpi": 440, "refresh_rate_hz": 120.0 },
  "memory": { "total_bytes": 12884901888, "available_bytes": 6442450944, "low_memory": false },
  "storage": { "internal_total_bytes": 251900149760, "internal_available_bytes": 171798691840 },
  "runtime": { "root_available": true, "xposed_service_bound": true, "lsposed_framework": "LSPosed-mod …", "agent_version": "0.1.0" },
  "apps": { "total": 320, "system": 210, "user": 110, "returned": 2, "truncated": true, "entries": [ { "package": "…", "version_name": "…", "version_code": 1, "system": false, "enabled": true } ] }
}
```

### trigger_app 语义

| 条件 | 结果 |
|------|------|
| 有 Launcher 且 `monkey` 注入成功 | `launched=true, method="launcher"` |
| 无 Launcher 且未给 `component` | `launched=false, needs_trigger=true`（PC 提示用户显式给 `--component`，Agent 绝不猜组件） |
| 给了 `component` 且 `am start` 成功 | `launched=true, method="component"` |
| 组件不属于目标包 / 非法 | RPC error `-32602` |
| `system`/`android`/`system_server` | RPC error `-32602` |

## collect_logs：Agent 自有事件环 + 不透明游标

`collect_logs` 的 cursor 是 **Agent 自有的单调 `event_id`**（不是 logcat 毫秒时间戳）。Agent 进程内维护一个有界结构化事件环（默认 2000 条，线程安全），并持久化到 App 私有目录的追加式 journal（`files/events/events.ndjson`，超限时原子旋转）。事件同时写 logcat 与 journal；Agent 后台 tailer 从 logcat 摄入 Hook 进程事件（Hook 进程在 libxposed API 102 下只能写 logcat/LSPosed log，没有 hook→service 写通道）。因此批量边界不会重复/丢失事件；只有 PC 游标早于环内最旧事件时才报告 `truncated`。

```json
{
  "entries": [{
    "event_id": 152,
    "timestamp_ms": 1700000000123,
    "level": "I",
    "tag": "ModspecScript",
    "event": "script_loaded",
    "generation": 1700000000000,
    "rule_id": null,
    "script_id": "xiaomi/security-center/macro-gate",
    "package": "com.miui.securitycenter",
    "message": "hook installed: O3$b.h(java.lang.String)"
  }],
  "next_event_id": 152,
  "first_event_id": 1,
  "truncated": false,
  "source": "journal"
}
```

| 字段 | 含义 |
|------|------|
| `entries[].event_id` | Agent 分配的单调 id |
| `next_event_id` | 下一轮 poll 应回传的 cursor（本批最后一条 `event_id`；无匹配时等于请求的 `after_event_id`） |
| `first_event_id` | 环内最旧保留的 id；`truncated == true` 时表示历史已被轮换丢弃 |
| `truncated` | `after_event_id < first_event_id` 时置真，PC 应从 `first_event_id` 重新开始或提示缺口 |
| `source` | `"journal"`（持久化 journal）或 `"ring_only"`（journal 写失败降级） |

`since_ms` 仅作为**弃用的兼容过滤**（旧版毫秒游标），`after_event_id` 存在时以它为准。PC 交互 session 总是用 `after_event_id` 增量拉取，并叠加 `exact_generation` 做精确代次隔离。

### 轮询重试与游标续传

`rule run` 的 `collect_logs` 轮询在**传输层**瞬时失败（连接重置/超时/5xx）时自动重试（默认最多 3 次，退避 200ms×N，`RuleSessionParams.collect_retries` 可调）。因为重试请求携带**同一个 `after_event_id`**，且 PC 侧按 `event_id` 去重（每个事件每 session 至多上报一次），重连后**不会重复、不会丢失**事件。变更调用（deploy/restart）不在轮询路径内，永不自动重试；轮询重试耗尽后返回显式 `SessionError::Collect`，规则保留在设备上可续跑。重试行为以 `poll_retry attempt=N` 事件行上报，Ctrl-C 随时确定性退出（不残留 attach 进程）。

## restart_targets 诊断

| 字段 | 含义 |
|------|------|
| `restarted` | force-stop 成功且 Launcher 已重新拉起 |
| `needs_trigger` | force-stop 成功但无 Launcher activity，需手动触发 |
| `not_installed` | 目标未安装，未执行 force-stop（先验 `pm path`） |
| `launch_failed` | 已 force-stop 但启动命令报错 |
| `failed` | force-stop 本身失败（package → 错误消息） |

`system`/`android` 永不被 force-stop。启动/作用域失败以 RPC error 或上表字段反馈，**不会**伪装成 Hook `hook_error` 事件。

## verify：drift 报告

`verify` 返回 `{ "drift": [...] }`，每个条目是 Agent 对已应用状态与期望目标（上次 apply 的 profile / 设备状态）的差异：

```json
{
  "drift": [
    { "mod_id": "game-mode", "kind": "rule_ref", "expected": "xiaomi/joyose/block-cloud-fetch", "actual": "", "reason": "rule not active on device" },
    { "mod_id": "hyper-perf-pack", "kind": "profile", "expected": "hyper-perf-pack", "actual": "none", "reason": "active profile mismatch" }
  ]
}
```

| 字段 | 含义 |
|------|------|
| `mod_id` | 漂移的 profile mod id（`profile` 类目时为请求的 profile_id） |
| `kind` | `profile`（active_profile 不匹配）/ `item`（item 状态非 applied：failed/manual/drifted，附 `last_error`）/ `rule_ref`（规则不在 active_rules）/ `scope`（LSPosed CLI 不可用无法保证 scope） |
| `expected` / `actual` | 期望值与设备实际值（均为字符串，便于 PC 展示与排序） |
| `reason` | 人类可读原因 |

无漂移时返回空数组。该 RPC 只读，属于 PC 可重试集（`profile verify` 同时叠加 `module_diagnostics` 的 scope⊆ 检查与 `[verify] lsposed_log` 模式经 `collect_logs` 匹配）。

## Events (notifications)

- `apply_progress`, `apply_completed`, `apply_failed`
- `state_changed`, `boot_completed`
- 最小调试事件：`rule_uploaded`、`reload_started`、`hook_loaded`、`target_restarted`、`hook_hit`、`hook_error`
- 脚本事件：`script_uploaded`、`script_enabled`、`script_disabled`、`script_reload_started`、`script_loaded`、`script_unloaded`、`script_hit`、`script_message`、`script_error`、`session_success`、`session_failure`

当前 HTTP 模式中，编排阶段由同步 RPC ack + CLI 标准事件行表达；Hook 进程事件由结构化 `collect_logs` 轮询取得（Agent 事件环 + journal）。未来 WS push 复用同一事件名与字段。`hook_*` / `script_*` 事件携带 `timestamp_ms/event_id/event/generation/rule_id?/script_id?/package/message`；早期解析阶段的错误事件可能没有 rule/script/package。`session_success`/`session_failure` 由 PC 侧 `script run` 会话在终止时打印（JSON 模式为结构化行）。

## 脚本包（script bundle）格式

脚本包 = 目录内的 `manifest.toml` + 源码文件（确定性 zip 分发，PC 与 Agent 双侧校验）：

```toml
script_version = "1"
[meta]
id = "xiaomi/security-center/macro-gate"   # 命名空间 id，规则同规则
name = "Security Center macro gate"
[compatible]
packages = ["com.miui.securitycenter"]      # 脚本注入的 Hook 进程（scope 依据）
target_packages = ["com.ChillyRoom.DungeonShooter"]  # 脚本守护的应用
oem = ["Xiaomi"]
min_android = 26
[engine]
runtime = "js"                               # js (Rhino) | lua (LuaJ)
entrypoint = "src/main.js"                   # 默认 src/main.js|main.lua
[limits]
execution_ms = 20000
callback_ms = 50
wait_class_ms = 30000
[permissions]
capabilities = ["emit", "log"]               # 只允许 emit/log；无 fs/network/root/shell
[verify]
log_success = "macro_allowed"
log_failure = "hook_error"
```

校验规则（`modspec-core::validate_script_bundle` 与 Agent `ScriptBundleValidator` 一致）：版本、id/包名格式、runtime ∈ {js,lua}、entrypoint 存在、文件名安全（拒绝绝对路径/`..`/反斜杠）、单文件 ≤512KiB、总包 ≤4MiB、文件数 ≤64、capabilities ⊆ {emit,log}。内容哈希 = SHA-256(manifest + 排序文件)，确定性、跨机器稳定。

## 脚本运行时语义（agent 侧）

- **引擎**：JS = Rhino 1.7.15（解释模式 + ES6，指令观察器实现执行预算）；Lua = LuaJ 3.0.1（Lua 5.2，无安全抢占——超预算按检测+弃置处理，文档注明）。两者纯 Java，无 native ABI 约束。
- **hook 组合**：每个方法只安装一个 libxposed 句柄（`HookRegistry` 复用），`before` 按注册序、`replace` 最后注册者生效（旧 replace 被 supersede，最新被移除后自动重臂）、`after` 按注册序；原方法至多调用一次；`ctx.callOriginal()`/`proceed()` 以当前参数调用原方法。
- **防护**：PROTECTIVE 异常模式；回调异常上报 `script_error` 并累计熔断（默认连续 10 次 → 暂停回调 30s）；回调超时看门狗（默认 50ms）；脚本 `script_error` 不崩溃目标进程。
- **能力边界**：脚本无文件系统/网络/root/shell 访问；`emit`（结构化事件，限速）与 `log`（console，限速）需在 manifest 声明。
- **延迟类**：`modspec.waitForClass(name, timeoutMs)` 在 entrypoint 线程轮询（受 manifest 预算），用于 `onPackageLoaded` 时尚不可用的类。
- **DexKit 查询**：`dexFindClass`/`dexFindMethod`（pkg/usingStrings/method/params/returnType/unique），歧义或为空时返回确定性 `script_error` 诊断而非静默吞错。

## Agent storage (on device)

```text
/data/data/com.modspec.agent/files/
  profiles/
  rules/
  scripts/                  # 脚本 zip（scripts/<safe-id>.zip）+ scripts/state.json
  state.json
  events/events.ndjson        # 事件 journal（有界、追加式、原子旋转）
```

`scripts/state.json` 持久化：`active_script`（一等公民的显式激活选择，enable 默认互斥）与每个脚本的 `hash/generation/engine/packages/target_packages/last_loaded_ms/last_hit_ms/last_error/hit_count/error_count`，由 Agent 编排写入 + logcat 摄入的 `script_*` 事件增量更新。Hook 进程通过 RemoteFile `scripts/<safe-id>.zip` 读取（无 Service 时降级 `/data/local/tmp/modspec/scripts/`）。

## Agent 服务器监督（watchdog）

`AgentService` 为 START_STICKY：进程/前台服务存活时，HTTP/WS 的 accept 循环可能因未捕获异常而死亡，此时端口仍在 LISTEN 但每个请求悬挂。`ServerSupervisor`（`rpc/ServerSupervisor.kt`）修复该场景：

- **幂等生命周期**：`start()`/`poke()` 不会重复 bind；每次 `onStartCommand`（包括 App 已存活时 `MainActivity.start()` 触发的空启动）都会 `poke()`，立即替换死亡服务器（旧实例先 `stop()` 释放陈旧 socket）。
- **看门狗**：每 5s 检查一次，空闲死亡的服务器自动重建，不杀 App。
- **可观测性**：重启计数与最近错误经 `get_status`/`module_diagnostics` 上报；WS `onError`/发送失败不再吞掉，记录 `lastError` 并打日志；HTTP `serve()` 对 handler 异常兜底返回 500，避免异常杀死 accept 线程。

## LSPosed integration

Agent shell-out to `lsposed-cli` when available ([LSPosed_mod](https://github.com/mywalkb/LSPosed_mod/wiki/CLI)):

- `modules set -e/-d <pkg>`
- `scope set -s/-a/-d <module> <scopes...>`
- `backup` / `restore`
- `log -v`

Rule hooks compiled in-agent via libxposed API 102 interceptor chain。

## 测试边界（真实设备未连接）

- **协议 / fake-Agent 测试**（本机可跑）：`modspec-protocol/tests/` 用 loopback fake Agent 真实走 HTTP transport，覆盖 `deploy_rule → restart_targets → collect_logs` 的请求参数、event_id 游标推进、认证头、401 处理，`rule run` 编排的成功/`hook_error`/重启失败/`needs_trigger`/精确代次隔离/超时，只读清单 RPC 族（`inspect_device`、`app_list`、`app_info`、`process_list`、`get_logs`、`module_diagnostics`、`trigger_app`）的请求/响应契约，以及**脚本会话**（`tests/script_session.rs`：deploy→enable→restart→loaded→hit→logs→disable 全流程、`request_id` 幂等、重连/游标续传无重复、`script_error` 中止、超时、变更不自动重试、`script_list`/`script_reload`/`script_validate` 契约）。`cargo test --workspace` 全绿。
- **连接管理器 fake 测试**（`tests/connection.rs`）：`FakeHealthServer`（可模拟"accept 但永不响应"的陈旧 forward）驱动预检分类（`stale_forward`/`agent_unreachable`/`unauthorized`）、forward 重建、bootstrap 恢复、重试耗尽、adb hint；`tests/session_reconnect.rs` 覆盖轮询瞬时失败重试（同游标、无重复、无丢失）、重连后不重复上报、deploy 传输失败**不重试**（恰好一次调用）、401 不重试、重试有界。
- **Agent JVM 单元测试**：`agent :app:testDebugUnitTest` 覆盖事件环（单调 id、有界、游标无重复丢失、journal 持久化/重播种）、`ps` 行解析与 uid 映射、logcat threadtime 解析与 package/tag/since 过滤、安装来源行解析、`ServerSupervisor` 的幂等 start/看门狗重建/poke 立即修复/失败重试/snapshot 统计/stop 幂等（fake server），以及脚本引擎（`HookRegistry` 组合语义/同方法多 hook/卸载/熔断、manifest 与 zip 校验、Rhino/LuaJ 双引擎 before/after/replace + callOriginal + 值转换、macro-gate 验收脚本包对 fake `O3$b` 只放行目标游戏）。
- **真机 E2E 未执行**：本次开发环境没有连接 Android 真机；LSPosed scope 批准、RemoteFile/RemotePreferences 同步、Hook 命中与 logcat→journal 摄入、`app_list/app_info/process_list/get_logs/trigger_app` 的真机返回、`modspec connect` 对真实陈旧 forward 的修复仍需在设备上验收（见 IMPLEMENTATION.md）。

## PC 侧 ADB 分工（不重实现 adb）

- 发现/转发/安装/拉取/UI 树快照：`modspec-adb` crate（类型化封装，内部调用 `adb` 二进制，参数全部先校验）。
- 屏幕流式：**不做**。文档明确委托 `scrcpy -s <serial>`。
- 不做不受限的 tap/type 自动化（无显式命令 + 设备归属校验 + 风险边界前不提供）。
