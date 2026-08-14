# ModSpec

LSPosed 生态的配置编排平台：**Rust CLI** + **LSPosed 模块 (modspec-agent)**。

- 声明式 **`.mspec.toml`** profile（模块 scope、规则引用、重应用、验证）
- 可复用 **`.rule.toml`** 通用 Hook 规则库
- **JS/Lua Hook 脚本引擎**（Rhino / LuaJ，Frida 风格 `before/after/replace`，结构化事件）
- 社区 profile / 规则 / 脚本索引
- PC ↔ 手机 JSON-RPC（当前 HTTP `:8764` 主路径，ADB loopback；WS `:8765` 可选）

## 快速开始（CLI）

```bash
cargo test --workspace

# 本地校验
cargo run -p modspec-cli -- validate profiles/xiaomi/hyper-perf-pack.mspec.toml
cargo run -p modspec-cli -- rule list
cargo run -p modspec-cli -- rule init --id xiaomi/example/foo --package com.example.foo
cargo run -p modspec-cli -- script validate scripts/xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- community lint
cargo run -p modspec-cli -- show profiles/xiaomi/hyper-perf-pack.mspec.toml

# 配对 + 真实 RPC（USB/ADB）
adb forward tcp:9876 tcp:8764
cargo run -p modspec-cli -- pair scan --code 123456 --host 127.0.0.1 --port 9876
cargo run -p modspec-cli -- device status

# 语义化连接管理（短预检 → 故障分类 → 重建 ADB forward → 可选拉起 Agent）
cargo run -p modspec-cli -- connect
cargo run -p modspec-cli -- connect --serial R58M2ABCD --no-bootstrap
cargo run -p modspec-cli -- profile apply profiles/xiaomi/hyper-perf-pack.mspec.toml --dry-run

# 只读验收：profile 状态漂移 + scope/规则清单 + [verify] 日志模式（非零退出表示有漂移）
cargo run -p modspec-cli -- profile verify profiles/xiaomi/hyper-perf-pack.mspec.toml

# PC-first 单规则调试闭环（结构化 hook 事件 + event_id 游标）
cargo run -p modspec-cli -- rule run rules/test/smoke-joyose.rule.toml --wait 20

# JS/Lua 脚本：本地校验 → 部署 → 重启目标 → 跟随结构化事件
cargo run -p modspec-cli -- script validate scripts/xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- script deploy scripts/xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- script run scripts/xiaomi/security-center/macro-gate --wait 30 --expect hit
cargo run -p modspec-cli -- script list
cargo run -p modspec-cli -- script enable xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- script disable xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- script remove xiaomi/security-center/macro-gate
cargo run -p modspec-cli -- script reload xiaomi/security-center/macro-gate --restart
cargo run -p modspec-cli -- script follow --script xiaomi/security-center/macro-gate

# 只读设备/应用/进程清单（结构化 JSON，AI/MCP 友好）
cargo run -p modspec-cli -- device inspect --apps
cargo run -p modspec-cli -- device logs --package com.xiaomi.joyose --limit 100
cargo run -p modspec-cli -- device diagnostics
cargo run -p modspec-cli -- app list --scope user --filter joyose
cargo run -p modspec-cli -- app info com.android.settings
cargo run -p modspec-cli -- process list --package com.example.target

# 显式生命周期操作（有守卫：system/android 永不被 force-stop）
cargo run -p modspec-cli -- app restart com.example.target
cargo run -p modspec-cli -- app trigger com.xiaomi.joyose --component com.xiaomi.joyose/.securitycenter.GPUTunerService

# 类型化 ADB 传输助手（内部调用 adb 二进制；屏幕流式请用 scrcpy）
cargo run -p modspec-cli -- adb devices
cargo run -p modspec-cli -- adb forward 9876 8764
cargo run -p modspec-cli -- adb install agent/app/build/outputs/apk/debug/app-debug.apk
cargo run -p modspec-cli -- adb ui-tree --serial R58M2ABCD

# MCP server（供 Cursor / Claude 调用）
cargo run -p modspec-cli -- mcp serve
```

## 脚本引擎（JS / Lua）

ModSpec 的脚本引擎是一个受控的专家 Hook 运行时（Frida 风格 API，`modspec` 全局对象）：

