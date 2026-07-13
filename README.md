# ModSpec

LSPosed 生态的配置编排平台：**Rust CLI** + **LSPosed 模块 (modspec-agent)**。

- 声明式 **`.mspec.toml`** profile（模块 scope、规则引用、重应用、验证）
- 可复用 **`.rule.toml`** 通用 Hook 规则库
- 社区 profile / 规则索引
- PC ↔ 手机 JSON-RPC（WebSocket `:8765`，HTTP 配对 `:8764`）

## 快速开始（CLI）

```bash
cargo test --workspace

# 本地校验
cargo run -p modspec-cli -- validate profiles/xiaomi/hyper-perf-pack.mspec.toml
cargo run -p modspec-cli -- rule list
cargo run -p modspec-cli -- show profiles/xiaomi/hyper-perf-pack.mspec.toml

# 配对 + 真实 RPC
cargo run -p modspec-cli -- pair scan --code 123456 --host 192.168.1.10
cargo run -p modspec-cli -- device status
cargo run -p modspec-cli -- profile apply profiles/xiaomi/hyper-perf-pack.mspec.toml --dry-run

# MCP server（供 Cursor / Claude 调用）
cargo run -p modspec-cli -- mcp serve
```

## 仓库结构

```text
crates/
  modspec-core/       TOML schema、devices 存储、校验
  modspec-protocol/   JSON-RPC + HTTP/WS 传输
  modspec-cli/        命令行 + mcp serve
  modspec-mcp/        MCP stdio 工具服务
agent/                LSPosed 模块 APK（Kotlin）
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

## Agent（LSPosed 模块）

见 [agent/README.md](agent/README.md)、[docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) 与 [docs/CONTINUE.md](docs/CONTINUE.md)。

- libxposed API **102**，`RuleEngine`（static + DexKit）+ `AppProfileApplier`
- `LsposedCli` → `/data/adb/lspd/bin/cli`
- `AgentService` → HTTP `:8764` + WebSocket `:8765`
- 构建需 **JDK 17**：`cd agent && .\gradlew.bat :app:assembleDebug`

## 参考项目

[docs/REFERENCES.md](docs/REFERENCES.md) — HMA-OSS、HyperCeiler、LSPosed_mod CLI、libxposed 等。

## 状态（v0.1.0-alpha，实验性）

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | TOML schema + 示例 + 本地 CLI | ✅ |
| 2 | Agent 骨架 + RPC + pair/apply CLI | ✅ |
| 3 | HTTP :8764 + RuleEngine Hook + DexKit | ✅ 本地编译通过 |
| 4 | ProfileApplier + remote file/prefs 规则通道 | ✅ 架构就绪 |
| 5 | MCP server (`modspec mcp serve`) | ✅ |
| 6 | 端到端 Hook 冒烟（真机 joyose 等） | 🚧 联调中 |

> 当前适合开发者预览与自测，**尚未**作为稳定发行版推荐普通用户安装。  
> 下一步做什么 → [docs/CONTINUE.md](docs/CONTINUE.md) · 长期规划 → [docs/ROADMAP.md](docs/ROADMAP.md)
