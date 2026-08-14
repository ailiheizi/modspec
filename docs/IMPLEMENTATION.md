# 实现清单（agent + CLI 联调）

> 组件级完成度快照。阶段优先级见 [ROADMAP.md](./ROADMAP.md)，实操步骤见 [CONTINUE.md](./CONTINUE.md)。

## 当前进度

| 组件 | 状态 | 备注 |
|------|------|------|
| modspec-core (TOML schema) | ✅ | profile + rule + validate + **script bundle**（manifest 校验/哈希/from_dir） |
| modspec-protocol (RPC 传输) | 🚧 | HTTP/WS request-response + pairing bearer token；TLS 待完善。连接可靠性已落地：`connection` 模块（短预检/分类/forward 修复/bootstrap/重试分类）+ 轮询游标续传 |
| modspec-protocol 结构化清单类型 | ✅ | `inspect`/`apps`/`process`/`logs`/`diagnostics`/`trigger` + 序列化测试 + 包名/组件校验 |
| modspec-protocol 脚本类型 + session | ✅ | `script.rs`：7 个脚本 RPC 类型 + `run_script_session`（deploy→scope→restart→script_* 事件轮询，`request_id` 幂等）+ fake-Agent E2E（含幂等/重连/script_error/超时） |
| modspec-cli (validate/pair/device/profile) | ✅ | offline + 真机 RPC |
| modspec-cli `script …` 命令族 | ✅ 本地（fake-Agent E2E + 本地 validate） | `validate`（离线）/`deploy`/`run`（单命令闭环，Ctrl-C 干净退出，JSON/NDJSON 模式）/`list`/`enable`/`disable`/`remove`/`reload`/`follow`（游标续传） |
| modspec-cli `app` / `process` / `device logs` / `device diagnostics` | ✅ 本地编译 | 只读清单 + 显式 `app restart/trigger`（有守卫） |
| modspec-cli `connect` + 连接管理器 | ✅ 本地（fake 测试） | `modspec-protocol::connection`：短 `/health` 预检 + 授权 ping；`stale_forward`/`agent_unreachable`/`unauthorized` 分类；loopback 重建 `adb forward`；可选 bootstrap MainActivity（`adb am start -n com.modspec.agent/.MainActivity`）；`rule run`/`profile apply`/`profile verify` 已接入预检+修复（apply/verify 不自动 bootstrap）。原始 adb 仅是实现细节（`modspec-adb::start_activity/bootstrap_agent`） |
| modspec-adb (类型化 ADB 封装) | ✅ 本地 | discovery/forward/install/pull/ui-tree/start_activity/bootstrap_agent；serial/端口/APK/路径/组件校验 + 单测；屏幕流式委托 scrcpy |
| modspec-mcp serve (stdio MCP) | ✅ | 19 个工具：基础工具集 + 只读 `device_inspect`/`app_list`/`app_info`/`process_list`/`collect_logs`/`verify_profile` + 变更 `soft_restart` |
| `modspec community lint` | ✅ | `modspec-core::community`：索引 schema + lint（重复 id、rule id 格式、包名、repo 相对路径存在性、oem 大小写）；CLI `community lint [--root]`；`community/index.toml` 内置索引 lint 全绿 |
| `modspec rule init` | ✅ | `RuleFile::template(id, package)` 生成合法起步规则（observe hook + verify 段），CLI `rule init --id … --package … [--output …]`，拒绝覆盖已存在文件 |
| `modspec profile verify` CLI | ✅ 本地 | `ProfileAction::Verify`：RPC `verify`（Agent 真实 drift）+ `module_diagnostics` scope⊆/rule∈active_rules 检查 + `[verify] lsposed_log` 模式经 `collect_logs` 匹配；`verify_result ok=N failed=N skipped=N`，有失败非零退出 |
| protocol `verify`/`reapply`/`soft_restart` 类型化方法 | ✅ 本地（fake-Agent 契约测试） | `verify.rs`（VerifyParams/VerifyResponse/DriftItem 全 Option 容错）+ `ReapplyParams` + `SoftRestartParams/Response`；`verify` 进只读可重试集，`reapply`/`soft_restart` 绝不自动重试（`tests/verify_contract.rs`） |
| modspec-agent RuleEngine (static + dexkit) | ✅ | 编译通过；已改为复用 `HookRegistry`（每方法单句柄多回调，修复句柄泄漏 + 同方法 hook 互斥） |
| modspec-agent variants 分支选择 | ✅ | |
| modspec-agent 脚本引擎 | ✅ 本地（JVM 单测） | `HookRegistry`（组合/熔断/卸载）+ `ScriptHost`（类解析/waitForClass/限速事件）+ `ScriptBridge`（Frida 风格 API）+ `RhinoRuntime`/`LuaRuntime`（真实引擎）+ `ScriptEngine`（进程侧 RemoteFile zip 加载）+ `ScriptManager`（RPC 生命周期 + request_id 幂等 + state 持久化） |
| modspec-agent verify RPC | ✅ 本地（JVM 单测） | `VerifyEvaluator.kt`（纯 JVM drift 评估：active_profile 匹配、item 状态、rule_ref∈active_rules、scope CLI 可用性）+ `AppProfileVerifyTest`（11 用例）；`RpcHandler.verify` 接线返回 `{ drift: [...] }` |
| modspec-agent RuleParser variants | ✅ 本地（JVM 单测） | `resolveHooks` 修复：`when` 的 oem/rom 条件参与匹配（不区分大小写），非空条件必须对应非空设备值；android 精确匹配；首个匹配变体生效，无匹配回落默认 hooks（`RuleParserTest` 10 用例） |
| modspec-agent EnvironmentChecker | ✅ 本地编译 | 新增 `checkRemoteRules`：active_rules 空 → OK；缺失规则文件 → FAIL + hint（re-apply / rule run）；全部存在 → OK + 计数 |
| modspec-agent AppProfileApplier | ✅ | rule_ref / reload 等 |
| modspec-agent DeviceInspector / AppInspector / ProcessInspector / LogQuery | ✅ 本地编译 | 只读清单 + `ps`/logcat 纯解析（JVM 单测）；不暴露个人数据 |
| RemoteRulesManager (openRemoteFile) | ✅ | legacy tmp 降级保留 |
| ModuleReloader (hotReload) | ✅ | API 102+ |
| Hook 管家 UI | ✅ | 规则/进程/日志/单按钮 |
| XposedServiceCoordinator | ✅ | 绑定状态机 |
| EventJournal + EventTailer + collect_logs | ✅ | Agent 自有有界事件环 + event_id 游标 + 持久化 journal；Hook 进程事件经 logcat 摄入；不再依赖 logcat 毫秒游标 |
| PC 单规则调试 session | ✅ 本地（fake-Agent E2E） | `rule run` 编排重构为 `modspec-protocol::session`；fake Agent 覆盖 loaded/hit/hook_error/重启失败/needs_trigger/精确代次隔离/超时/请求参数 |
| restart_targets 诊断 | ✅ 本地编译 | not_installed / needs_trigger / launch_failed / failed 区分；force-stop 前先验安装 |
| trigger_app 显式启动 | ✅ 本地编译 | Launcher 优先；无 Launcher → needs_trigger；显式 component 必须属于目标包；system/android/system_server 拒绝 |
| 配对 bearer token | ✅ | devices.toml 持久化；HTTP/WS Bearer 头；401→Unauthorized；旧记录需重新配对 |
| Agent HTTP :8764 | ✅ | NanoHTTPD（127.0.0.1）；`serve()` 兜底防 handler 异常杀死 accept 线程 |
| Agent WS :8765 | ✅ | text JSON-RPC；CLI 暂以 HTTP 为主；`onError`/发送失败不再吞掉 |
| Agent ServerSupervisor（服务器监督） | ✅ 本地（JVM 单测） | 幂等生命周期 + 5s 看门狗 + 每次 `onStartCommand` `poke()`；死亡 accept 循环自动重建（不杀 App）；重启计数/最近错误进 `get_status`/`module_diagnostics`（`server_http_alive`/`server_ws_alive`/`server_http_restarts`/`server_ws_restarts`/`server_last_error`） |
| PC 轮询重试 + 游标续传 | ✅ 本地（fake-Agent E2E） | `collect_logs` 传输层瞬时失败有界重试（同 `after_event_id`，无重复/丢失，PC 侧按 event_id 去重）；`poll_retry attempt=N` 事件行；变更调用绝不自动重试（deploy 恰好一次） |
| 脚本生命周期 RPC + 幂等 | ✅ 本地（fake-Agent E2E + JVM 单测） | `script_validate/deploy/list/enable/disable/remove/reload`；`request_id` LRU 重放；`active_script` 一等公民互斥激活；state.json 持久化 hash/generation/last load/hit/error |
| 脚本引擎测试 | ✅ 本地（JVM 单测） | manifest/zip/hash 校验、`HookRegistry` 组合语义（before 序、replace 最后者、callOriginal、after 覆盖、同方法多 hook、卸载/关闭无泄漏、熔断）、Rhino/LuaJ 真实引擎（值转换、执行预算、编译错误）、macro-gate 验收包（JS 规范实现 + Lua 示例，fake `O3$b` 只放行目标游戏） |
| E2E smoke-hook 真机验收 | 🚧 | **唯一设备侧 blocker** |
| GitHub Actions CI | ✅ | `.github/workflows/ci.yml`：rust（fmt + clippy -D warnings + test + community lint）+ agent（JDK 17 + setup-android + assembleDebug）；`.github/dependabot.yml` |
| profile verify CLI | ✅ 本地 | 见上表 `modspec profile verify`（Agent 侧 verify 已真实实现，真机验收待做） |