```js
// scripts/xiaomi/security-center/macro-gate/src/main.js（见仓库内完整实现）
modspec.hook({
  clazz: "com.miui.securitycenter.O3$b",
  method: "h",
  params: ["android.content.Context", "java.lang.String", "boolean"],
  before: function (ctx) { /* 修改参数 */ },
  replace: function (ctx) { ctx.result = true; /* 或 ctx.callOriginal() */ },
  after:  function (ctx) { ctx.result = ctx.result + "!"; },
  id: "macro-gate-h"
});
modspec.emit("macro_allowed", { game: modspec.getTargets()[0] });
```

- **引擎**：JavaScript = Rhino 1.7.15（MPL-2.0），Lua = LuaJ 3.0.1（MIT）——纯 Java，无 native ABI 约束
- **原生层（可选）**：声明 `capabilities = ["frida"]` + `[frida] script = "src/frida.js"` 时，PC 按需下发 Frida gadget（不内置 APK），hook 进程 dlopen 后由 `frida.js`（QuickJS）做 native hook / 内存读写；事件经 logcat→journal 管道回传（gadget LGPL-2.1，显式能力声明才加载）
- **隔离**：无文件/网络/root/shell 能力；能力必须声明（`emit` / `log`）且被校验
- **确定性组合**：同一方法多 hook 共享一个安装句柄，`before` 按注册序、`replace` 最后者生效、`callOriginal` 语义一致（修复了旧 RuleEngine 的句柄泄漏与“一个 O3.b hook 挡住后续 hook”）
- **防护**：PROTECTIVE 异常模式 + 连续失败熔断（circuit breaker）+ 回调超时看门狗
- **生命周期**：`script_uploaded/enabled/disabled/reload_started/loaded/unloaded/hit/message/error` 结构化事件，`script run` 单命令完成 部署→scope→重启→等待→流式事件→Ctrl-C
- **幂等**：变更 RPC 携带 `request_id`，重放返回已存响应，绝不盲目重试变更

### 现有 TOML 规则迁移（向后兼容）

脚本引擎**不替代**声明式规则：

- 所有 `.rule.toml` 规则、`rule run`/`rule list`、profile 的 `rule_ref` 完全不变，继续受支持。
- 何时用规则：简单观察/skip/return_const、单一目标、无需逻辑分支。
- 何时用脚本：条件逻辑、按参数/游戏 id 分流、多步骤决策、DexKit 深度查询、自定义结构化事件（`emit`）。
- 两者可以共存：规则与脚本的 hook 经同一个 `HookRegistry` 复用器组合（同一方法上 `before` 按注册序、`replace` 最后注册者生效），互不遮蔽。
- 修复项：历史版本中 `RuleEngine.shutdown()` 的 `activeHandles.clear()` 泄漏（未真正 unhook）以及“一个 `O3.b` hook 阻止后续同方法 hook”的问题，已由每方法单句柄复用修复；旧规则无需改动即可受益。

## 能力与风险表

