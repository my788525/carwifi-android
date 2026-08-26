package com.carwifi.app.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 本地转发审计日志：写文件 + 内存环形缓冲，供设置页查看。 */
class AuditLogger(dir: File) {
    private val file = File(dir, "carwifi_audit.log")
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    init {
        runCatching {
            if (file.exists()) {
                file.readLines().takeLast(300).forEach { buf.addLast(it) }
            }
        }
    }

    fun log(line: String) {
        val ts = fmt.format(Date())
        val s = "[$ts] $line"
        buf.addLast(s)
        while (buf.size > 500) buf.removeFirst()
        runCatching {
            file.appendText("$s\n")
            // 长期常驻运行：文件超过 2000 行自动截断，仅保留最近记录，避免无限增长
            if (file.length() > 200_000) {
                val lines = file.readLines()
                if (lines.size > 2000) {
                    file.writeText(lines.takeLast(2000).joinToString("\n") + "\n")
                }
            }
        }
    }

    /** 实时读取文件末尾 n 行（UI 与 CoreService 各自持有实例，但写同一文件）。 */
    fun recent(n: Int = 50): List<String> {
        val lines = runCatching { if (file.exists()) file.readLines() else emptyList() }
            .getOrDefault(emptyList())
        return if (lines.size <= n) lines else lines.subList(lines.size - n, lines.size)
    }
}
