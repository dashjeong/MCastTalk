package app.guidecast.transmitter

import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded, app-private diagnostics. Never pass audio, transcripts, URLs, PINs or tokens here. */
internal class RotatingDiagnosticFile(
    private val directory: File,
    private val name: String,
    private val maxBytes: Long = 131_072,
) {
    init { require(name.matches(Regex("[a-zA-Z0-9_-]{1,80}"))); require(maxBytes >= 1024) }

    @Synchronized fun append(record: String) {
        directory.mkdirs()
        val file = File(directory, "$name.log")
        val bytes = (record.replace('\n', ' ').replace('\r', ' ')
            .take(minOf(2048, (maxBytes / 4).toInt() - 1)) + "\n").toByteArray()
        if (file.length() + bytes.size > maxBytes) {
            val old = File(directory, "$name.2.log")
            if (old.exists()) check(old.delete())
            val previous = File(directory, "$name.1.log")
            if (previous.exists()) check(previous.renameTo(old))
            if (file.exists()) check(file.renameTo(previous))
        }
        file.appendBytes(bytes)
    }
}

internal object RuntimeDiagnosticLog {
    @Volatile private var sink: RotatingDiagnosticFile? = null
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(128),
        { task -> Thread(task, "guidecast-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    fun initialize(directory: File, process: String) {
        sink = RotatingDiagnosticFile(directory, process.substringAfter(':', "main")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80))
    }

    fun record(event: String, detail: String = "", critical: Boolean = false) {
        val record = "${System.currentTimeMillis()} ${if (critical) "ERROR" else "INFO"} $event $detail"
        val target = sink ?: return
        val action = Runnable { runCatching { target.append(record) } }
        if (critical) action.run() else runCatching { writer.execute(action) }
    }

    fun failure(event: String, error: Throwable) {
        // Exception messages can contain translation input. Persist types/frames, not messages.
        val frames = error.stackTrace.take(10).joinToString(";") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        record(event, "type=${error.javaClass.name} cause=${error.cause?.javaClass?.name} frames=$frames", true)
    }

    fun export(directory: File, output: OutputStream) {
        val root = directory.canonicalFile
        ZipOutputStream(output).use { zip ->
            val buffer = ByteArray(131_072)
            var exported = 0
            directory.listFiles().orEmpty().filter {
                it.name.matches(Regex("[a-zA-Z0-9_-]{1,80}(\\.[12])?\\.log")) &&
                    it.isFile && it.canonicalFile.parentFile == root
            }.sortedBy { it.name }.take(60).forEach { file ->
                // Catch only source disappearance/read errors. Destination failures must reach
                // the operator, not result in a falsely successful or damaged export.
                val count = try {
                    file.inputStream().use { input ->
                        var total = 0
                        while (total < buffer.size) {
                            val read = input.read(buffer, total, buffer.size - total)
                            if (read < 0) break
                            total += read
                        }
                        total
                    }
                } catch (_: IOException) { return@forEach }
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(buffer, 0, count)
                zip.closeEntry()
                exported++
            }
            check(exported > 0) { "저장된 진단 로그를 읽을 수 없습니다." }
        }
    }
}
