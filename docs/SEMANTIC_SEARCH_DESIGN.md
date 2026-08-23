# modspec 功能语义搜索 + AI 接口 — 架构设计

> 状态：设计稿 v1（2026-08）
> 范围：profile 功能描述元数据 → 向量化 → 语义搜索 → OpenAI 兼容接口接入

## 0. 结论先行（TL;DR）

1. **不需要向量索引库。** 当前功能量级（几十~几百个 mod）下，纯 Kotlin 暴力余弦相似度单次查询 < 1ms，任何 ANN 索引都是过度设计。向量直接存 JSON 文件即可。
2. **zvec 调研结果：确实存在，但不适合本项目 Android 端。** 它是阿里巴巴开源的进程内向量数据库（基于 Proxima 引擎，Apache-2.0），有 Rust 绑定 `zvec-rust`；但官方平台支持仅 Linux(x86_64/ARM64)/macOS(ARM64)/Windows(x86_64)，**无 Android**，交叉编译到 aarch64-linux-android 无人验证过。
3. **Android 端选型：零新依赖方案** —— 向量存 `filesDir/embeddings/*.json`，Kotlin `FloatArray` 余弦循环。与项目现状一致（agent 刻意选择纯 Java 依赖，build.gradle.kts 注释明确写了 "pure-Java runtimes (no native ABI surface)"）。
4. **必须有离线 fallback**：关键词搜索永远可用；embedding 是增强层，不是前提。
5. 分期：P0 元数据 + 关键词搜索 → P1 embedding + 配置 UI + 跳转 → P2 AI 对话 → P3（可选）端侧小模型。

## 1. 现状摘要（读代码确认）

### 1.1 Rust CLI（modspec-core）

`crates/modspec-core/src/profile.rs`：

- `Profile { mspec_version, meta, device, mods: Vec<ModEntry>, reapply, verify }`
- `Meta` 已有 `description / tags / author / version / requires`
- `ModEntry` 是 13 变体 tag 枚举（lsposed_module / scope / module_ref / module_prefs / rule_ref / hook / dynamic_scope / remote_prefs / remote_blob / lsposed_restore / reload / post_action / shell_toggle）
- **mod 级别公共字段只有 `id / enabled`**；`ShellToggle` 有 `title`，`ModuleRef` 有 `note`，其余变体无人类可读描述 → 这是元数据格式要解决的核心缺口
- profile 为 TOML；agent 收到的是 JSON

### 1.2 Android agent

- `MainActivity.kt`：3 个底部 tab —— 快捷开关(ShortcutsPage) / Hook 管理(HookPage) / 环境(EnvPage)；ShortcutsPage 渲染 `ShellToggleRow` 卡片列表，是**搜索栏的自然落点**
- `AppProfileApplier.applyFromJson()`：profile 以 JSONObject 到达 agent，apply 后整体写入 `filesDir/profiles/<id>.json`（AgentStorage.profilesDir）
- **关键推论：agent 磁盘上已有完整 profile JSON（含未来新增的 description），搜索可直接消费它，无需新传输通道**
- 依赖现状（app/build.gradle.kts）：NanoHTTPD / tomlj / Java-WebSocket / DexKit / Rhino / LuaJ / Compose。**没有任何 HTTP 客户端库**

## 2. 向量库选型调研

### 2.1 候选对比

