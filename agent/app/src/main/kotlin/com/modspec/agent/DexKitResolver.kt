package com.modspec.agent

import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * Resolves obfuscated methods via [DexKitBridge] (HyperCeiler-style).
 */
object DexKitResolver {
    init {
        runCatching { System.loadLibrary("dexkit") }
    }

    fun findMethod(apkPath: String, classLoader: ClassLoader, query: DexQuery): Method? {
        if (apkPath.isBlank()) return null
        return runCatching {
            DexKitBridge.create(apkPath).use { bridge ->
                val methods = bridge.findMethod {
                    matcher {
                        if (!query.className.isNullOrBlank()) {
                            declaredClass(query.className!!)
                        }
                        if (!query.methodName.isNullOrBlank()) {
                            name(query.methodName!!)
                        }
                    }
                }
                when {
                    query.unique && methods.size != 1 -> null
                    methods.isEmpty() -> null
                    else -> methods.first().getMethodInstance(classLoader)
                }
            }
        }.getOrNull()
    }
}
