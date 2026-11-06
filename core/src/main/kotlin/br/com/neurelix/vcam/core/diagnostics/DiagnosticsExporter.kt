package br.com.neurelix.vcam.core.diagnostics

import android.os.Build
import br.com.neurelix.vcam.core.model.EngineMetrics
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Gera relatório de texto/ZIP removendo caminhos privados, e-mails e identificadores. */
object DiagnosticsExporter {

    fun sanitize(text: String): String = text
        .replace(Regex("/data/(user/\\d+|data)/[\\w.]+"), "<app-data>")
        .replace(Regex("/storage/emulated/\\d+"), "<storage>")
        .replace(Regex("content://[^\\s,)]+"), "content://<redigido>")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "<email>")
        .replace(Regex("(?i)(android_id|serial)[=: ]+\\S+"), "$1=<redigido>")

    fun buildReport(extra: Map<String, String>, metrics: EngineMetrics): String = buildString {
        appendLine("NEURELIX Virtual Camera — relatório de diagnóstico")
        appendLine("Gerado em: ${java.util.Date()}")
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Build: ${Build.DISPLAY}")
        appendLine()
        appendLine("== Métricas ==")
        appendLine(metrics.toString())
        appendLine()
        extra.forEach { (k, v) -> appendLine("== $k =="); appendLine(v); appendLine() }
        appendLine("== Log (${AppLog.snapshot().size} linhas) ==")
        AppLog.snapshot().forEach { appendLine(it.format()) }
    }.let(::sanitize)

    fun exportText(dir: File, extra: Map<String, String>, metrics: EngineMetrics): File {
        dir.mkdirs()
        val f = File(dir, "neurelix-diagnostico-${System.currentTimeMillis()}.txt")
        f.writeText(buildReport(extra, metrics)); return f
    }

    fun exportZip(dir: File, extra: Map<String, String>, metrics: EngineMetrics): File {
        dir.mkdirs()
        val f = File(dir, "neurelix-diagnostico-${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(f)).use { z ->
            z.putNextEntry(ZipEntry("relatorio.txt")); z.write(buildReport(extra, metrics).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("log.txt"))
            z.write(sanitize(AppLog.snapshot().joinToString("\n") { it.format() }).toByteArray()); z.closeEntry()
        }
        return f
    }
}
