package com.modspec.agent.search

import android.content.Context
import android.util.Log
import com.ml.shubham0204.sentence_embeddings.SentenceEmbedding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
 * 端侧语义搜索：Xenova/paraphrase-multilingual-MiniLM-L12-v2（int8 量化 ONNX，384 维，
 * 中英双语 50+ 语言，XLM-Roberta Unigram tokenizer）。
 * - 模型文件运行时下载到 filesDir/embeddings/model/（不进 APK），下载后完全离线。
 * - 引擎：shubham0204/Sentence-Embeddings-Android（ONNX Runtime + Rust HF tokenizer），
 *   last_hidden_state → attention-mask 加权 mean pooling → L2 归一化。
 * - 向量缓存 JSON 增量更新：text_hash 未变不重复 embed。
 */
object SemanticSearchManager {

    private const val TAG = "SemanticSearch"
    const val MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2-int8"
    private const val HF_REPO = "Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main"
    private const val MODEL_FILE = "model_quantized.onnx"

    // HF 主站优先，失败自动回退国内镜像 hf-mirror.com
    private val HOSTS = listOf("https://huggingface.co", "https://hf-mirror.com")

    /** 远端文件清单：repo 内相对路径 / 字节数 / sha256（下载后强校验）。 */
    private class RemoteFile(val remotePath: String, val localName: String, val size: Long, val sha256: String)

    private val FILES = listOf(
        RemoteFile("config.json", "config.json", 673L, "05b570bff786faa5c4604152aa16f19f77ed6dfc31e47dd0f3dd987078693ac7"),
        RemoteFile("tokenizer_config.json", "tokenizer_config.json", 496L, "3f5961b9ac86288cccdb97f32fb848d6187c78e1603958c53f3ea1f296b7d8a2"),
        RemoteFile("special_tokens_map.json", "special_tokens_map.json", 280L, "06e405a36dfe4b9604f484f6a1e619af1a7f7d09e34a8555eb0b77b66318067f"),
        RemoteFile("tokenizer.json", "tokenizer.json", 17_082_913L, "b60b6b43406a48bf3638526314f3d232d97058bc93472ff2de930d43686fa441"),
        // 大文件放最后，保证进度条前段快速走完小文件
        RemoteFile("onnx/model_quantized.onnx", MODEL_FILE, 118_308_126L, "66fc00f5f29afcaff34092e1bdd20008ca3918265a82fb9695a551e510cc4ebc"),
    )
    private val TOTAL_BYTES = FILES.sumOf { it.size }

    // tokenizer.json 已配置 truncation(max_length=128)，Rust 分词器会自动截断；
    // 这里再做一层字符级截断，避免极端超长文本浪费 token 计算量
    private const val MAX_TEXT_CHARS = 400
    // 低于该余弦分数视为噪声丢弃；实测校准：真命中 ≥0.44，噪声底 ≤0.33
    private const val MIN_SCORE = 0.40f
    // 语义补充结果最多返回条数
    private const val MAX_SEMANTIC_HITS = 6

    sealed interface Status {
        data object Checking : Status

        /** 模型未下载；sizeMb 为预计下载量。 */
        data class NeedsDownload(val sizeMb: Int) : Status

        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : Status

        /** 下载或初始化失败；保留关键词搜索兜底。 */
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

