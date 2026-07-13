package com.modspec.agent

import android.content.Context
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import java.io.File

/**
 * Copies blobs into the module shared data directory via [XposedService.openRemoteFile].
 */
object RemoteBlobManager {
    fun deploy(context: Context, remotePath: String, source: String) {
        val service = ModspecApp.xposedService
            ?: error("XposedService not bound")
        require(!remotePath.contains('/') && !remotePath.contains("..")) {
            "remote blob path must be a simple filename"
        }

        val bytes = readSourceBytes(context, source)
        service.openRemoteFile(remotePath).use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                out.write(bytes)
            }
        }
    }

    private fun readSourceBytes(context: Context, source: String): ByteArray = when {
        source.startsWith("assets://") -> {
            val assetPath = source.removePrefix("assets://")
            context.assets.open(assetPath).use { it.readBytes() }
        }
        source.startsWith("file://") -> File(source.removePrefix("file://")).readBytes()
        else -> File(source).readBytes()
    }
}
