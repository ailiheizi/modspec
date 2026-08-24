package com.modspec.agent.search

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条已索引向量（embedding 已 L2 归一化，点积即余弦）。 */
class IndexedItem(
    val id: String,
    val textHash: String,
    val embedding: FloatArray,
)

/** 全量索引快照。 */
class StoredIndex(
    val model: String,
    val dimensions: Int,
    val items: List<IndexedItem>,
)

/**
 * 向量存储抽象（SEMANTIC_SEARCH_DESIGN §2.3）。
 * 当前量级（≤ 数百条）JSON + 暴力余弦即可；未来规模上来可换实现，上层无感。
 */
interface EmbeddingStore {
    fun load(): StoredIndex?
    fun save(index: StoredIndex)
}

/** JSON 文件实现：filesDir/embeddings/store.json */
class JsonEmbeddingStore(private val file: File) : EmbeddingStore {

    override fun load(): StoredIndex? {
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val model = root.optString("model")
            val dims = root.optInt("dimensions", 0)
            if (dims <= 0 || model.isBlank()) return@runCatching null
            val items = root.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.optJSONObject(i) ?: return@mapNotNull null
                    val emb = item.optJSONArray("embedding") ?: return@mapNotNull null
                    IndexedItem(
                        id = item.getString("id"),
                        textHash = item.optString("text_hash"),
                        embedding = FloatArray(emb.length()) { emb.getDouble(it).toFloat() },
                    )
                }
            }.orEmpty()
            if (items.isEmpty()) null else StoredIndex(model, dims, items)
        }.getOrNull()
    }

    override fun save(index: StoredIndex) {
        file.parentFile?.mkdirs()
        val items = JSONArray()
        index.items.forEach { item ->
            val emb = JSONArray()
            item.embedding.forEach { emb.put(it.toDouble()) }
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text_hash", item.textHash)
                    .put("embedding", emb),
            )
        }
        val root = JSONObject()
            .put("model", index.model)
            .put("dimensions", index.dimensions)
            .put("items", items)
        // 先写临时文件再原子替换，避免写一半崩溃留下损坏缓存
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    companion object {
        fun default(context: Context): JsonEmbeddingStore =
            JsonEmbeddingStore(File(File(context.filesDir, "embeddings"), "store.json"))
    }
}
