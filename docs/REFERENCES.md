# 参考项目与 modspec 借鉴点

modspec 是 LSPosed 生态里首个「声明式 profile + 规则库 + CLI 编排」方向；以下项目无直接等价物，但各取所长。

## 直接相关

| 项目 | 链接 | 借鉴 |
|------|------|------|
| **LSPosed / libxposed API** | [LSPosed](https://github.com/LSPosed/LSPosed), [libxposed/api](https://github.com/libxposed/api) | Hook 生命周期 phase、`scope.list`、`module.prop`、API 102 拦截链模型 |
| **LSPosed CLI (fork)** | [mywalkb/LSPosed_mod](https://github.com/mywalkb/LSPosed_mod/wiki/CLI), [JingMatrix/Vector PR#400](https://github.com/JingMatrix/Vector/pull/400) | `modules` / `scope` / `backup` / `restore` / `log` 子命令 → modspec `lsposed_*` mod types |
| **HMA-OSS** | [frknkrc44/HMA-OSS](https://github.com/frknkrc44/HMA-OSS) | **JSON 配置热重载**、`writeConfig` + `onConfigChanged` → agent 侧 profile/state 热更新 |
| **HyperCeiler** | [ReChronoRain/HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) | `rules/` 分包组织、DexKit + cache、按 ROM 分支 → `.rule.toml` `variants` |
| **DexKit** | [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit) | 混淆 ROM 方法定位 → `target.resolver = "dexkit"` |
| **AppRetentionHook** | [HChenX/AppRetention](https://github.com/HChenX/AppRetention) | 跨 OEM scope 表 → profile `module_ref` 文档 |
| **MIUIPerfSaver** | [rdtoy/MIUIPerfSaver](https://github.com/rdtoy/MIUIPerfSaver) | joyose/powerkeeper 编排 → `hyper-perf-pack.mspec.toml` |
| **SpoofMyDevice** | [BuSung-dev/SpoofMyDevice](https://github.com/BuSung-dev/SpoofMyDevice) | 配置文件为 UI/Hook 单一数据源 → `state.json` |
| **LSPosed universal template** | [Jordan231111/lsposed-universal-template](https://github.com/Jordan231111/lsposed-universal-template) | libxposed 101+ 入口、FeatureRegistry |

## 通信 / CLI（非 LSPosed，仅架构参考）

| 项目 | 借鉴 |
|------|------|
| [plainhub/plainapp-cli](https://github.com/plainhub/plainapp-cli) | Rust CLI + 手机 HTTPS API + Token |
| [KDE Connect / cosmic-ext-connect-core](https://github.com/olafkfreund/cosmic-ext-connect-core) | mDNS + TLS 配对 + Rust 协议库 |

## 刻意不做

- **HyperCeiler 式全量 Java rules/**：modspec 用 TOML 规则 + agent 运行时编译，AI 写 TOML 而非 APK
- **自建屏幕/输入通道**：设备授权与转发复用 ADB，屏幕控制复用 scrcpy；LSPosed 管理仍走 Agent 内正式 service/CLI

## 差异总结

```text
HyperCeiler     = 单模块、硬编码 rules/*.java、无 CLI、无 profile 标准
HMA-OSS         = JSON config + 热重载，但仅服务自身 Hook、无通用规则库
LSPosed CLI     = 框架管理，无 Hook 规则、无 PC 编排
modspec         = TOML profile + rule 库 + CLI + agent + 社区索引
```

## 脚本引擎参考（JS/Lua hook API 设计，2026-08）

ModSpec 的脚本引擎（`modspec script` + Rhino/LuaJ 运行时）独立实现，**未复制任何受保护源码**；以下项目仅提供 API 形态与坑位参考：

| 项目 | 许可证 | 借鉴点（概念层面） | 未采用/边界 |
|------|--------|--------------------|-------------|
| [LSPilot](https://github.com/YunJavaPro/LSPilot) + [LSPilot-Docs](https://github.com/YunJavaPro/LSPilot-Docs) + [me.yun.lspilot](https://github.com/Xposed-Modules-Repo/me.yun.lspilot) | 无显式许可证 → **仅参考** | 具名 hook（`hookMethodBefore/After/Replace` + `replaceHook(id)`/`unhook(id)`）、`param.args/result/thisObject` 调用上下文、`findClass/findClassOrNull` + host classloader、DexKit builder 风格查询、`log`/`toast` 助手、JS(LSPilot 用 Rhino)/Lua(LuaJIT) 双引擎思路、主机侧 `hostLoader` 类加载 | 不采用手机端 AI 主流程、宽泛存储/网络权限、`setTimeout` 缺失等引擎边界；ModSpec 的 `modspec.*` API、结构化事件与安全能力模型为独立设计 |
| [LuaHook](https://github.com/KuLiPai/LuaHook) + [LuaHook-Scripts](https://github.com/KuLiPai/LuaHook-Scripts) + [luahook-docs](https://github.com/KuLiPai/luahook-docs) | GPL-3.0 → **仅参考，未并入** | `hook { class=…, method=…, before/after=function(it) … end }` 表式声明、`it.thisObject`/`it.args` 上下文、`invoke(thisObject, "name")` 反射助手、DexFinder(DexKit) 查询 | GPL 代码不进入 ModSpec（AGPL 项目可兼容 GPL 分发但本实现独立重写，见 THIRD_PARTY_NOTICES） |
| [JsHook (me.jsonet.jshook)](https://github.com/Xposed-Modules-Repo/me.jsonet.jshook) | 无显式许可证 → **仅参考** | Rhino 作为 Android JS 引擎的可行性验证（纯 Java、无 ABI）、Java 反射桥接的坑位（方法重载、包装字符串、varargs） | 不采用其宽泛的 native/内存注入面；ModSpec 只做受控 Java 层 hook |
| [Frida](https://github.com/frida/frida) / [frida-tools](https://github.com/frida/frida) | LGPL-2.1 / MIT(tools) | `Interceptor.replace` 的 replace/before/after 语义、`callOriginal` 概念、`send()` 结构化消息、script load/unload 生命周期 | ModSpec 以「结构化会话 + 可复现脚本/档案」为中心，而非通用注入 |

**许可证说明**：LSPilot / JsHook 仓库无显式许可证，源码仅作阅读参考，不复制、不衍生；LuaHook 为 GPL-3.0，仅研究其 API 形态。ModSpec 脚本引擎的 HookRegistry（每方法单句柄复用）、`modspec.*` API、能力白名单、请求幂等与事件模型均为独立实现。依赖许可证见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

## PC-first 调试闭环设计笔记（2026-08）

### 直接复用与 Agent 自有边界

- 直接复用 **ADB** 做设备授权、serial 选择、端口转发、进程/Activity/Service 辅助操作；屏幕与输入直接调用 **scrcpy/Appium/Maestro**，ModSpec 不重造。
- 任意脚本注入与动态分析直接联动 **Frida/JADX/DexKit**；ModSpec 只负责声明式 LSPosed 规则的部署、scope、reload、日志与可复现 profile。
- Agent 必须自己提供：规则原子落盘、RemoteFile/RemotePreferences generation、正式 LSPosed service/CLI scope、hook 生命周期 ack、结构化事件、成功结果保存。
- 内部 SQLite 读取只作为诊断降级；管理动作优先 libxposed service 或 LSPosed CLI。

### 最小事件模型

| 事件 | 产生方 | 成功含义 |
|------|--------|----------|
| `rule_uploaded` | Agent RPC ack / CLI | 内容已校验、原子落盘并发布 generation |
| `reload_started` | CLI | 即将重启/触发目标 |
| `target_restarted` | Agent | force-stop 后 Launcher 已重新启动 |
| `hook_loaded` | Hook 进程 | 目标方法已解析并注册 interceptor |
| `hook_hit` | Hook 进程 | interceptor 实际执行（限频，避免日志爆量） |
| `hook_error` | Hook 进程 | 规则/目标解析或 interceptor 运行失败；scope/restart 失败分别由 RPC error 与 CLI 生命周期行明确反馈 |
| `script_uploaded/enabled/disabled/reload_started/loaded/unloaded` | Agent / Hook 进程 | 脚本生命周期各阶段（`script_loaded` 表示运行时就绪并安装了 hook） |
| `script_hit` / `script_message` / `script_error` | Hook 进程 | 脚本 hook 命中（限频）/ 脚本 console 输出 / 脚本失败（含熔断、超时、DexKit 歧义诊断） |
| `session_success` / `session_failure` | PC（`script run`） | 会话终止结果（JSON 模式为结构化行） |

`hook_*` / `script_*` 事件必须携带 `timestamp_ms/event_id/event/generation/rule_id?/script_id?/package/message`；早期解析阶段的错误事件可能没有 rule/script/package。PC 对交互 session 使用精确 generation 匹配，避免时钟偏差、陈旧日志及并发部署误判；增量采集使用 Agent 自有的单调 `event_id` 不透明游标（`after_event_id`/`next_event_id`），替代 logcat 毫秒游标。未来 WebSocket push 沿用同一模型。

### CLI session

`modspec rule run <file>` 类比 Frida 的短会话：确定唯一 paired device → 上传完整规则 → 确保 scope → reload/restart → 每 500ms 拉结构化日志 → 达到 `loaded` 或 `hit` 验收条件后退出。Ctrl-C 停止拉取并释放本地连接；规则默认保留用于下一次迭代，未来可加显式 `--ephemeral`。

### 无真机测试（本地验证，不等同真机 E2E）

- **协议 + 编排（Rust）**：`modspec-protocol/tests/` 用 loopback fake Agent 真实走 HTTP transport。`fake_agent.rs` 断言 `deploy_rule → restart_targets → collect_logs` 方法/参数、`event_id` 游标、Bearer 认证头、401→`Unauthorized`、JSON-RPC error。`rule_session.rs` 覆盖 `modspec-protocol::session`（`rule run` 编排）的 loaded/hit 成功、`hook_error`、重启失败不被误标为 hook_error、`needs_trigger`/`not_installed`/`launch_failed` 上报、精确代次隔离、超时 deadline、游标推进与请求参数、无作用域/system 前置校验。
- **Agent 事件环（Kotlin JVM）**：`:app:testDebugUnitTest` 覆盖 `EventJournal` 单调 id、有界性、批量游标无重复/丢失、过滤、`truncated` 缺口、logcat 去重、journal 持久化/重播种。
- **APK**：`:app:assembleDebug` 覆盖 Agent 协议实现的编译。
- 真机只剩 LSPosed scope/RemoteFile/RemotePreferences/hook 命中 与 logcat→journal 摄入验收。

### 安全边界

- 开发版 Agent HTTP/WS 默认仅监听手机 `127.0.0.1`，通过用户已授权的 ADB 设备转发；配对返回高熵 bearer token，后续 HTTP/WS RPC 强制鉴权。重新配对会轮换 token。
- `rule run` 只允许规则声明的合法 Android 包名，执行前明确打印 device/rule/targets；规则大小限制 1 MiB。
- shell 命令不接受自由文本；危险的任意 `post_action` 不属于单规则 session。
- 未来恢复 LAN 时必须先完成能力协商、token 吊销 UX 与 TLS，参考 PlainApp/KDE Connect，而不是扩大当前配对系统。

### 重点参考

- [Frida](https://github.com/frida/frida) / [frida-tools](https://github.com/frida/frida-tools)：spawn/attach、session detach、script load/unload、message channel、CLI 设备选择。
- [scrcpy](https://github.com/Genymobile/scrcpy)：ADB server 部署、serial、forward/reverse、退出清理；屏幕控制直接复用。
- [Appium](https://github.com/appium/appium) / [Maestro](https://github.com/mobile-dev-inc/Maestro)：session/capabilities、标准错误、声明式 flow、自动等待与失败回调。
- [QuJing](https://github.com/Mocha-L/QuJing) / [XServer](https://github.com/monkeylord/XServer)：Xposed 手机服务 + PC 选择目标；避免开放网络、重启丢配置与无界日志。
- [LSPilot](https://github.com/Xposed-Modules-Repo/me.yun.lspilot)：可借鉴具名 Hook 的 replace/unhook、Logcat + 文件日志、断点保存；**不采用**手机端 AI 主流程和宽泛存储/网络权限（脚本引擎的 API 形态参考见上表）。
- [pidcat](https://github.com/JakeWharton/pidcat)：按应用而不是短命 PID 过滤日志；ModSpec进一步返回结构化事件。
- [libxposed API](https://github.com/libxposed/api) / [service](https://github.com/libxposed/service) / [LSPosed_mod CLI](https://github.com/mywalkb/LSPosed_mod)：RemoteFile、RemotePreferences、runningTargets、hotReload、scope。
- [mobile-mcp](https://github.com/mobile-next/mobile-mcp) / [appium-mcp](https://github.com/appium/appium-mcp)：低 token 工具结果与成熟自动化后端封装。