## 本地测试（无真机）

- `cargo test --workspace`：Rust 全绿。`modspec-protocol/tests/` 的 loopback fake Agent 走真实 HTTP transport，断言 `deploy_rule → restart_targets → collect_logs` 参数、`event_id` 游标推进、Bearer 认证头、401→`Unauthorized`，`rule run` 编排（`modspec-protocol::session`）的成功 loaded/hit、`hook_error`、重启失败不被误标为 hook_error、`needs_trigger`/`not_installed`/`launch_failed`、精确代次隔离、超时 deadline、无重启/无作用域前置校验、轮询瞬时失败重试与重连去重（`tests/session_reconnect.rs`），只读清单 RPC 族（`inspect_device`/`app_list`/`app_info`/`process_list`/`get_logs`/`module_diagnostics`/`trigger_app`）的请求参数与响应形状，**verify/reapply/soft_restart 契约**（`tests/verify_contract.rs`：profile_id 参数、drift 容错反序列化、rules_only/only_failed 标志、Bearer 认证），以及**脚本会话**（`tests/script_session.rs`：deploy→enable→restart→loaded→hit→logs→disable 全流程、`request_id` 幂等与稳定复用、重连/游标续传无重复、`script_error` 中止、超时、变更不自动重试、`script_list`/`script_reload`/`script_validate` 契约）。连接管理器（`tests/connection.rs`）用 `FakeHealthServer`（可模拟"accept 但永不响应"的陈旧 forward）覆盖预检分类、forward 重建、bootstrap 恢复、重试耗尽、`unauthorized` 检测。`modspec-core` 的 script bundle 单测覆盖 manifest 校验/路径穿越/超限/能力白名单/确定性哈希/from_dir，community 单测覆盖重复 id/oem 大小写/rule id 格式/路径存在性（含内置 `community/index.toml` lint 全绿），rule 模板单测覆盖 round-trip + validate。`modspec-adb` 单测覆盖 `adb devices` 解析、serial/APK/路径/端口/组件（`start_activity`）校验。
- Agent JVM 单测（`:app:testDebugUnitTest`）：`EventJournal` 事件环的单调 id、有界性、批量游标无重复/丢失、过滤、`truncated` 缺口、logcat 去重、journal 持久化/重播种（含 `script_id` 字段）；`ProcessInspector` 的 `ps` 行解析与 uid 映射；`LogQuery` 的 threadtime 解析与 package/tag/since 过滤；`AppInspector` 安装来源行解析；`ServerSupervisor` 的幂等 start、看门狗/`poke` 重建死亡服务器、启动失败记录与重试、snapshot 统计、stop 幂等与看门狗停止；脚本引擎族（`HookRegistryTest`、`ScriptManifestTest`、`ScriptEngineTest`、`MacroGateAcceptanceTest`）：组合语义/同方法多 hook/卸载无泄漏/熔断、manifest 与 zip/hash 校验、Rhino/LuaJ 真实引擎（before/after/replace + callOriginal + 参数/结果改写 + 执行预算 + 反射助手）、验收脚本包对 fake `O3$b`（含 `g` 锚点、`h` 方法）只对 `com.ChillyRoom.DungeonShooter` 返回 `true` 且发射 `macro_allowed`，缺类时发射确定性 `hook_error`；`RuleParserTest`（variants 的 android/oem/rom 匹配、大小写不敏感、非空条件需非空设备值、无匹配回落默认、首个匹配优先）；`AppProfileVerifyTest`（active_profile 匹配、item failed/manual/drifted 上报、rule_ref∈active_rules、scope CLI 不可用漂移、无漂移返回空）。
- `:app:assembleDebug`（JDK 17 + ANDROID_HOME）编译通过；本机已跑 `:app:testDebugUnitTest` 84 用例全绿。

