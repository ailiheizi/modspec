# 如何继续开发 ModSpec

面向「接下来该干什么」的实操手册。配合 [ROADMAP.md](./ROADMAP.md) 看阶段优先级。

---

## 0. 克隆与依赖

```bash
git clone https://github.com/ailiheizi/modspec.git
cd modspec

# Rust
cargo test --workspace

# Android（JDK 17，不要用 JDK 25）
cd agent
# Windows 可选代理：
# $env:HTTP_PROXY="http://127.0.0.1:7890"
# $env:HTTPS_PROXY="http://127.0.0.1:7890"
# 本机 JDK 17：在 gradle.properties 取消注释 org.gradle.java.home
.\gradlew.bat :app:assembleDebug
# macOS/Linux（仓库文件无执行位时）：bash ./gradlew :app:assembleDebug
```

参考项目（可选，本地对照用）：

```powershell
cd references
git clone --depth 1 https://github.com/libxposed/example.git libxposed-example
git clone --depth 1 https://github.com/libxposed/service.git libxposed-service
git clone --depth 1 https://github.com/frknkrc44/HMA-OSS.git HMA-OSS
```

---

## 1. 当前最高优先级：跑通 smoke-hook

这是 **Phase 6** 的验收标准，也是公开发布的第一块敲门砖。

### 1.1 手机侧准备

1. 安装 `agent/app/build/outputs/apk/debug/app-debug.apk`
2. LSPosed Manager：**启用** modspec 模块
3. 作用域勾选：**系统框架** + **com.xiaomi.joyose**（smoke 需要）
4. 打开 **ModSpec Agent** App（触发 `AgentService` + XposedService 绑定）
5. Hook 管理卡片应逐步显示「已连接 · LSPosed API xxx」

### 1.2 PC 侧联调

```bash
# USB 连接
adb devices
adb forward tcp:9876 tcp:8764

# 语义化连接（短预检 → 分类 → 重建 forward → 可选拉起 Agent）——替代手写 adb forward
cargo run -p modspec-cli -- connect
cargo run -p modspec-cli -- connect --serial R58M2ABCD --no-bootstrap

# 配对（App 首页 6 位码）
cargo run -p modspec-cli -- pair scan --code <码> --host 127.0.0.1 --port 9876

# 查看状态（含 server_http_alive / server_ws_alive）
cargo run -p modspec-cli -- device status

# 应用冒烟 profile
cargo run -p modspec-cli -- profile apply profiles/test/smoke-hook.mspec.toml
```

### 1.3 PC-first 单规则 session（当前推荐）

```bash
cargo run -p modspec-cli -- rule run rules/test/smoke-joyose.rule.toml --wait 20 --expect loaded
# 可选：--serial <adb序列号> 指定 forward 修复设备；--no-bootstrap 关闭 Agent 拉起
```

该命令会先做连接预检（短超时；loopback 下自动重建 `adb forward`，Agent 不可达时默认拉起其 MainActivity，`--no-bootstrap` 可关），随后依次输出 `rule_uploaded` → `reload_started` → `target_restarted`/`target_stopped`/`target_not_installed`/`launch_failed` → `event_id=… event=hook_loaded|hook_hit|hook_error …`（每行带 Agent 分配的单调 `event_id`，即 `collect_logs` 游标）。轮询遇到瞬时传输失败会打印 `poll_retry attempt=N` 并自动重试（同游标，不重复不丢失）。如果目标没有 Launcher（如 joyose），会显示 `target_stopped action=trigger_manually`；另一个终端触发：

```bash
adb shell am startservice -n com.xiaomi.joyose/.securitycenter.GPUTunerService
```

需要确认真实调用命中时使用 `--expect hit`。Ctrl-C 只终止 PC 日志 session，不会留下 attach 进程；已下发规则会保留，便于继续迭代。

编排逻辑在 `modspec-protocol::session`，CLI 只是打印 + Ctrl-C 适配器；本地用 loopback fake Agent 测试（`cargo test -p modspec-protocol`）。

### 1.3b JS/Lua 脚本引擎（本次新增，真机待验收）

