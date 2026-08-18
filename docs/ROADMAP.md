# ModSpec 路线图

> 最后更新：2026-08-08 · 当前版本：**v0.1.0-alpha**（私仓）

## 愿景

让 LSPosed 调机从「散落脚本 + 手动 scope + 重启应用」变成：

1. **声明式 profile**（`.mspec.toml`）描述要开哪些 mod、引用哪些规则
2. **可复用规则库**（`.rule.toml`）按包/OEM 共享
3. **JS/Lua 专家脚本引擎**（`.script.toml` 包 + Frida 风格 hook API）处理声明式表达不了的逻辑
4. **PC ↔ 手机编排**（CLI / MCP）一键 apply、diff、verify、script run
5. **Agent 侧可视化**（Hook 管家）看规则、进程、日志、软重启

长期定位：**LSPosed 生态的配置编排层**，不是又一个单点 Hook 模块。PC-first：PC 编辑/生成 → Agent 部署 → scope → 重启/热载 → 结构化事件流 → 迭代 → 可复现脚本/档案。

---

## 公开发布门槛（私仓 → public）

满足以下全部条件后再 `gh repo edit --visibility public` 并打 `v0.1.0` tag：

| # | 条件 | 验收方式 |
|---|------|----------|
| 1 | smoke-hook 真机通过 | `rule run ... --expect loaded/hit` 返回 generation 匹配的结构化成功事件 |
| 2 | XposedService 绑定可复现 | 冷启动 App → Hook 面板显示「已连接」 |
| 3 | 规则跨进程生效 | 不靠 `su cp /data/local/tmp`，`openRemoteFile` 主路径稳定 |
| 4 | README 最小联调路径 | 新环境 30 分钟内能跑通 pair → rule run → structured logs |
| 5 | CI 绿 | `cargo test` + `assembleDebug` 在 GitHub Actions 通过 |
| 6 | 无机器专属路径 | `gradle.properties` / `local.properties` 不入库 |

当前：**0/6 未完全验收**（架构已就绪，E2E 联调中）。

---

## 阶段规划

### Phase 6 — E2E 联调闭环（当前，最高优先级）

**目标**：第一条 PC 规则在真机从上传、scope、目标重启到 hook 命中全链路可重复。

| 任务 | 文件/命令 | 完成标准 |
|------|-----------|----------|
| 设备联调 smoke-hook | `rules/test/smoke-joyose.rule.toml` | `rule run` 收到 generation 匹配的 `hook_loaded` / `hook_hit` |
| 修复 XposedService 未连接 UX | `XposedServiceCoordinator.kt`, `MainActivity.kt` | 状态条准确、重试提示清晰 |
| `EnvironmentChecker` 增加 remote_files | `EnvironmentChecker.kt` | ✅ 本地实现 `checkRemoteRules`（缺失规则文件报 FAIL + hint）；真机确认检查项显示 OK/WARN |
| Hook 模块 generation ack | `ModspecModule.kt`, `RuleEngine.kt` | ✅ 本地实现；真机确认 `hook_loaded` / `hook_hit` |
| 文档：最小联调手册 | `docs/CONTINUE.md` | 按文档可复现 |

**里程碑**：`v0.1.0-alpha.1` — 私仓 tag，附 debug APK 构建说明。

---

### Phase 7 — 稳定性与可观测性

**目标**：出了问题能定位，不靠猜。

| 任务 | 说明 |
|------|------|
| 共享日志环缓冲 | ✅ 已实现：Agent 进程内 `EventJournal`（有界 2000 条）+ `files/events/events.ndjson` 追加式 journal（512KB 原子旋转） |
| `collect_logs` 可靠游标 | ✅ 已实现：单调 `event_id` 不透明游标 + `truncated` 缺口上报，替代纯 logcat 毫秒游标 |
| Hook 进程事件摄入 | ✅ 已实现：`EventTailer` 后台 tail logcat → journal（libxposed API 102 无 hook→service 写通道，logcat 为事实通道）；真机验证待做 |
| `rule run` 编排可测 | ✅ 已实现：重构进 `modspec-protocol::session`，fake-Agent 覆盖成功/失败/代次隔离/超时/参数 |
| restart 诊断 | ✅ 已实现：not_installed / needs_trigger / launch_failed / failed 区分，先验安装 |
| 原始日志诊断 `device logs` | ✅ 已实现：`get_logs` RPC（package/tag/since 过滤、有界、root 降级上报），与 Hook 事件环分离 |
| 只读模块诊断 `device diagnostics` | ✅ 已实现：`module_diagnostics`（框架/scope/active_rules/generation/事件源/tailer 状态），无任意 root 文件读取 |
| `profile verify` CLI 命令 | ✅ 本地（真机验收待做） | `modspec profile verify`：RPC `verify`（Agent 真实 drift）+ `module_diagnostics`（scope⊆ / rule∈active_rules）+ `[verify] lsposed_log` 模式经 `collect_logs` 匹配；`verify_result ok=N failed=N skipped=N` |
| `get_status` 不阻塞 | RPC 超时问题根治 |
| 错误码统一 | CLI / Agent / MCP 同一套 `ModspecError` |