## 真机验收清单（唯一剩余设备侧工作）

### 真机已验证（2026-08-08，Redmi 25102RKBEC / Android 16 / MIUI 12.7.4 / KernelSU）

- ✅ 配对、`connect`（healthy + stale_forward 重建 + bootstrap）、`device status/inspect/diagnostics`（环境检查 8 项全绿，含新增 `remote_rules`）
- ✅ `rule run` smoke-hook：`rule_uploaded`（remote_file + scope=already）→ joyose 跨进程加载 → **`hook_loaded` generation 精确匹配** → `collect_logs` 游标续传（event_id 852，curl 直测 `exact_generation` 隔离）
- ✅ `script run` macro-gate：部署 → 三进程 `script_loaded`（engine=js）→ 引擎脚本执行
- ✅ `app trigger`（launcher + `--component`）、`process list`、`app info`
- ✅ **游戏 hook 实证（DungeonShooter）**：`rule run rules/test/dungeon-shooter-observe.rule.toml --expect hit` → `hook_loaded` + **`hook_hit`**（`BasePlayerApplication.onCreate`）→ journal 摄入（event_id 1090/1091/1108/1109）+ `collect_logs` 精确拉取
- ✅ **宏 gate 修复验收（Security Center 12.7.4）**：DexKit 按证据字符串解析 MacroUtil（`R3.b`）→ hook `g`+`h(Context,String,boolean)` → `macro_allowed` 事件（`method=macro_util:g+h`）；非目标游戏保持原行为