    /** 启动时调用：模型已存在则直接初始化引擎并载入缓存索引。 */
    fun ensureReady(context: Context) {
        scope.launch {
            engineMutex.withLock {
                if (_state.value is Status.Ready || _state.value is Status.Downloading) return@withLock
                _state.value = Status.Checking
                val dir = modelDir(context)
                if (!FILES.all { isPlausible(File(dir, it.localName)) }) {
                    _state.value = Status.NeedsDownload(sizeMb = totalSizeMb())
                    return@withLock
                }
                try {
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

    /** 用户点击下载按钮触发；完成后自动初始化。支持断点续传与双源重试。 */
    fun downloadModel(context: Context) {
        scope.launch {
            engineMutex.withLock {
                if (_state.value is Status.Ready || _state.value is Status.Downloading) return@withLock
                val dir = modelDir(context)
                dir.mkdirs()
                _state.value = Status.Downloading(0, TOTAL_BYTES)
                try {
                    var doneBefore = 0L
                    for (f in FILES) {
                        downloadTo(dir, f) { done, _ ->
                            _state.value = Status.Downloading(doneBefore + done, TOTAL_BYTES)
                        }
                        doneBefore += f.size
                        _state.value = Status.Downloading(doneBefore, TOTAL_BYTES)
                    }
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    initEngine(dir)
                    loadStoreCache(context)
                    _state.value = Status.Ready(indexing = false)
                    Log.i(TAG, "model downloaded & engine ready, init=${android.os.SystemClock.elapsedRealtime() - t0} ms")
                } catch (e: Exception) {
                    Log.w(TAG, "download/init failed", e)
                    _state.value = Status.Failed(
                        when (e) {
                            is IOException -> "模型下载失败：${e.message}（请检查网络后重试）"
                            else -> "语义引擎初始化失败：${e.message}"
                        },
                    )
                }
            }
        }
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
            tokenizerBytes = File(dir, "tokenizer.json").readBytes(),
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

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun totalSizeMb(): Int = ((TOTAL_BYTES + (1 shl 20) - 1) / (1 shl 20)).toInt()

    /** 文件存在且大小接近上游（±2%），防半截文件；精确完整性由下载时的 sha256 保证。 */
    private fun isPlausible(file: File): Boolean {
        if (!file.exists()) return false
        val expected = FILES.firstOrNull { it.localName == file.name }?.size ?: return file.length() > 0
        return kotlin.math.abs(file.length() - expected) <= expected / 50
    }

    private fun downloadTo(dir: File, f: RemoteFile, onProgress: ((Long, Long) -> Unit)? = null) {
        val dest = File(dir, f.localName)
        if (dest.exists() && dest.length() == f.size && sha256Hex(dest) == f.sha256) {
            onProgress?.invoke(f.size, f.size)
            return
        }
        var lastError: Exception? = null
        for (host in HOSTS) {
            try {
                fetch("$host/$HF_REPO/${f.remotePath}", dir, f, onProgress)
                return
            } catch (e: Exception) {
                Log.w(TAG, "download ${f.localName} from $host failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IOException("all mirrors failed")
    }

    /** HTTP 下载到 .part 临时文件，支持 Range 断点续传；完成后校验大小与 sha256 再原子落盘。 */
    private fun fetch(url: String, dir: File, f: RemoteFile, onProgress: ((Long, Long) -> Unit)?) {
        val tmp = File(dir, "${f.localName}.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            var offset = if (tmp.exists() && tmp.length() < f.size) tmp.length() else 0L
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val code = conn.responseCode
            when {
                code == 206 -> Unit // 从断点继续
                code in 200..299 -> offset = 0 // 服务端不支持 Range，从头下
                else -> throw IOException("HTTP $code")
            }
            if (offset == 0L && tmp.exists()) tmp.delete()
            val total = offset + (conn.contentLengthLong.takeIf { it > 0 } ?: (f.size - offset))
            FileOutputStream(tmp, offset > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var copied = offset
                    var lastReport = copied
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        // 每 ~256KB 上报一次进度，避免过度刷新 UI
                        if (onProgress != null && copied - lastReport >= 256 * 1024) {
                            lastReport = copied
                            onProgress(copied, total)
                        }
                    }
                    onProgress?.invoke(copied, total)
                }
            }
            if (tmp.length() != f.size) {
                throw IOException("size mismatch: got ${tmp.length()}, want ${f.size}")
            }
            val actualSha = sha256Hex(tmp)
            if (actualSha != f.sha256) {
                tmp.delete()
                throw IOException("sha256 mismatch for ${f.localName}")
            }
            val dest = File(dir, f.localName)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }
}
