# 参考项目整合说明

本目录存放从公开仓库克隆的参考实现，供 ModSpec agent 对照与借鉴。**不直接 fork 进主工程**，只抽取模式。

## 已克隆仓库

| 目录 | 来源 | 借鉴点 |
|------|------|--------|
| `libxposed-example/` | [libxposed/example](https://github.com/libxposed/example) | `hotReloadModule`、`runningTargets`、单按钮 reload UI |
| `libxposed-service/` | [libxposed/service](https://github.com/libxposed/service) | `XposedServiceHelper`、`openRemoteFile`、`RemotePreferences` |
| `libxposed-api/` | [libxposed/api](https://github.com/libxposed/api) | Hook phase、API 版本约定 |
| `HMA-OSS/` | [frknkrc44/HMA-OSS](https://github.com/frknkrc44/HMA-OSS) | 绑定状态机、日志收集、`writeConfig` 重载链 |

## 未完整克隆

| 项目 | 说明 |
|------|------|
| **JsHook** | GitHub 多为模块仓库页，完整 App 源码未公开；产品模式参考其「按包脚本列表 + 重载」 |

## 已迁入 ModSpec 的对应实现

| 参考模式 | ModSpec 文件 |
|----------|----------------|
| libxposed `openRemoteFile` 规则同步 | `RemoteRulesManager.kt`、`RuleEngine.kt` |
| example `hotReloadModule` | `ModuleReloader.kt` |
| example / JsHook 运行目标列表 | `HookPanelLoader`、`MainActivity` Hook 面板 |
| HMA `ServiceClient` 状态 UX | `XposedServiceCoordinator.kt` |
| HMA / CLI `collect_logs` | `LogTailReader.kt`、`rpc/RpcHandler.kt` |
| RemotePreferences `rules_generation` | `ModuleReloader.publishRulesGeneration` |

## 刻意未移植

- HMA 自定义 AIDL `IHMAService`（已有 libxposed `XposedService`）
- ContentProvider 推 binder（LSPosed 自带 `XposedServiceHelper`）
- `/data/misc` 专用配置树（ModSpec 用 agent `files/rules` + remote file）

## 更新克隆

```powershell
cd references
git -C libxposed-example pull
git -C libxposed-service pull
git -C libxposed-api pull
git -C HMA-OSS pull
```