| 命令 | 数据来源 | 默认安全等级 | 说明 |
|------|----------|--------------|------|
| `connect` | PC（短 `GET /health` 预检 + 授权 ping + adb） | ✅ 只读 + 显式修复 | 分类 `healthy`/`stale_forward`/`agent_unreachable`/`unauthorized`；loopback 下重建 `adb forward`；可选 bootstrap Agent MainActivity（`--no-bootstrap` 关闭）；3s 短超时不悬挂 |
| `device inspect` | Agent（Android API，只读） | ✅ 只读 | 硬件/软件/显示/内存/存储/运行时；`--apps` 才返回有界包清单（含截断信号），不暴露配对密钥或应用数据 |
| `device status` / `diagnostics` | Agent（只读） | ✅ 只读 | 状态 + LSPosed 框架/scope/规则/脚本/事件源诊断；不做任意 root 文件读取 |
| `device logs` | Agent（root logcat dump） | ✅ 只读 | 有界、按 package/tag/since 过滤的原始日志；与 `collect_logs` 的结构化 Hook 事件分离 |
| `app list` / `app info` | Agent（PackageManager + 一次只读 `pm list packages -i`） | ✅ 只读 | 包名/版本/system/启用/安装来源/组件统计；有界 + 过滤；非法包名拒绝 |
| `process list` | Agent（`ps -A`） | ✅ 只读 | package/pid/uid/运行状态 |
| `rule run` | Agent（deploy + scope + 重启 + 事件轮询） | ⚠️ 显式 | 单规则调试闭环；system/android 永不被 force-stop；先短预检+forward 修复（可选 bootstrap），轮询瞬时传输失败自动重试且游标不重复/不丢失；deploy/restart 绝不自动重试 |
| `script validate/deploy/run/list/enable/disable/remove/reload/follow` | Agent（脚本 RPC + 事件环） | ⚠️ 显式 | 脚本 = 受控专家引擎；`validate` 离线；变更带 `request_id` 幂等；`run` 单命令闭环；无文件/网络/root/shell 能力 |
| `app restart` / `app trigger` | Agent（`am force-stop` / `monkey` / `am start`） | ⚠️ 显式 | 需 bearer token；`trigger` 默认只启 Launcher，无 Launcher 时报 `needs_trigger`，显式 `--component` 必须属于目标包 |
| `profile apply` | Agent | ⚠️ 显式 | 需 bearer token；先短预检+forward 修复（不自动 bootstrap） |
| `profile verify` | Agent（verify drift + diagnostics + collect_logs） | ✅ 只读 | 只读验收：active_profile/items/rule_ref∈active_rules/scope 漂移 + `[verify] lsposed_log` 模式匹配；有漂移非零退出 |
| `community lint` | PC 本地（`community/index.toml`） | ✅ 只读 | 重复 id、rule id 格式、包名合法性、repo 相对路径存在性、oem 格式 |
| `rule init` | PC 本地 | ⚠️ 写本地文件 | 生成合法 `.rule.toml` 起步模板（observe hook + verify 段）；拒绝覆盖已存在文件 |
| `adb forward/install/pull/ui-tree` | PC 本地 adb 二进制 | ⚠️ 显式 | 类型化封装：serial/端口/APK/路径/组件均先校验；屏幕流式交给 `scrcpy -s <serial>`，不做镜像/任意 tap/type |

> 原则：**默认只读、结构化为先、每次变更显式命名 + 参数校验 + 可见的风险分类**。不提供通用 `modspec shell`，不暴露不受限的远程 shell；脚本引擎同样没有任何 shell/文件/网络能力。

## 仓库结构

```text
crates/
  modspec-core/       TOML schema（profile/rule/script bundle）、devices 存储、校验
  modspec-protocol/   JSON-RPC + HTTP/WS 传输 + 结构化设备/app/进程/日志/脚本类型
  modspec-cli/        命令行 + mcp serve
  modspec-mcp/        MCP stdio 工具服务
  modspec-adb/        类型化 ADB 传输封装（discovery/forward/install/pull/ui-tree）
agent/                LSPosed 模块 APK（Kotlin；script/ 下为脚本引擎实现）
scripts/              脚本包（manifest.toml + src/）— 例：xiaomi/security-center/macro-gate
profiles/             示例 profile
rules/                通用 Hook 规则库
community/            社区索引
docs/                 协议、路线图、联调手册
```

## 文档

| 文档 | 说明 |
|------|------|
| [docs/ROADMAP.md](docs/ROADMAP.md) | **未来规划**：阶段目标、公开发布门槛、版本命名 |
| [docs/CONTINUE.md](docs/CONTINUE.md) | **如何继续**：smoke-hook 联调、开发顺序、文件速查 |
| [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) | 组件完成度清单 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献与环境要求 |
| [docs/protocol.md](docs/protocol.md) | JSON-RPC 协议 |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | 第三方依赖与许可证 |

## Agent（LSPosed 模块）

见 [agent/README.md](agent/README.md)、[docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) 与 [docs/CONTINUE.md](docs/CONTINUE.md)。

- libxposed API **102**，`RuleEngine`（static + DexKit）+ `ScriptEngine`（Rhino/LuaJ）+ `AppProfileApplier`
- `LsposedCli` → `/data/adb/lspd/bin/cli`
- `AgentService` → HTTP `:8764` + WebSocket `:8765`
- 构建需 **JDK 17**：`cd agent && .\gradlew.bat :app:assembleDebug`

## 参考项目

[docs/REFERENCES.md](docs/REFERENCES.md) — HMA-OSS、HyperCeiler、LSPosed_mod CLI、libxposed、LSPilot（脚本 API 参考）、LuaHook/JsHook/Frida（概念参考）等。

