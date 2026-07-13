# 实现清单（agent + CLI 联调）

> 组件级完成度快照。阶段优先级见 [ROADMAP.md](./ROADMAP.md)，实操步骤见 [CONTINUE.md](./CONTINUE.md)。

## 当前进度

| 组件 | 状态 | 备注 |
|------|------|------|
| modspec-core (TOML schema) | ✅ | profile + rule + validate |
| modspec-protocol (RPC 传输) | 🚧 | HTTP 可用；WS 待完善 |
| modspec-cli (validate/pair/device/profile) | ✅ | offline + 真机 RPC |
| modspec-mcp serve (stdio MCP) | ✅ | 基础工具集 |
| modspec-agent RuleEngine (static + dexkit) | ✅ | 编译通过 |
| modspec-agent variants 分支选择 | ✅ | |
| modspec-agent AppProfileApplier | ✅ | rule_ref / reload 等 |
| RemoteRulesManager (openRemoteFile) | ✅ | legacy tmp 降级保留 |
| ModuleReloader (hotReload) | ✅ | API 102+ |
| Hook 管家 UI | ✅ | 规则/进程/日志/单按钮 |
| XposedServiceCoordinator | ✅ | 绑定状态机 |
| LogTailReader + collect_logs | ✅ | 依赖 root logcat |
| Agent HTTP :8764 | ✅ | NanoHTTPD |
| Agent WS :8765 | 🚧 | 桩/半成品 |
| E2E smoke-hook 真机验收 | 🚧 | **当前 blocker** |
| GitHub Actions CI | ❌ | 见 ROADMAP Phase 6 |
| profile verify CLI | ❌ | 见 ROADMAP Phase 7 |

## 下一步（摘自 ROADMAP Phase 6）

1. 真机跑通 `profiles/test/smoke-hook.mspec.toml`
2. `EnvironmentChecker` 增加 `remote_files` 检查
3. `ModspecModule` reload ack
4. 补 CI workflow

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

可用工具：`validate`、`show`、`list_rules`、`list_devices`、`device_status`、`apply_profile`

## Agent 目录

```text
agent/app/src/main/kotlin/com/modspec/agent/
  ModspecModule.kt           libxposed 102 入口
  RuleEngine.kt              .rule.toml → hook
  RemoteRulesManager.kt      openRemoteFile 规则同步
  ModuleReloader.kt          hotReload / force-stop
  XposedServiceCoordinator.kt  绑定状态
  HookPanelSnapshot.kt       Hook 面板数据
  AppProfileApplier.kt       profile → mods
  EnvironmentChecker.kt      环境诊断
  rpc/RpcHandler.kt          JSON-RPC
```

## CLI 命令

```bash
modspec mcp serve
modspec pair scan --code 123456 --host 192.168.1.10
modspec device status
modspec profile apply profiles/test/smoke-hook.mspec.toml
```

## 联调顺序

详见 [CONTINUE.md § smoke-hook](./CONTINUE.md#1-当前最高优先级跑通-smoke-hook)。

1. 安装 APK → LSPosed 启用 + scope
2. 打开 App → 配对码 → AgentService
3. `adb forward` → `pair scan` → `profile apply`
4. App 软重启 → `logcat` 验证

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
