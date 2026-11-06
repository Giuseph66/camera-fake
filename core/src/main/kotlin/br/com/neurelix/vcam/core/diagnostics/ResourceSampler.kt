package br.com.neurelix.vcam.core.diagnostics

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Amostra CPU (% de um núcleo) e memória do próprio processo, lendo /proc/self/stat. */
class ResourceSampler {
    private var lastTicks = readTicks()
    private var lastTimeMs = SystemClock.elapsedRealtime()
    private val ticksPerSec = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).toDouble()

    data class Sample(val cpuPercent: Float, val memoryMb: Float, val nativeMb: Float)

    fun sample(): Sample {
        val ticks = readTicks()
        val now = SystemClock.elapsedRealtime()
        val dtMs = (now - lastTimeMs).coerceAtLeast(1)
        val cpu = if (ticks >= 0 && lastTicks >= 0) ((ticks - lastTicks) / ticksPerSec) / (dtMs / 1000.0) * 100.0 else 0.0
        lastTicks = ticks; lastTimeMs = now
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return Sample(cpu.toFloat(), mi.totalPss / 1024f, Debug.getNativeHeapAllocatedSize() / 1048576f)
    }

    private fun readTicks(): Long = runCatching {
        val parts = File("/proc/${Process.myPid()}/stat").readText().substringAfterLast(')').trim().split(' ')
        // após ")" o campo 0 é "state"; utime=11, stime=12 (campos 14 e 15 da linha original)
        parts[11].toLong() + parts[12].toLong()
    }.getOrDefault(-1L)
}