## 状态（v0.1.0-alpha，实验性）

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | TOML schema + 示例 + 本地 CLI | ✅ |
| 2 | Agent 骨架 + RPC + pair/apply CLI | ✅ |
| 3 | HTTP :8764 + RuleEngine Hook + DexKit | ✅ 本地编译通过 |
| 4 | ProfileApplier + remote file/prefs 规则通道 | ✅ 架构就绪 |
| 5 | MCP server (`modspec mcp serve`) | ✅ |
| 6 | 端到端 Hook 冒烟（真机 joyose 等） | 🚧 联调中 |
| 7 | Agent 事件环 + `collect_logs` event_id 游标 + `rule run` 编排测试 | ✅ 本地（fake-Agent + JVM 单测） |
| 8 | 只读设备/应用/进程清单 + 日志/模块诊断（`device inspect/logs/diagnostics`、`app list/info`、`process list`） | ✅ 本地（fake-Agent + JVM 单测） |
| 9 | 显式生命周期（`app restart/trigger`）+ 类型化 ADB 层（`modspec adb …`）+ MCP 只读工具 | ✅ 本地；真机验证待做 |
| 10 | 连接可靠性（Agent `ServerSupervisor` 看门狗 + `modspec connect` 语义连接管理 + 只读轮询重试/游标续传） | ✅ 本地（fake 测试 + JVM 单测）；真机验证待做 |
| 11 | **JS/Lua 脚本引擎**：脚本包格式/校验（PC+Agent）、HookRegistry 复用（修复句柄泄漏与同方法 hook 互斥）、before/after/replace + callOriginal + 值转换、Rhino/LuaJ 双引擎、DexKit 查询、`modspec script …` 全命令、脚本生命周期 RPC + `request_id` 幂等、熔断/超时、macro-gate 验收脚本包 | ✅ 本地（fake-Agent 脚本会话测试 + JVM 引擎/多 hook/验收测试）；真机验证待做 |
| 12 | 社区生态 + 验收工具链：`community lint`（索引校验）、`rule init`（模板生成）、`profile verify`（只读漂移验收 + 日志模式）、`verify`/`reapply`/`soft_restart` 类型化 RPC + 契约测试、MCP `soft_restart`/`collect_logs`/`verify_profile`、GitHub Actions CI（fmt/clippy/test/lint + assembleDebug）、Agent `verify` drift 评估 + variants oem/rom 匹配修复 + `checkRemoteRules` 环境检查 | ✅ 本地（Rust + JVM 全绿，84 Agent 用例）；真机验收待做 |

> 当前适合开发者预览与自测，**尚未**作为稳定发行版推荐普通用户安装。
> 本机已通过：`cargo test --workspace`（含 loopback fake-Agent 的 `rule run` 编排、脚本会话端到端/幂等/重连、认证/401、只读清单 RPC 契约、verify/reapply/soft_restart 契约、连接管理器 stale_forward/unauthorized 分类与修复、轮询重试无重复/无丢失、变更调用不自动重试、community lint、rule 模板）、Agent `:app:testDebugUnitTest`（84 用例：事件环 + ps/logcat 解析 + ServerSupervisor 看门狗 + HookRegistry 组合语义/熔断/卸载 + Rhino/LuaJ 双引擎 + macro-gate 验收 + RuleParser variants oem/rom + VerifyEvaluator drift）、`:app:assembleDebug`、`cargo clippy -D warnings` 全绿。唯一剩余验收是按 `adb forward → pair → script run/rule run → scope 批准/目标触发` 在 LSPosed 真机观察 generation 匹配的 `script_loaded` / `macro_allowed` / `hook_loaded` / `hook_hit`，新 RPC 的真机返回（`profile verify`/`soft_restart`/`verify` drift），以及真实陈旧 forward 下 `modspec connect` 的自动修复与 Agent 侧服务器自杀式重启自愈。
> 下一步做什么 → [docs/CONTINUE.md](docs/CONTINUE.md) · 长期规划 → [docs/ROADMAP.md](docs/ROADMAP.md)

## 许可证

AGPL-3.0-or-later（本仓库原为 MIT，脚本引擎版本起重新许可；详见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）。
