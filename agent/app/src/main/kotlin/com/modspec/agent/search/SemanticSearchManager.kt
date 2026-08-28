package com.modspec.agent.search

import android.content.Context
import android.util.Log
import com.ml.shubham0204.sentence_embeddings.SentenceEmbedding
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 参与索引的开关条目（与 UI 层的 ShellToggleRow 解耦）。 */
data class IndexableToggle(
    val id: String,
    val title: String,
    val description: String?,
    val aliases: List<String>,
    val categoryTitles: List<String>,
)

/**
 * 端侧语义搜索：BAAI/bge-small-zh-v1.5（int8 量化 ONNX，384 维，中英双语，
 * XLM-Roberta Unigram tokenizer）。模型 22MB 随 APK assets 内置，首次使用时
 * 拷贝到 filesDir 即可，零下载、完全离线。
 *
 * - 引擎：shubham0204/Sentence-Embeddings-Android（ONNX Runtime + Rust HF tokenizer），
 *   last_hidden_state → attention-mask 加权 mean pooling → L2 归一化。
 * - 向量缓存 JSON 增量更新：text_hash 未变不重复 embed。
 */
object SemanticSearchManager {

    private const val TAG = "SemanticSearch"
    const val MODEL_NAME = "bge-small-zh-v1.5-int8"
    private const val MODEL_FILE = "model_quantized.onnx"
    private const val TOKENIZER_FILE = "tokenizer.json"

    /** assets 内模型目录（构建期放入，见 app/src/main/assets/models/bge-small-zh/）。 */
    private const val ASSET_DIR = "models/bge-small-zh"

    // tokenizer.json 已配置 truncation(max_length=512)，Rust 分词器会自动截断；
    // 这里再做一层字符级截断，避免极端超长文本浪费 token 计算量
    private const val MAX_TEXT_CHARS = 400
    // 低于该余弦分数视为噪声丢弃（bge-small-zh 上重新校准后如需可再调）
    private const val MIN_SCORE = 0.40f
    // 语义补充结果最多返回条数
    private const val MAX_SEMANTIC_HITS = 6

    sealed interface Status {
        data object Checking : Status

        /** 模型拷贝中（assets → filesDir，秒级）。 */
        data object Preparing : Status

        /** 初始化失败；保留关键词搜索兜底。 */
        data class Failed(val message: String) : Status

        /** 引擎就绪；indexing 表示正在重建向量缓存。 */
        data class Ready(val indexing: Boolean) : Status
    }

    class SearchHit(val id: String, val score: Float)

    private val _state = MutableStateFlow<Status>(Status.Checking)
    val state: StateFlow<Status> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ORT session 与 JNI tokenizer 指针非线程安全，所有推理/索引/搜索串行化
    private val engineMutex = Mutex()

    private var engine: SentenceEmbedding? = null
    private var storeItems: Map<String, IndexedItem> = emptyMap()
    private var dimensions: Int = 0

    fun modelDir(context: Context): File = File(context.filesDir, "embeddings/model")

    /**
     * 启动时调用：把内置模型从 assets 拷贝到 filesDir（仅首次或文件不完整时），
     * 然后初始化引擎并载入缓存索引。全程无网络。
     */
    fun ensureReady(context: Context) {
        scope.launch {
            engineMutex.withLock {
                if (_state.value is Status.Ready || _state.value is Status.Preparing) return@withLock
                _state.value = Status.Checking
                try {
                    val dir = ensureModelFiles(context)
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    initEngine(dir)
                    loadStoreCache(context)
                    _state.value = Status.Ready(indexing = false)
                    Log.i(TAG, "engine ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms " +
                        "(cached index items=${storeItems.size})")
                } catch (e: Exception) {
                    Log.w(TAG, "init failed", e)
                    engine?.close()
                    engine = null
                    _state.value = Status.Failed("语义引擎初始化失败：${e.message}")
                }
            }
        }
    }

    /** assets → filesDir 拷贝（ONNX Runtime 从路径加载最稳）；已完整则跳过。 */
    private fun ensureModelFiles(context: Context): File {
        val dir = modelDir(context)
        dir.mkdirs()
        copyAssetIfNeeded(context, "$ASSET_DIR/$MODEL_FILE", File(dir, MODEL_FILE))
        copyAssetIfNeeded(context, "$ASSET_DIR/$TOKENIZER_FILE", File(dir, TOKENIZER_FILE))
        return dir
    }

    private fun copyAssetIfNeeded(context: Context, assetPath: String, dest: File) {
        val expectedSize = context.assets.openFd(assetPath).length
        if (dest.exists() && dest.length() == expectedSize) return
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = dest.length()
        if (actual != expectedSize) {
            dest.delete()
            error("asset copy size mismatch for $assetPath: got $actual, want $expectedSize")
        }
        Log.i(TAG, "copied asset $assetPath -> ${dest.absolutePath} ($actual bytes)")
    }