| 方案 | Android 兼容性 | 引入成本 | 评价 |
|---|---|---|---|
| **纯 Kotlin 暴力余弦** | 无条件 | 零依赖 ~50 行 | **推荐**。500 条 × 1536 维 ≈ 亚毫秒 |
| **zvec**（Alibaba/Proxima） | 无官方 Android | 需交叉编译 C++ | agent 端不可用；CLI 可用但没必要 |
| **usearch**（unum-cloud） | 宣称支持，Java 绑定存在但需 NDK/JNI 自建 | JNI + NDK 工具链 | 可行但破坏"少 native 面"原则；百条量级 HNSW 无意义 |
| **ObjectBox Vector Search** | 一等公民（Kotlin API + HNSW + <8MB so） | Gradle 插件 + 注解处理 + native so | 为几百条引入整个对象数据库，过重 |
| **sqlite-vec** | 有预编译 .so（v0.1.2+），但 framework SQLite 加载扩展麻烦；pre-v1 有 breaking change | 中 | 它本身也是暴力扫描——性能与方案一同阶还要背扩展复杂度 |
| **sqliteai/sqlite-vector** | 有 Android 二进制 | 中 | Elastic License 2.0（仅 OSI 开源免费）需评估；同为暴力扫描内核 |
| **Couchbase Lite Vector** | 支持 | 重 | 需要 Sync Gateway 才有完整能力，不匹配 |

### 2.2 zvec 详细调研（回应作者疑问）

