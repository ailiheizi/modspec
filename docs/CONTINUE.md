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

# 配对（App 首页 6 位码）
cargo run -p modspec-cli -- pair scan --code <码> --host 127.0.0.1 --port 9876

# 查看状态
cargo run -p modspec-cli -- device status

# 应用冒烟 profile
cargo run -p modspec-cli -- profile apply profiles/test/smoke-hook.mspec.toml --host 127.0.0.1 --port 9876
```

### 1.3 软重启 + 验证 hook

1. App 内点 **软重启模块**（或 CLI `soft_restart` RPC）
2. 触发 joyose（无 Launcher，可用）：
   ```bash
   adb shell am startservice -n com.xiaomi.joyose/.gputuner.GPUTunerService
   ```
3. 看日志：
   ```bash
   adb logcat -s ModspecRuleEngine ModspecModule
   ```
4. 期望：`smoke-joyose` / `hooked` 相关行

### 1.4 失败时排查顺序

| 现象 | 先查 |
|------|------|
| RPC 超时 | App 是否前台打开过；`adb forward` 是否还在 |
| XposedService 未连接 | 模块是否启用；冷启动 App；看 Hook 面板状态条 |
| 规则未加载 | `RemoteRulesManager`；环境检查；`files/rules/*.rule.toml` |
| joyose 无 log | scope 是否含 joyose；是否软重启；进程是否启动 |
| 仍走 tmp 降级 | `ModspecApp.xposedService` 是否为 null |

---

## 2. 推荐开发顺序（单人 / AI 协作）

按依赖关系排序，**不要跳步**：

```
① smoke-hook E2E 通过
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
| `crates/modspec-cli/src/commands/profile.rs` | apply / diff |
| `crates/modspec-mcp/src/tools.rs` | MCP 工具定义 |

### Android Agent

| 路径 | 职责 |
|------|------|
| `ModspecModule.kt` | Xposed 入口、加载 RuleEngine |
| `RuleEngine.kt` | 解析规则、下 hook |
| `RemoteRulesManager.kt` | openRemoteFile 同步规则 |
| `ModuleReloader.kt` | hotReload / force-stop |
| `AppProfileApplier.kt` | profile JSON → 执行 mods |
| `MainActivity.kt` + `HookPanelSnapshot.kt` | Hook 管家 UI |
| `rpc/RpcHandler.kt` | JSON-RPC 方法表 |
| `EnvironmentChecker.kt` | 环境诊断 |

### 配置与示例

| 路径 | 职责 |
|------|------|
| `profiles/test/smoke-hook.mspec.toml` | 冒烟 profile |
| `rules/test/smoke-joyose.rule.toml` | 冒烟规则 |
| `agent/app/src/main/assets/rules/` | 打进 APK 的内置规则 |

---

## 4. 常见开发任务食谱

### 新增一条 Hook 规则

1. 在 `rules/<oem>/<pkg>/foo.rule.toml` 编写
2. `cargo run -p modspec-cli -- rule validate rules/.../foo.rule.toml`
3. 复制或同步到 `agent/app/src/main/assets/rules/`（或靠 apply 推送）
4. 在 profile 里加 `type = "rule_ref"`
5. apply → 软重启 → logcat

### 新增 profile mod 类型

1. `modspec-core/src/profile.rs` 扩展 enum
2. `AppProfileApplier.kt` 加分支
3. `validate.rs` 加校验
4. 示例写进 `profiles/`
5. 补测试

### 新增 RPC / MCP 方法

1. `RpcHandler.kt` 注册 method
2. `modspec-protocol` 加类型
3. `modspec-cli` 或 `modspec-mcp` 暴露命令/工具
4. `docs/protocol.md` 更新

### 改 Hook 管家 UI

1. `res/layout/activity_main.xml` 或 item 布局
2. `HookPanelSnapshot.kt` 数据结构
3. `MainActivity.renderHookManager()`
4. `res/values/strings.xml` 中文文案

---

## 5. 与 AI（Cursor）协作建议

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
- 「按 ROADMAP Phase 7 实现共享日志环缓冲」

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

1. **真机联调**：手机连上后完整跑 smoke-hook 并修 blocker
2. **CI**：加 `.github/workflows/ci.yml`
3. **verify 命令**：CLI 自动读 log 验收 profile
4. **改 public**：smoke 通过后改仓库可见性 + Release

说一个方向即可，我按 [ROADMAP](./ROADMAP.md) 优先级继续推进。