```bash
# 本地离线校验（manifest + 包结构 + 哈希）
cargo run -p modspec-cli -- script validate scripts/xiaomi/security-center/macro-gate

# 单命令闭环：连接恢复 → scope → 部署 → 重启 com.miui.securitycenter → 等待 script_loaded
cargo run -p modspec-cli -- script run scripts/xiaomi/security-center/macro-gate --wait 30
# 需要真实命中（macro_allowed 生效）时：
cargo run -p modspec-cli -- script run scripts/xiaomi/security-center/macro-gate --wait 30 --expect hit
# JSON/NDJSON（AI 工具友好）：
cargo run -p modspec-cli -- script run scripts/xiaomi/security-center/macro-gate --json

# 生命周期
cargo run -p modspec-cli -- script list
cargo run -p modspec-cli -- script enable xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- script disable xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- script remove xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- script reload xiaomi/security-center/macro-gate --restart
cargo run -p modspec-cli -- script follow --script xiaomi/security-center/macro-gate
```

脚本包结构：`scripts/<oem>/<pkg>/<name>/{manifest.toml, src/main.js|main.lua}`。新脚本开发流程：本地 `script validate` → `script run --expect hit` 迭代 → 保存为可复现包。脚本 API（`modspec` 全局）：`hook/hookConstructor/unhook`、`findClass/findClassOrNull/waitForClass`、`findMethod(OrNull)/findConstructor(OrNull)`、`getField/setField/getStaticField/setStaticField`、`callMethod/callStatic/newInstance`、`dexFindClass/dexFindMethod`、`emit/log`、`getTargets/getPackage/scriptInfo`。hook 回调上下文 `ctx`：`thisObject`/`args`/`arg(i)`/`setArg(i,v)`/`result`/`callOriginal()`/`proceed()`/`skip(v)`（Lua 中 `ctx:arg(1)` 从 1 起）。能力边界：无文件/网络/root/shell；`emit`/`log` 需在 manifest 声明。

### 1.5b profile 只读验收 / 社区 lint / 规则模板（本次新增，真机待验收）

```bash
# 只读验收：Agent verify drift + module_diagnostics（scope⊆、rule∈active_rules）+ [verify] 日志模式
cargo run -p modspec-cli -- profile verify profiles/xiaomi/hyper-perf-pack.mspec.toml
# 输出 verify drift/ok/failed 逐条 + verify_result ok=N failed=N skipped=N；有漂移非零退出

# 社区索引校验（重复 id / rule id 格式 / 包名 / 路径存在性；CI 也在跑）
cargo run -p modspec-cli -- community lint

# 规则起步模板（observe hook + verify 段，拒绝覆盖已有文件）
cargo run -p modspec-cli -- rule init --id xiaomi/example/foo --package com.example.foo
```

### 1.5 设备清单/进程/日志（本次新增，真机待验收）

```bash
# 只读清单（结构化 JSON；--apps 才返回有界包明细，超限带 truncated 信号）
cargo run -p modspec-cli -- device inspect --apps
cargo run -p modspec-cli -- app list --scope user --filter joyose
cargo run -p modspec-cli -- app info com.android.settings
cargo run -p modspec-cli -- process list --package com.xiaomi.joyose
cargo run -p modspec-cli -- device logs --package com.xiaomi.joyose --limit 100
cargo run -p modspec-cli -- device diagnostics

# 显式生命周期（有守卫；无 Launcher 时先报 needs_trigger，再给显式组件）
cargo run -p modspec-cli -- app restart com.example.target
cargo run -p modspec-cli -- app trigger com.xiaomi.joyose --component com.xiaomi.joyose/.securitycenter.GPUTunerService

# 类型化 ADB 传输（内部调用 adb；屏幕流式用 scrcpy -s <serial>）
cargo run -p modspec-cli -- adb devices
cargo run -p modspec-cli -- adb forward 9876 8764
cargo run -p modspec-cli -- adb install agent/app/build/outputs/apk/debug/app-debug.apk
```

