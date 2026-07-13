# ModSpec 路线图

> 最后更新：2026-07-13 · 当前版本：**v0.1.0-alpha**（私仓）

## 愿景

让 LSPosed 调机从「散落脚本 + 手动 scope + 重启应用」变成：

1. **声明式 profile**（`.mspec.toml`）描述要开哪些 mod、引用哪些规则
2. **可复用规则库**（`.rule.toml`）按包/OEM 共享
3. **PC ↔ 手机编排**（CLI / MCP）一键 apply、diff、verify
4. **Agent 侧可视化**（Hook 管家）看规则、进程、日志、软重启

长期定位：**LSPosed 生态的配置编排层**，不是又一个单点 Hook 模块。

---

## 公开发布门槛（私仓 → public）

满足以下全部条件后再 `gh repo edit --visibility public` 并打 `v0.1.0` tag：

| # | 条件 | 验收方式 |
|---|------|----------|
| 1 | smoke-hook 真机通过 | `profile apply` → 软重启 → `logcat` 见 `smoke-joyose.*hooked` |
| 2 | XposedService 绑定可复现 | 冷启动 App → Hook 面板显示「已连接」 |
| 3 | 规则跨进程生效 | 不靠 `su cp /data/local/tmp`，`openRemoteFile` 主路径稳定 |
| 4 | README 最小联调路径 | 新环境 30 分钟内能跑通 pair → apply → verify |
| 5 | CI 绿 | `cargo test` + `assembleDebug` 在 GitHub Actions 通过 |
| 6 | 无机器专属路径 | `gradle.properties` / `local.properties` 不入库 |

当前：**0/6 未完全验收**（架构已就绪，E2E 联调中）。

---

## 阶段规划

### Phase 6 — E2E 联调闭环（当前，最高优先级）

**目标**：第一条 profile 在真机从 apply 到 hook 命中全链路可重复。

| 任务 | 文件/命令 | 完成标准 |
|------|-----------|----------|
| 设备联调 smoke-hook | `profiles/test/smoke-hook.mspec.toml` | verify.checks 通过 |
| 修复 XposedService 未连接 UX | `XposedServiceCoordinator.kt`, `MainActivity.kt` | 状态条准确、重试提示清晰 |
| `EnvironmentChecker` 增加 remote_files | `EnvironmentChecker.kt` | 检查项显示规则通道 OK/WARN |
| Hook 模块 reload ack | `ModspecModule.kt`, `RuleEngine.kt` | 软重启后 log 有 reload 确认 |
| 文档：最小联调手册 | `docs/CONTINUE.md` | 按文档可复现 |

**里程碑**：`v0.1.0-alpha.1` — 私仓 tag，附 debug APK 构建说明。

---

### Phase 7 — 稳定性与可观测性

**目标**：出了问题能定位，不靠猜。

| 任务 | 说明 |
|------|------|
| 共享日志环缓冲 | `/data/misc/modspec/logs` 或 RemoteFile，替代纯 logcat tail |
| `collect_logs` RPC 完善 | 返回结构化级别、tag、时间戳 |
| `profile verify` CLI 命令 | 读 agent 状态 + log 模式匹配 |
| `get_status` 不阻塞 | RPC 超时问题根治 |
| 错误码统一 | CLI / Agent / MCP 同一套 `ModspecError` |

**里程碑**：`v0.1.0-beta` — 可给少量内测用户。

---

### Phase 8 — WebSocket RPC 与传输层

**目标**：CLI 默认走 WS `:8765`，HTTP 仅 health/配对。

| 任务 | 说明 |
|------|------|
| `LocalWsServer` 完整 JSON-RPC | 与 `RpcHandler` 共用逻辑 |
| `modspec-protocol` 真连接 | 去掉 offline 桩为主路径 |
| TLS / 配对 token | 替换明文 HTTP（开发期可保留 cleartext 开关） |
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
| `community/index.toml` 校验 CI | PR 检查 rule id 唯一、包名合法 |
| `modspec community lint` | CLI 子命令 |
| 规则模板生成器 | `modspec rule init --package com.foo` |
| Profile 市场（远期） | 静态站点或 GitHub Pages 索引 |

---

### Phase 11 — AI 原生工作流

**目标**：Cursor / Claude 通过 MCP 完成日常调机。

| 任务 | 说明 |
|------|------|
| MCP 工具补全 | `soft_restart`, `collect_logs`, `verify_profile` |
| MCP resource | 暴露当前 `state.json`、active profile |
| Cursor rule / skill | 仓库内 `.cursor/rules` 或 SKILL 教 AI 用 modspec |
| 自然语言 → profile diff | 实验性，CLI `profile suggest` |

---

### Phase 12 — 发行与多设备

| 任务 | 说明 |
|------|------|
| GitHub Actions release | 自动构建 signed/debug APK |
| `modspec device` 多机管理 | devices.toml 已有，补 SSH/adb 发现 |
| OEM profile 包 | xiaomi / oneplus / pixel 分包维护 |
| 无 root 降级路径 | 明确文档：哪些 mod 必须要 root |

**里程碑**：`v1.0.0` — 公开稳定版。

---

## 技术债（随时可插空修）

- `AppProfileApplier` / `RecommendedScope` 若干 Kotlin 类型警告
- `gradle.properties` JDK 路径需本机配置（已改为注释模板）
- `references/` 克隆体不入库，需 `INTEGRATION.md` 指引重新 clone
- README 状态表与 `IMPLEMENTATION.md` 需保持同步
- Agent `ProfileApplier.kt` 在 README 提到但可能已合并进 `AppProfileApplier`

---

## 刻意不做（至少 v1.0 前）

- 自建 Xposed 框架 / 替代 LSPosed
- 完整 JsHook 式脚本引擎（保持声明式 TOML）
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