- 是什么：[alibaba/zvec](https://github.com/alibaba/zvec)，2026 年开源的进程内向量数据库，定位"向量数据库界的 SQLite"，底层为阿里生产级 Proxima 引擎。特性：dense+sparse 向量、FTS+向量混合检索（RRF 融合）、标量过滤、schema evolution、CRUD 持久化。
- Rust 绑定：存在两个——官方系 [`zvec-rust`](https://github.com/zvec-ai/zvec-rust)（crates.io 0.6.x，Apache-2.0，FFI 包装 libzvec_c_api，自动下载 prebuilt 库）和第三方 `zvec-bindings`。
- "zvec 可以嵌入"对一半：
  - Rust CLI / 桌面场景：可以嵌入（macOS ARM64 / Linux x86_64+ARM64 有 CI 与 prebuilt 库）
  - Android agent：平台矩阵不含 Android；prebuilt 分发不覆盖 aarch64-linux-android；Proxima C++ 栈在 NDK 上未经上游验证
- 即使能上也不该上：zvec 的价值在百万级向量的 ANN 性能与混合检索，本场景 N≈10² 全部触发不到。

### 2.3 选型结论

```
规模：N ≤ 1000 功能，dim ≤ 3072
内存：N × dim × 4B ≈ 最坏 12MB（实际 << 1MB）
延迟：单次 FloatArray 遍历，手机 CPU 亚毫秒
→ ANN 在此规模收益为零，只增加构建复杂度与召回损失
```

**决定：Android 端与 Rust CLI 端统一采用「JSON 文件存储 + 暴力余弦」。**
把检索抽象成 `EmbeddingStore` 接口（`search(queryVec, topK): List<Hit>`），将来若功能真涨到万级，接口后面换 usearch/ObjectBox 即可，上层无感。

## 3. 整体架构（文字图）

```
Rust CLI (modspec-cli)
  profile.toml ─parse→ ModEntry(+description/tags) ─serde_json─RPC(现有通道)→ agent

Android Agent App（主 app 进程；hooked 进程绝不参与）
┌────────────────────────────────────────────────────────────┐
│ Compose UI                                                 │
│   快捷开关 tab                                              │
│     [搜索框 SearchBar]                                      │
│       ├ 结果卡片(title/desc/相似度/来源) ─tap→ 跳转开关/规则   │
│       └ "问 AI" 入口 (P2)                                   │
│           │ query                                          │
│           ▼                                                │
│ SearchController                                           │
│   ├ 无 API key → KeywordSearcher（纯本地，永远兜底）          │
│   └ 有 API key                                             │
│       ├ EmbeddingClient ─HTTP→ OpenAI 兼容 /embeddings      │
│       └ HybridRanker: cosine ⊕ keyword → top-K             │
│                                                            │
│ EmbeddingManager                                           │
│   ├ 读 profiles/<id>.json → 组装 searchable text → hash    │
│   ├ 与 embeddings/<id>.cache.json diff，增量调 API          │
│   └ 写缓存 {model, dim, items:[{mod_id, text_hash, vec}]}   │
│                                                            │
│ AiConfigStore：endpoint/key/model（环境 tab "AI 设置"卡片）  │
└────────────────────────────────────────────────────────────┘
```

组件职责：
- Profile 元数据 = 唯一事实源，随 profile 分发
- KeywordSearcher = 零依赖兜底（中文 bigram + 原文子串 + tags/aliases 匹配打分）
- EmbeddingClient = 唯一网络出站点（仅主 app 进程）
- HybridRanker = `0.7·cosine + 0.3·keyword_norm` 或简单 RRF 合并
- 导航 = 搜索结果携带 `(tab_index, mod_id)`，点击切 tab 并滚动高亮

## 4. 数据流

### 4.1 索引建立（懒加载 + 增量）

```
打开 app / 进入快捷开关 tab
 → EmbeddingManager.ensureIndex(activeProfileJson)
    1. 每个 mod 组装 searchable text："«type 中文标签» «title/note» «description» «tags» «aliases»"
    2. sha256(text) 得 text_hash
    3. 读缓存：model/dim 不匹配则全量失效；diff 出变化项
    4. 有 diff 且已配 key：批量 POST /embeddings
    5. 合并写回 cache.json（f32 base64）
    6. 失败/离线 → 仅 keyword 路径，UI 提示"语义搜索暂不可用"
```

时机：**不在 apply 时同步生成**（apply 可能离线且不应被网络阻塞）；apply 成功后可后台 prefetch，首次搜索兜底触发。

### 4.2 查询

```
输入（300ms debounce）
 → keyword 结果立即展示（<5ms）
 → 语义开启时 embed(query) → 余弦遍历 → 合并重排 → 更新列表
 → 点击结果：切 tab + 定位卡片；长按查看完整 description
```

### 4.3 AI 对话（P2）

```
"问 AI" 提问
 → 复用查询流程取 top-5 相关 mod 作为 context
 → POST /chat/completions（system 里声明工具边界 + context）
 → 流式渲染；引用的 mod 以卡片附在回答下方
 → 安全约束：AI 只输出"建议操作"；执行 su/shell 必须用户确认，
   且命令只能引用 profile 已有的 on/off_command 原文，AI 不能注入新命令
```

## 5. 元数据格式设计

### 5.1 Rust schema 变更（modspec-core）

新增公共元数据结构 flatten 进每个变体（避免 13 个变体重复声明字段）：

```rust
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct ModCommon {
    /// 一句话功能描述（搜索主文本，建议中文）
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    /// 同义词/英文别名，提升召回（如 ["hotspot", "softap"]）
    #[serde(default)]
    pub aliases: Vec<String>,
}

// 每个 ModEntry 变体追加：
#[serde(default)]
#[serde(flatten)]
pub common: ModCommon,
```

配套 `impl ModEntry { pub fn searchable_text(&self) -> String }`：拼合 type 标签、title/note/rule、description、tags、aliases 为规范文本（CLI 校验与测试共用）。

### 5.2 profile 示例

```toml
[[mods]]
id = "hotspot-5ghz"
type = "shell_toggle"
title = "5GHz 热点"
description = "强制热点使用 5GHz 频段，速率更高、干扰更少。适合投屏、大文件传输。"
tags = ["wifi", "热点", "网络", "投屏"]
aliases = ["hotspot band", "softap 5g", "便携热点"]
on_command = "cmd wifi force-softap-band enabled 5"
off_command = "cmd wifi force-softap-band disabled"

[[mods]]
id = "block-app-network"
type = "rule_ref"
rule = "generic/firewall/block-network"
scope = ["com.example.app"]
description = "限制某应用联网（hook 网络权限检查），不影响其他应用上网。"
tags = ["联网", "防火墙", "断网", "流量"]
```

向后兼容：全部字段带 default，旧 profile 不改一字照常解析。

## 6. OpenAI 兼容接口配置

### 6.1 配置存储

`filesDir/ai_config.json`（独立于 state.json，避免被 apply 流程覆写；key 不入此文件）：

```json
{
  "endpoint": "https://api.openai.com/v1",
  "embedding_model": "text-embedding-3-small",
  "chat_model": "gpt-4o-mini",
  "semantic_search_enabled": true
}
```

- api_key 存 `EncryptedSharedPreferences`（androidx.security-crypto）；日志永不打印 key
- UI：环境 tab 新增「AI 设置」卡片 —— endpoint / key / 两个 model / 连通性测试按钮
- HTTP 客户端：可先用 `HttpURLConnection` + org.json（POST JSON + SSE 行解析），嫌麻烦再引 OkHttp（纯 Java 依赖）

### 6.2 embedding 缓存

`filesDir/embeddings/<profile_id>.cache.json`：

```json
{
  "model": "text-embedding-3-small",
  "dim": 1536,
  "version": 1,
  "items": [
    { "mod_id": "hotspot-5ghz", "text_hash": "ab12…", "vector_b64": "…" }
  ]
}
```

不变式：换 model / dim → 缓存整体作废重建；text_hash 未变的 mod 永不重复调用。

## 7. 离线 fallback 设计

| 条件 | 行为 |
|---|---|
| 未配 key / 开关关闭 | 纯关键词：bigram 匹配 title/description + tags/aliases 前缀匹配加权求和 |
| 配了 key 但请求失败 | 降级关键词，顶部提示条"语义搜索暂不可用" |
| 正常 | hybrid 合并：关键词保精确命中（如 "joyose"），语义保口语化查询（如 "限制某应用联网"） |

端侧 embedding 模型（ONNX Runtime Mobile + bge-small-zh，约 30-90MB）不进 MVP，列为 P3 观察项。

## 8. 分期实施建议

**P0 — 元数据 + 关键词搜索（最小可发布，零网络零风险）**
1. core：ModCommon flatten + `searchable_text()` + 单测
2. 内置 profiles/rules 补 description/tags
3. agent：快捷开关 tab 顶部 SearchBar + KeywordSearcher 过滤 toggles/rules

**P1 — 语义搜索 MVP**
4. AI 设置卡片 + ai_config.json + EncryptedSharedPreferences
5. EmbeddingClient（/embeddings）+ 增量缓存 + 暴力余弦 HybridRanker
6. 搜索结果跳转定位（tab + mod_id）、降级路径与错误提示

**P2 — AI 对话**
7. /chat/completions + 检索上下文注入 + 流式 UI
8. 建议-确认式动作卡（安全边界见 §4.3）

**P3 — 可选演进（触发条件才做）**
- 端侧小模型实现完全离线语义
- EmbeddingStore 后置 usearch/ObjectBox（N > 5k 才有意义）
- CLI `modspec profile embed` 预计算向量随社区包分发

## 9. 风险 / 开放问题

1. **隐私**：description 与用户 query 会发往用户自配的第三方 endpoint。必须 opt-in 默认关、设置页明示数据去向。
2. **root 设备上的 key 安全**：EncryptedSharedPreferences 在 root 下非绝对安全；本 app 本身是 root 工具，威胁模型可接受，文档声明即可。
3. **中文检索质量**：bigram 方案对 2-3 字短 query 召回一般；是否引 pinyin 匹配留 P0 实测。
4. **多 profile 场景**：当前 state 只记 active_profile 一个；缓存文件结构已按 profile_id 分片，天然支持扩展。
5. **hooked 进程隔离**：网络/UI/AiConfig 仅存在于主 app 进程，需作为代码评审硬约束。
6. **OpenAI 兼容度差异**：各网关对 /embeddings 的 batch 上限、SSE 格式、错误码不完全一致，EmbeddingClient 要宽容解析并限制单批条数。
7. **zvec 若未来官方支持 Android**（其 Dart/Flutter SDK 暗示有移动意向），可在 P3 重新评估；当前结论不变。