真机验收清单见 [IMPLEMENTATION.md § 真机验收清单](./IMPLEMENTATION.md#真机验收清单唯一剩余设备侧工作)。

### 1.4 软重启 + 验证 hook

1. App 内点 **软重启模块**（或 CLI `soft_restart` RPC）
2. 触发 joyose（无 Launcher，可用）：
   ```bash
   adb shell am startservice -n com.xiaomi.joyose/.securitycenter.GPUTunerService
   ```
3. 看日志：
   ```bash
   adb logcat -s ModspecRuleEngine ModspecModule
   ```
4. 期望：`smoke-joyose` / `hooked` 相关行

### 1.4 失败时排查顺序

| 现象 | 先查 |
|------|------|
| RPC 超时 / `rule run`、`profile apply` 卡住 | 先跑 `modspec connect`（自动重建陈旧 forward / 拉起 Agent）；`device diagnostics` 看 `server_http_alive`/`server_ws_alive`（Agent 端 ServerSupervisor 已内置看门狗，空启动/进程存活时会自愈，无需强停进程） |
| `modspec connect` 报 `stale_forward` 且修复失败 | USB 是否断开；`adb devices` 是否 `device`；`--serial` 是否需显式指定（多设备时）；Agent App 是否被杀 |
| `modspec connect` 报 `unauthorized` | token 已轮换/过期；`modspec pair scan` 重新配对 |
| `rule run` 出现 `poll_retry attempt=N` | 正常：瞬时传输失败的有界重试（游标保留，不重复不丢失）；持续报错则查 Agent 是否被强停 |
| XposedService 未连接 | 模块是否启用；冷启动 App；看 Hook 面板状态条 |
| 规则未加载 | `RemoteRulesManager`；环境检查；`files/rules/*.rule.toml` |
| joyose 无 log | `rule run` 的 scope ack；是否出现 `target_stopped`/`target_not_installed`；进程是否被手动触发 |
| collect_logs 无事件 | Agent 事件环 `EventJournal` + `EventTailer`（需 root tail logcat）；`files/events/events.ndjson`；`--wait` 是否太短 |
| `device logs` 空 | `root_available=false` 时原始 logcat 不可用（Hook 事件仍走 collect_logs）；`resolved_pids` 是否为空（进程未运行） |
| `app trigger` 报 needs_trigger | 目标无 Launcher activity；按提示给 `--component <pkg>/<class>` |
| 提示「has no bearer token」 | 旧 devices.toml 记录无 token；`modspec pair scan` 重新配对 |
| 仍走 tmp 降级 | `ModspecApp.xposedService` 是否为 null |

---

## 2. 推荐开发顺序（单人 / AI 协作）

按依赖关系排序，**不要跳步**：

```
① smoke-hook E2E 通过（+ 新只读面真机验收：device inspect / app list / process list / device logs / diagnostics）
    ↓
② EnvironmentChecker 补 remote_files 检查项
    ↓
③ ModspecModule 写 reload ack + RuleEngine 日志规范化
    ↓
④ verify CLI 读 log 做自动验收
    ↓
⑤ GitHub Actions（cargo test + assembleDebug）
    ↓
⑥ 打 v0.1.0-alpha.1 tag，考虑改 public
    ↓
⑦ WebSocket RPC 真连接
    ↓
⑧ module_scope / module_prefs mod 类型补全
    ↓
⑨ MCP 工具补 soft_restart / verify
    ↓
⑩ 社区 rules 贡献流程
```

每一层完成后更新 `docs/IMPLEMENTATION.md` 和 `README.md` 状态表。

---

## 3. 关键文件速查

### Rust CLI

| 路径 | 职责 |
|------|------|
| `crates/modspec-core/src/profile.rs` | `.mspec.toml` schema |
| `crates/modspec-core/src/rule.rs` | `.rule.toml` schema |
| `crates/modspec-protocol/src/client.rs` | RPC 客户端 |
| `crates/modspec-protocol/src/connection.rs` | **PC 侧连接管理器**：短预检、`stale_forward`/`agent_unreachable`/`unauthorized` 分类、forward 重建、bootstrap、重试分类（只读才可重试） |
| `crates/modspec-protocol/src/session.rs` | `rule run` 编排（含轮询瞬时失败有界重试 + 游标续传） |
| `crates/modspec-protocol/src/script.rs` | **脚本 RPC 类型 + `run_script_session` 编排**（`request_id` 幂等、script_* 事件轮询） |
| `crates/modspec-core/src/script.rs` | **脚本包 schema + 校验 + 确定性 SHA-256** |
| `crates/modspec-core/src/community.rs` | **社区索引 schema + lint**（`community lint`：重复 id/rule id 格式/包名/路径存在性/oem） |
| `crates/modspec-protocol/src/verify.rs` | **verify/soft_restart 类型**（VerifyParams/DriftItem/SoftRestartResponse，全 Option 容错）；`client.rs` 的 `verify`/`reapply`/`soft_restart` 方法 |
| `crates/modspec-protocol/src/{inspect,apps,process,logs,diagnostics,trigger}.rs` | 只读清单/触发协议类型 + 校验（包名/组件） |
| `crates/modspec-cli/src/commands/{app,process,adb,device}.rs` | 新命令面（清单/生命周期/ADB 传输） |
| `crates/modspec-adb/src/lib.rs` | 类型化 ADB 封装（discovery/forward/install/pull/ui-tree） |
| `crates/modspec-mcp/src/tools.rs` | MCP 工具定义（19 个，含 `soft_restart`/`collect_logs`/`verify_profile`） |
| `crates/modspec-cli/src/commands/profile.rs` | apply / diff / **verify（只读漂移验收）** |
| `crates/modspec-cli/src/commands/community.rs` | `community index` / **`community lint`** |
| `crates/modspec-cli/src/commands/rule.rs` | validate / list / run / **init（模板生成）** |

### Android Agent

| 路径 | 职责 |
|------|------|
| `ModspecModule.kt` | Xposed 入口、加载 RuleEngine + ScriptEngine |
| `RuleEngine.kt` | 解析规则、下 hook（复用 HookRegistry） |
| `HookRegistry.kt` | **每方法单句柄多回调复用器 + 熔断 + 慢回调看门狗**（纯 JVM，可单测；修复句柄泄漏与同方法 hook 互斥） |
| `ScriptEngine.kt` / `ScriptHost.kt` / `ScriptBridge.kt` | **进程侧脚本运行时**（RemoteFile zip 加载、类解析/waitForClass、Frida 风格 API） |
| `ScriptRuntime.kt` | **RhinoRuntime（JS）+ LuaRuntime（LuaJ）适配器**（值转换、执行预算、回调桥接） |
| `ScriptManager.kt` | **Agent 侧脚本 RPC**（deploy/enable/disable/remove/reload + request_id 幂等 + 互斥激活） |
| `VerifyEvaluator.kt` | **verify RPC drift 评估**（纯 JVM 可单测：active_profile/items/rule_ref/scope） |
| `RuleParser.kt` | `.rule.toml` 解析 + variants 匹配（android/oem/rom 全条件生效，首个匹配优先） |
| `EnvironmentChecker.kt` | 环境诊断（7 项 + `checkRemoteRules` 规则通道检查） |
| `ScriptManifest.kt` / `ScriptStateStore.kt` | manifest/zip/哈希校验 + 生命周期状态持久化（JVM 安全） |
| `RemoteRulesManager.kt` | openRemoteFile 同步规则 |
| `ModuleReloader.kt` | hotReload / force-stop |
| `AppProfileApplier.kt` | profile JSON → 执行 mods |
| `DeviceInspector.kt` / `AppInspector.kt` / `ProcessInspector.kt` / `LogQuery.kt` | 只读设备/app/进程/logcat 清单（纯解析可 JVM 测试） |
| `rpc/ServerSupervisor.kt` | **Agent 服务器监督**：幂等生命周期 + 5s 看门狗；accept 循环死亡自动重建（不杀 App）；健康状态进 diagnostics |
| `MainActivity.kt` + `HookPanelSnapshot.kt` | Hook 管家 UI |
| `rpc/RpcHandler.kt` | JSON-RPC 方法表 |

### 配置与示例

| 路径 | 职责 |
|------|------|
| `profiles/test/smoke-hook.mspec.toml` | 冒烟 profile |
| `rules/test/smoke-joyose.rule.toml` | 冒烟规则 |
| `agent/app/src/main/assets/rules/` | 打进 APK 的内置规则 |

---

## 4. 常见开发任务食谱

### 新增一条 Hook 规则

1. 在 `rules/<oem>/<pkg>/foo.rule.toml` 编写（或 `modspec rule init --id <oem>/<pkg>/foo --package <pkg>` 生成模板后编辑）
2. `cargo run -p modspec-cli -- rule validate rules/.../foo.rule.toml`
3. 复制或同步到 `agent/app/src/main/assets/rules/`（或靠 apply 推送）
4. 在 profile 里加 `type = "rule_ref"`
5. apply → 软重启 → logcat（或 `modspec profile verify` 只读验收漂移）

### 新增一个 JS/Lua 脚本包

1. `scripts/<oem>/<pkg>/<name>/manifest.toml` + `src/main.js`（或 `main.lua`）
2. `cargo run -p modspec-cli -- script validate scripts/...`
3. `modspec script run scripts/... --wait 30 --expect hit` 迭代；结构化事件 `script_loaded` / `script_hit` / `script_error` / `macro_allowed`（自定义 emit）
4. 需要引擎新 API 时改 `ScriptBridge.kt` + 两个适配器（`ScriptRuntime.kt`）+ `ScriptEngineTest`，保持 JS/Lua 对等
5. 静态验收：复制包到 `agent/app/src/test/resources/scripts/` 镜像并扩展 `MacroGateAcceptanceTest`

### 新增 profile mod 类型

1. `modspec-core/src/profile.rs` 扩展 enum
2. `AppProfileApplier.kt` 加分支
3. `validate.rs` 加校验
4. 示例写进 `profiles/`
5. 补测试

### 新增 RPC / MCP 方法

1. `RpcHandler.kt` 注册 method（+ 新 RPC 的只读逻辑放独立 object，如 `DeviceInspector`/`AppInspector`）
2. `modspec-protocol` 加类型（`src/{apps,process,logs,diagnostics,trigger,script}.rs` 风格：带默认值与序列化单测）
3. `modspec-cli` 或 `modspec-mcp` 暴露命令/工具（新命令先做 PC 侧包名/组件校验）
4. fake-Agent 契约测试（`crates/modspec-protocol/tests/fake_agent.rs` / `tests/script_session.rs`）+ 纯解析 JVM 单测
5. `docs/protocol.md` + `IMPLEMENTATION.md` 更新

> 变更类 RPC（deploy/enable/disable/remove/reload）必须携带 `request_id` 幂等键并加进 Agent 侧 `ScriptManager` 的 LRU 重放；绝不盲目自动重试变更。

### 新增 adb 传输操作（modspec-adb）

1. 先在 `crates/modspec-adb` 加类型化方法 + 输入校验（serial/端口/路径/组件）
2. 校验逻辑加纯函数单测
3. CLI `commands/adb.rs` 暴露；文档标注风险与 scrcpy 分工
4. 不做不受限 tap/type；屏幕流式一律委托 scrcpy

### 改 Hook 管家 UI

1. `res/layout/activity_main.xml` 或 item 布局
2. `HookPanelSnapshot.kt` 数据结构
3. `MainActivity.renderHookManager()`
4. `res/values/strings.xml` 中文文案

---

## 5. 与 AI 协作建议

### MCP 配置

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

### 给 AI 的有效 prompt 模板

- 「跑通 smoke-hook：设备已连接，帮我 apply 并看 logcat」
- 「XposedService 显示未连接，查 `XposedServiceCoordinator` 和 LSPosed scope」
- 「给 `EnvironmentChecker` 加 remote_files 检查项」
- 「给 `rule run` 补 fake-Agent 编排测试」（Phase 7 事件环/event_id 游标已实现，见 `EventJournal.kt` / `modspec-protocol::session`）

### 子任务拆分原则

- 一次只做一个 Phase 子项
- 改 Agent 后必须 `assembleDebug` + `adb install`
- 改 schema 后必须 `cargo test`
- 参考实现先查 `references/INTEGRATION.md`，再改代码

---

## 6. 提交与发布节奏

```bash
# 日常
git checkout -b feat/smoke-verify
# ... 开发 ...
cargo test --workspace
cd agent && .\gradlew.bat :app:assembleDebug

git add -A && git commit -m "feat: ..."
git push -u origin feat/smoke-verify
gh pr create --title "..." --body "..."

# 里程碑 tag（私仓）
git tag v0.1.0-alpha.1
git push origin v0.1.0-alpha.1
```

公开发布前 checklist 见 [ROADMAP.md § 公开发布门槛](./ROADMAP.md#公开发布门槛私仓--public)。

---

## 7. 下一步你可以直接让我做的事

1. **真机联调**：手机连上后完整跑 smoke-hook 并修 blocker（CI 已建、verify/community/rule init 已落地，见上）
2. **CI 首跑**：仓库推到 GitHub 后观察 `.github/workflows/ci.yml` 两个 job 首次通过（rust + assembleDebug）
3. **verify 真机验收**：`modspec profile verify` 在真机跑通（Agent drift + scope/规则 + `[verify]` 日志模式）
4. **改 public**：smoke 通过后改仓库可见性 + Release

说一个方向即可，我按 [ROADMAP](./ROADMAP.md) 优先级继续推进。
