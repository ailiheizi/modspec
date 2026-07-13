package com.modspec.agent

/**
 * 采集 ModSpec 相关 logcat 尾行 — 供 Hook 管家 UI 与 collect_logs RPC。
 */
object LogTailReader {

    private val TAGS = listOf("ModspecModule", "ModspecRuleEngine", "ModspecAgent")

    fun tail(lines: Int = 12): List<String> {
        if (!ShellRunner.canSu()) return emptyList()
        val filter = TAGS.joinToString(" ") { "$it:I" }
        val cmd = "logcat -d -t ${lines * 3} -s $filter 2>/dev/null"
        val raw = ShellRunner.runSu(cmd).getOrNull()?.lineSequence()?.toList().orEmpty()
        return raw.takeLast(lines)
    }
}