### 真机发现并已修复的 bug（本轮）

| Bug | 根因 | 修复 |
|-----|------|------|
| `device inspect` 抛 "Tried to obtain display from a Context not associated with one" | Android 16 上 `applicationContext.display` 非法 | 改用 `DisplayManager.getDisplay(DEFAULT_DISPLAY)`（DeviceInspector.kt） |
| `app trigger <pkg>`（无 Launcher）报 `-32602: component null ...` | Rust 侧 `Option<String>` 序列化为 `"component": null`，agent `optString` 读成字面 `"null"` | `skip_serializing_if` + agent 防御（trigger.rs + RpcHandler.kt） |
| `app trigger` 报 "Cannot run program ksu: error=13"（monkey 无 activity 时） | `runSu` 把命令级失败（exit≠0）误判为 su 通道不可用 | 新增 `SuCommandFailed` 区分命令失败与 su 失败（ShellRunner.kt），needs_trigger 语义恢复 |
| 脚本部署 `zip.tmp: open failed: ENOENT` | `safeScriptId` 保留 `/`，本地/RemoteFile 名展开成多级路径，中间目录不存在 | 与规则一致 `/`→`%2F` 扁平化（ScriptManager.kt）+ 写前 `mkdirs` |
| 脚本部署后 hook 进程 "Invalid path: scripts/...zip" + script_error | 发布名（`file.name` 无前缀）与读取名（`remoteFileName` 带 `scripts/` 前缀）不一致；且 libxposed RemoteFile 拒绝含 `/` 的名字 | 统一为无前缀扁平名 `remoteFileName`（ScriptManager.kt + ScriptEngine.kt） |
| DexKit 查询 `UnsatisfiedLinkError: nativeInitDexKit` | hook 进程的 native 路径无 agent APK 的 libdexkit.so；`System.loadLibrary` 只查宿主 classloader；`/data/user/0` 无 dlopen 权限（SELinux 拒绝 execute）；`/data/local/tmp` 目录 system_app 无 search 权限 | `DexKitLib`（agent root）：从模块 APK 提取 so 部署到 **模块 APK 安装目录**（`/data/app/.../<pkg>==/modspec_lib/`，`apk_data_file` 标签，任何进程可 dlopen）；hook 进程 `System.load` 该路径；部署走后台线程（su 探测可能阻塞数十秒，不能占 RpcHandler init） |