**里程碑**：`v0.1.0-beta` — 可给少量内测用户。

---

### Phase 8 — WebSocket RPC 与传输层

**目标**：CLI 默认走 WS `:8765`，HTTP 仅 health/配对。

| 任务 | 说明 |
|------|------|
| `LocalWsServer` 完整 JSON-RPC | ✅ 与 `RpcHandler` 共用逻辑（新只读/触发 RPC 自动可用） |
| 类型化 ADB 传输层 | ✅ `modspec-adb`：discovery/forward/install/pull/ui-tree，参数先校验；屏幕流式委托 `scrcpy -s <serial>` |
| `modspec-protocol` 真连接 | 去掉 offline 桩为主路径 |
| TLS / token 生命周期 | 当前已有配对 bearer token；补吊销、轮换 UX 与加密传输 |
| 断线重连 | CLI 侧 exponential backoff |

---

### Phase 9 — Profile mod 类型补全

**目标**：`.mspec.toml` 里声明的 mod 都能执行。

| type | 现状 | 下一步 |
|------|------|--------|
| `rule_ref` | ✅ 基本可用 | variants 真机覆盖更多 ROM |
| `reload` | ✅ force-stop / hotReload | 与 verify 联动 |
| `module_scope` | 🚧 部分 | 对接 `LsposedCli scope` |
| `module_prefs` | 🚧 桩 | `ModulePrefsWriter` 覆盖 HyperCeiler 等 |
| `remote_prefs` | ✅ 架构有 | 更多第三方模块模板 |
| `dexkit_rule` | ✅ | 文档 + 示例规则 |

---

### Phase 10 — 社区与规则生态

**目标**：别人能贡献 profile/rule，不必 fork 整个 agent。

| 任务 | 说明 |
|------|------|
| `community/index.toml` 校验 CI | ✅ `.github/workflows/ci.yml` 的 rust job 内跑 `modspec community lint`（重复 id、rule id 格式、包名、路径存在性） |
| `modspec community lint` | ✅ `modspec-core::community` + CLI 子命令，内置索引 lint 全绿（含测试） |
| 规则模板生成器 | ✅ `modspec rule init --id <id> --package com.foo [--output <path>]`，生成合法起步规则并拒绝覆盖 |
| Profile 市场（远期） | 静态站点或 GitHub Pages 索引 |

---

### Phase 11 — AI 原生工作流

**目标**：AI 编辑器 / Claude 通过 MCP 完成日常调机。

| 任务 | 说明 |
|------|------|
| MCP 只读清单工具 | ✅ `device_inspect` / `app_list` / `app_info` / `process_list`（紧凑 schema，只读） |
| MCP 脚本工具 | ✅ `script_validate`（本地）/ `script_list`（只读）/ `script_deploy` / `script_enable` / `script_disable` / `script_remove`（变更，需已配对设备） |
| MCP 工具补全 | ✅ `soft_restart`（变更）/ `collect_logs`（只读游标拉取）/ `verify_profile`（drift 上报）；`verify_profile`/`collect_logs` 已在本地契约测试覆盖 |
| MCP resource | 暴露当前 `state.json`、active profile |
| AI rule / skill | 仓库内 SKILL（或编辑器 rules）教 AI 用 modspec |
| 自然语言 → profile diff | 实验性，CLI `profile suggest` |

---

### Phase 11a — JS/Lua 脚本引擎（✅ 本地，真机待验收）

**目标**：专家级 Hook 脚本成为一等公民（Rhino/LuaJ 双引擎、Frida 风格 API、结构化会话），同时保留声明式 TOML 的简单路径。

