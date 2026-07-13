# 实现清单（agent + CLI 联调）

## 当前进度

| 组件 | 状态 |
|------|------|
| modspec-core (TOML schema) | ✅ |
| modspec-protocol (RPC + HTTP/WS 传输) | ✅ |
| modspec-cli (validate/pair/device/profile) | ✅ |
| modspec-mcp serve (stdio MCP) | ✅ |
| modspec-agent RuleEngine (static + dexkit) | ✅ |
| modspec-agent variants 分支选择 | ✅ |
| modspec-agent AppProfileApplier | ✅ |
| module_prefs / remote_prefs / remote_blob | ✅ |
| Agent HTTP :8764 + WS :8765 | ✅ |

## MCP 配置（Cursor / Claude Desktop）

```json
{
  "mcpServers": {
    "modspec": {
      "command": "cargo",
      "args": ["run", "-p", "modspec-cli", "--", "mcp", "serve"],
      "cwd": "C:/path/to/ai_fix"
    }
  }
}
```

可用工具：`validate`、`show`、`list_rules`、`list_devices`、`device_status`、`apply_profile`

## Agent 目录

```text
agent/app/src/main/kotlin/com/modspec/agent/
  ModspecModule.kt      libxposed 102 入口
  RuleEngine.kt         .rule.toml → libxposed Hook (+ DexKit)
  DexKitResolver.kt     dexkit 方法定位
  AppProfileApplier.kt  profile JSON → 执行
  ModulePrefsWriter.kt  第三方模块 prefs（root）
  RemotePrefsManager.kt libxposed remote prefs
  RemoteBlobManager.kt  libxposed remote files
  AgentStorage.kt       state.json + reload marker
  assets/rules/         内置规则库
```

## CLI 命令

```bash
modspec mcp serve
modspec pair scan --code 123456 --host 192.168.1.10
modspec device status
modspec profile apply profiles/xiaomi/hyper-perf-pack.mspec.toml
```

## 联调顺序

1. 安装 agent APK，LSPosed 启用模块并勾选 scope
2. 打开 App → 配对码 → AgentService 启动
3. `modspec pair scan` → `device status` → `profile apply`
4. AI 侧：`modspec mcp serve` 注册到 Cursor MCP

## 构建 agent

- JDK **17**
- `cd agent && gradle wrapper && .\gradlew.bat :app:assembleDebug`

## 参考

- [REFERENCES.md](./REFERENCES.md)
- [protocol.md](./protocol.md)