### 待真机验收（设备重连后继续）

1. 安装 `agent/app/build/outputs/apk/debug/app-debug.apk`，在 LSPosed 启用 ModSpec。
2. `adb forward tcp:9876 tcp:8764`，再用手机显示的配对码执行 `pair scan`；首次配对会保存 bearer token。
3. 执行 `modspec connect`（短预检；若 `stale_forward` 自动重建 forward，仍不可达则拉起 Agent App）。`device diagnostics` 应显示 `server_http_alive`/`server_ws_alive` 为真。
4. 执行 `modspec rule run rules/test/smoke-joyose.rule.toml --wait 20 --expect loaded`，如手机弹出 scope 请求则明确批准。
5. 若 joyose 无 Launcher，手动触发 `adb shell am startservice -n com.xiaomi.joyose/.securitycenter.GPUTunerService`。
6. 确认 PC 收到与本次 generation 精确匹配的 `hook_loaded`（Agent 事件环 + journal 摄入，event_id 游标推进）；需要实际命中时改用 `--expect hit`。
7. 脚本引擎：`modspec script run scripts/xiaomi/security-center/macro-gate --wait 30 --expect hit`（首次 scope 批准 `com.miui.securitycenter`）→ 启动 `com.ChillyRoom.DungeonShooter`（或其宏入口）→ PC 应收到 `script_loaded`（hook installed: MacroUtil.g/h）+ `macro_allowed` + `script_hit`；换一个非目标游戏确认不被放行；`modspec script list` 应显示 hash/hit 计数；`script disable/enable` 切换一等公民激活态；`script follow` 以游标续传流式跟随。
8. 验证 restart 诊断：`not_installed`（未安装包）、`needs_trigger`（无 Launcher）、`launch_failed` 分类是否与设备实际一致。
9. 验证新只读面：`device inspect --apps`（硬件/软件/运行时/app 摘要与截断）、`app list --scope user --filter …`、`app info <pkg>`（installer/Launcher/组件统计）、`process list --package <pkg>`（pid/uid）、`device logs --package <pkg>`（root logcat 过滤 + resolved_pids）、`device diagnostics`（scope/active_rules/generation/事件源/服务器健康）。
10. 验证 `app trigger <pkg>`（Launcher）与 `app trigger <pkg> --component <pkg>/.Class`（无 Launcher 场景，如 joyose）。
11. 可靠性专项：模拟陈旧 forward（拔线重插或强停 Agent 进程后再 `adb forward`）后跑 `modspec connect` 应自动修复；强停 Agent 后重新打开 App，`device status` 应显示服务器已自愈（`server_*_alive`），RPC 不再悬挂。
12. **宏 gate 修复验收（12.7.4）**：✅ 已真机完成——DexKit 解析 MacroUtil + hook g/h + `macro_allowed`（见上表）。`script_hit`（游戏侧打开宏入口时）仍可在真机补验。
13. `profile verify` 真机验收：`modspec profile verify profiles/xiaomi/hyper-perf-pack.mspec.toml` 应输出 drift/ok 检查与 `verify_result`。

## MCP 配置（Cursor / Claude Desktop）

```json
{
  "mcpServers": {
    "modspec": {
      "command": "cargo",
      "args": ["run", "-p", "modspec-cli", "--", "mcp", "serve"],
      "cwd": "C:/path/to/modspec"
    }
  }
}
```

可用工具：`validate`、`show`、`list_rules`、`list_devices`、`device_status`、`device_inspect`、`app_list`、`app_info`、`process_list`、`apply_profile`、`script_validate`、`script_list`、`script_deploy`、`script_enable`、`script_disable`、`script_remove`、`soft_restart`（变更）、`collect_logs`（只读）、`verify_profile`（只读）

> 只读工具（`device_status`/`device_inspect`/`app_list`/`app_info`/`process_list`/`collect_logs`/`verify_profile`/`script_validate`/`script_list`）随时可用；`apply_profile`/`soft_restart`/`script_deploy`/`script_enable`/`script_disable`/`script_remove` 是变更操作，需已配对设备（bearer token）。

## Agent 目录

