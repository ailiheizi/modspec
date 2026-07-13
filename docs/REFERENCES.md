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
- **ADB 主通道**：LSPosed 管理走 agent 内 `lsposed-cli` + 局域网 RPC

## 差异总结

```text
HyperCeiler     = 单模块、硬编码 rules/*.java、无 CLI、无 profile 标准
HMA-OSS         = JSON config + 热重载，但仅服务自身 Hook、无通用规则库
LSPosed CLI     = 框架管理，无 Hook 规则、无 PC 编排
modspec         = TOML profile + rule 库 + CLI + agent + 社区索引
```