    /**
     * 增量重建索引：text_hash 未变的条目直接复用缓存向量，只 embed 变化项。
     * 引擎未就绪时静默跳过（就绪后 UI 会再次触发）。
     */
    fun reindex(context: Context, toggles: List<IndexableToggle>) {
        scope.launch {
            engineMutex.withLock {
                if (engine == null || toggles.isEmpty()) return@withLock
                if (_state.value is Status.Ready) _state.value = Status.Ready(indexing = true)
                try {
                    val store = JsonEmbeddingStore.default(context)
                    val cached = store.load()
                        ?.takeIf { it.model == MODEL_NAME }
                        ?.items
                        ?.associateBy { it.id }
                        ?: emptyMap()
                    val items = ArrayList<IndexedItem>(toggles.size)
                    var embedded = 0L
                    var embedMs = 0L
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    for (t in toggles) {
                        val text = buildSearchableText(t)
                        val hash = sha256Short(text)
                        val old = cached[t.id]
                        if (old != null && old.textHash == hash && old.embedding.size == dimensions) {
                            items.add(old)
                            continue
                        }
                        val e0 = android.os.SystemClock.elapsedRealtime()
                        val vec = embed(text)
                        embedMs += android.os.SystemClock.elapsedRealtime() - e0
                        embedded++
                        dimensions = vec.size
                        items.add(IndexedItem(t.id, hash, vec))
                    }
                    store.save(StoredIndex(MODEL_NAME, dimensions, items))
                    storeItems = items.associateBy { it.id }
                    Log.i(TAG, "indexed ${items.size} toggles (dim=$dimensions, " +
                        "embedded=$embedded in $embedMs ms, avg=${if (embedded > 0) embedMs / embedded else 0} ms/item, " +
                        "total ${android.os.SystemClock.elapsedRealtime() - t0} ms)")
                } catch (e: Exception) {
                    Log.w(TAG, "reindex failed", e)
                } finally {
                    if (_state.value is Status.Ready) _state.value = Status.Ready(indexing = false)
                }
            }
        }
    }

    /**
     * 语义检索：query 向量化 → 与全部已归一化向量做点积。
     * 返回 id→score 映射（已按阈值过滤、按分数降序截断）；引擎不可用返回 null（回退纯关键词）。
     */
    suspend fun search(query: String): Map<String, Float>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return engineMutex.withLock {
            if (engine == null || storeItems.isEmpty()) return@withLock null
            runCatching {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val qv = embed(q)
                val hits = ArrayList<SearchHit>(storeItems.size)
                for ((id, item) in storeItems) {
                    if (item.embedding.size != qv.size) continue
                    var dot = 0f
                    for (i in qv.indices) dot += qv[i] * item.embedding[i]
                    if (dot >= MIN_SCORE) hits.add(SearchHit(id, dot))
                }
                hits.sortByDescending { it.score }
                val top = hits.take(MAX_SEMANTIC_HITS)
                Log.i(TAG, "search '$q' (${android.os.SystemClock.elapsedRealtime() - t0} ms) -> " +
                    top.joinToString { "${it.id}:${"%.3f".format(it.score)}" })
                top.associate { it.id to it.score }
            }.onFailure { Log.w(TAG, "search failed", it) }.getOrNull()
        }
    }

    private suspend fun initEngine(dir: File) {
        val e = SentenceEmbedding()
        e.init(
            modelFilepath = File(dir, MODEL_FILE).absolutePath,
            tokenizerBytes = File(dir, TOKENIZER_FILE).readBytes(),
            useTokenTypeIds = true,
            outputTensorName = "last_hidden_state",
            normalizeEmbeddings = true,
        )
        engine?.close()
        engine = e
    }

    /** 单条向量化；调用方需持有 engineMutex。 */
    private suspend fun embed(text: String): FloatArray {
        val e = engine ?: error("embedding engine not ready")
        val clipped = if (text.length > MAX_TEXT_CHARS) text.substring(0, MAX_TEXT_CHARS) else text
        return e.encode(clipped)
    }

    private fun loadStoreCache(context: Context) {
        val stored = JsonEmbeddingStore.default(context).load()?.takeIf { it.model == MODEL_NAME }
        if (stored != null) {
            storeItems = stored.items.associateBy { it.id }
            dimensions = stored.dimensions
        } else {
            storeItems = emptyMap()
        }
    }

    private fun buildSearchableText(t: IndexableToggle): String =
        listOfNotNull(
            t.title.takeIf { it.isNotBlank() },
            t.categoryTitles.joinToString(" ").takeIf { it.isNotBlank() },
            t.description?.takeIf { it.isNotBlank() },
            t.aliases.joinToString(" ").takeIf { it.isNotBlank() },
        ).joinToString(" ")

    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