```text
agent/app/src/main/kotlin/com/modspec/agent/
  ModspecModule.kt           libxposed 102 入口（rules + scripts 双引擎）
  RuleEngine.kt              .rule.toml → hook（复用 HookRegistry）
  HookRegistry.kt            每方法单句柄多回调复用器 + 熔断 + 限速（纯 JVM）
  ScriptEngine.kt            进程侧脚本加载/运行（RemoteFile zip + 预算线程）
  ScriptHost.kt              per-process 脚本宿主（类解析/waitForClass/事件/字段）
  ScriptBridge.kt            引擎无关脚本 API（hook/findClass/fields/…）
  ScriptRuntime.kt           RhinoRuntime（JS）+ LuaRuntime（LuaJ）适配器
  ScriptAndroid.kt           libxposed 安装器 + logcat 事件 + DexKit 查询
  ScriptManager.kt           Agent 侧脚本 RPC（deploy/enable/disable/remove/reload + 幂等）
  ScriptManifest.kt          manifest 解析/校验 + 确定性 zip/SHA-256
  ScriptStateStore.kt        脚本生命周期状态持久化（JVM 安全 JSON 编解码）
  RemoteRulesManager.kt      openRemoteFile 规则同步
  ModuleReloader.kt          hotReload / force-stop
  XposedServiceCoordinator.kt  绑定状态
  HookPanelSnapshot.kt       Hook 面板数据
  AppProfileApplier.kt       profile → mods
  EnvironmentChecker.kt      环境诊断
  DeviceInspector.kt         只读设备清单（inspect_device）
  AppInspector.kt            只读 app 清单/详情（app_list / app_info）
  ProcessInspector.kt        ps 进程清单（process_list，纯解析可 JVM 测试）
  LogQuery.kt                有界 logcat 诊断（get_logs，纯解析可 JVM 测试）
  VerifyEvaluator.kt         verify RPC drift 评估（纯 JVM，可单测）
  rpc/RpcHandler.kt          JSON-RPC
  rpc/ServerSupervisor.kt    幂等服务器生命周期 + 看门狗（LocalHttpServer/LocalWsServer 实现 ManagedServer）
```

## CLI 命令

```bash
modspec mcp serve
adb forward tcp:9876 tcp:8764
modspec pair scan --code 123456 --host 127.0.0.1 --port 9876
modspec connect [--serial R58M2ABCD] [--no-bootstrap]
modspec device status
modspec rule run rules/test/smoke-joyose.rule.toml --wait 20 --expect loaded
modspec script validate scripts/xiaomi/security-center/macro-gate
modspec script deploy scripts/xiaomi/security-center/macro-gate
modspec script run scripts/xiaomi/security-center/macro-gate --wait 30 --expect hit
modspec script list / enable / disable / remove / reload --restart / follow --script <id>
modspec device inspect --apps
modspec device logs --package com.xiaomi.joyose --limit 100
modspec device diagnostics
modspec app list --scope user
modspec app info com.android.settings
modspec app restart com.example.target
modspec app trigger com.xiaomi.joyose --component com.xiaomi.joyose/.securitycenter.GPUTunerService
modspec process list --package com.example.target
modspec adb devices / forward 9876 8764 / install <apk> / pull <remote> <local> / ui-tree
```

## 联调顺序

详见 [CONTINUE.md § smoke-hook](./CONTINUE.md#1-当前最高优先级跑通-smoke-hook)。

1. 安装 APK → LSPosed 启用 + scope
2. 打开 App → 配对码 → AgentService
3. `adb forward` → `pair scan` → `rule run`
4. 必要时手动触发目标 → PC 结构化事件验证

## 构建 agent

- JDK **17**（见 [agent/README.md](../agent/README.md)）
- `cd agent && .\gradlew.bat :app:assembleDebug`

## 文档索引

| 文档 | 内容 |
|------|------|
| [ROADMAP.md](./ROADMAP.md) | 未来阶段、发布门槛、版本规划 |
| [CONTINUE.md](./CONTINUE.md) | 如何继续、联调食谱、文件地图 |
| [REFERENCES.md](./REFERENCES.md) | 外部参考项目 |
| [protocol.md](./protocol.md) | JSON-RPC 协议 |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 贡献流程 |
| [../references/INTEGRATION.md](../references/INTEGRATION.md) | 参考代码整合说明 |
