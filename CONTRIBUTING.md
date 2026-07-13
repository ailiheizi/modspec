# 贡献指南

感谢关注 ModSpec。当前为 **v0.1.0-alpha 私仓预览**，欢迎 issue 与小 PR。

## 开发前阅读

1. [README](../README.md) — 项目概览
2. [ROADMAP](./ROADMAP.md) — 阶段规划与发布门槛
3. [CONTINUE](./CONTINUE.md) — 联调步骤与文件地图
4. [protocol](./protocol.md) — RPC 约定

## 环境要求

| 组件 | 版本 |
|------|------|
| Rust | stable，edition 2021 |
| JDK | **17**（Gradle / Kotlin 1.9 不支持 JDK 25） |
| Android SDK | API 35 |
| 真机 | Root + LSPosed（开发 Hook 功能时） |

## 提交流程

```bash
cargo test --workspace
cd agent && .\gradlew.bat :app:assembleDebug   # 改了 agent 时必跑
```

- 一个 PR 聚焦一个 ROADMAP 子项
- 中文 UI 文案放 `agent/.../res/values/strings.xml`
- 不要提交：`local.properties`、构建产物、`tmp_*.json`、本机 JDK 路径
- Schema 变更需同步示例 `profiles/` / `rules/` 与 `validate` 测试

## 代码风格

- Rust：跟随现有 crate 结构，错误用 `ModspecError`
- Kotlin：与 `agent/` 现有命名一致，少抽象多直白
- 注释：只写非显而易见的业务/技术细节

## Issue 标签建议

| 标签 | 用途 |
|------|------|
| `e2e` | 真机联调 / smoke |
| `agent` | Android 模块 |
| `cli` | Rust CLI / MCP |
| `schema` | TOML / 协议 |
| `docs` | 文档 |
| `good first issue` | 新人友好小任务 |

## 参考实现

第三方克隆放本地 `references/`，不入库。见 [references/INTEGRATION.md](../references/INTEGRATION.md)。

## 许可

MIT — 见 [LICENSE](../LICENSE)。