| 任务 | 说明 |
|------|------|
| 脚本包格式 + 双侧校验 | ✅ `manifest.toml`（engine/entrypoint/limits/capabilities/packages）+ 确定性 zip/SHA-256；PC `modspec-core` 与 Agent `ScriptBundleValidator` 一致；路径穿越/超限/能力白名单拒绝 |
| 双引擎 + 运行时接口 | ✅ `RhinoRuntime`（MPL-2.0）/ `LuaRuntime`（LuaJ，MIT）统一 `ScriptRuntime`；纯 Java 无 ABI 约束；JS 指令观察器执行预算、Lua 预算检测+弃置（文档注明）；确定性 dispose/unload |
| hook API（JS/Lua 对等） | ✅ `modspec.hook`（before/after/replace + callOriginal + 参数/结果改写 + thisObject）、`findClass/waitForClass`（延迟类）、重载选择（显式歧义诊断）、字段/方法/构造器反射助手、限速 `emit`/`log` |
| HookRegistry 复用 + 修复 | ✅ 每方法单句柄多回调（修复 RuleEngine `activeHandles.clear()` 泄漏与「一个 O3.b hook 挡住后续 hook」）；组合顺序确定；熔断 + 回调超时看门狗 |
| DexKit 查询 | ✅ `dexFindClass/dexFindMethod`（pkg/usingStrings/params/returnType/unique），歧义/空 → 确定性 `script_error` |
| 脚本生命周期 RPC | ✅ `script_validate/deploy/list/enable/disable/remove/reload`；`request_id` 幂等（Agent LRU 重放）；`active_script` 一等公民互斥激活；state.json 持久化 |
| PC 会话 | ✅ `modspec script validate/deploy/run/list/enable/disable/remove/reload/follow`；`run` 单命令：连接恢复 → scope → 部署 → 重启 → `script_loaded/hit/error` 等待 → 事件流 → Ctrl-C 干净退出；`--json` NDJSON |
| 验收脚本包 | ✅ `scripts/xiaomi/security-center/macro-gate`（Security Center 12.3.2）：DexKit/静态/延迟解析 O3$b，`g` 锚点验证，仅对 `com.ChillyRoom.DungeonShooter` 返回 true，`macro_allowed`/`hook_error` 结构化事件；JS 规范实现 + Lua 示例 |
| 真机验收 | 🚧 `script run scripts/xiaomi/security-center/macro-gate --expect hit`（见 IMPLEMENTATION.md 清单第 7 条） |

**里程碑**：脚本引擎本地全绿（cargo test + JVM 单测 + assembleDebug）；真机验收后并入 beta。

---

### Phase 12 — 发行与多设备

| 任务 | 说明 |
|------|------|
| GitHub Actions release | 自动构建 signed/debug APK |
| `modspec device` 多机管理 | ✅ devices.toml 已有；`modspec adb devices` 提供 adb 发现；多设备 `--device`/`--serial` 选择已就绪 |
| OEM profile 包 | xiaomi / oneplus / pixel 分包维护 |
| 无 root 降级路径 | 明确文档：哪些 mod 必须要 root |

**里程碑**：`v1.0.0` — 公开稳定版。

---

## 技术债（随时可插空修）

- `AppProfileApplier` / `RecommendedScope` / `DexKitResolver` / `ModulePrefsWriter` 若干 Kotlin 类型警告
- `gradle.properties` JDK 路径需本机配置（已改为注释模板）
- `references/` 克隆体不入库，需 `INTEGRATION.md` 指引重新 clone
- README 状态表与 `IMPLEMENTATION.md` 需保持同步
- Agent `ProfileApplier.kt` 在 README 提到但可能已合并进 `AppProfileApplier`
- `inspect_device` 真机验收（API 26–29 的 `refreshRate` 回退路径、SOC 字段空值、`pm list packages -i` 在不同 ROM 的输出差异）
- `verify` 的 scope 漂移检查依赖 `LsposedCli` 读 scope 能力（当前仅当 CLI 不可用时上报漂移；补 `scope get` 后可做精确比较）

---

## 刻意不做（至少 v1.0 前）

- 自建 Xposed 框架 / 替代 LSPosed
- 无能力边界的不受限脚本沙箱（脚本引擎不提供文件/网络/root/shell 能力；`modspec shell` 永不做）
- 在线账号 / 云同步 profile
- Magisk 模块打包（仅 LSPosed 模块 + CLI）

---

## 版本命名约定

| 标签 | 含义 |
|------|------|
| `v0.1.0-alpha` | 当前：骨架齐全，E2E 未验收 |
| `v0.1.0-alpha.N` | 每次联调里程碑 |
| `v0.1.0-beta` | 内测：smoke 稳定 + verify 可用 |
| `v0.1.0` | 首次公开稳定 |
| `v1.0.0` | 社区规则 + MCP 工作流成熟 |

---

## 相关文档

- [如何继续开发](./CONTINUE.md) — 立即可做的步骤与命令
- [实现清单](./IMPLEMENTATION.md) — 组件完成度
- [协议](./protocol.md) — JSON-RPC 约定
- [参考项目](./REFERENCES.md)
- [参考整合](../references/INTEGRATION.md)
