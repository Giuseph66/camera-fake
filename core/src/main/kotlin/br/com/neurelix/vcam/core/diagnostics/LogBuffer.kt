package br.com.neurelix.vcam.core.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class LogLevel(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

data class LogEntry(val timeMs: Long, val level: LogLevel, val channel: String, val tag: String, val message: String) {
    fun format(): String {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timeMs))
        return "$ts ${level.letter}/$channel/$tag: $message"
    }
}

/**
 * Buffer circular de logs do aplicativo, thread-safe, observável.
 * Canais: "app", "engine", "integration", "camera2", "codec", "binder", "privileged".
 */
object AppLog {
    private const val CAPACITY = 2000
    private val lock = Any()
    private val ring = ArrayDeque<LogEntry>(CAPACITY)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    @Volatile private var lastPublish = 0L

    fun log(level: LogLevel, channel: String, tag: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg :: ${t.javaClass.simpleName}: ${t.message}" else msg
        val e = LogEntry(System.currentTimeMillis(), level, channel, tag, full)
        synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(e)
            val now = e.timeMs
            if (level >= LogLevel.WARN || now - lastPublish > 250) {
                lastPublish = now
                _entries.value = ring.toList()
            }
        }
        val prio = when (level) {
            LogLevel.DEBUG -> Log.DEBUG; LogLevel.INFO -> Log.INFO; LogLevel.WARN -> Log.WARN; LogLevel.ERROR -> Log.ERROR
        }
        Log.println(prio, "NVC/$channel", "[$tag] $full")
    }

    fun flush() = synchronized(lock) { _entries.value = ring.toList() }

    fun snapshot(): List<LogEntry> = synchronized(lock) { ring.toList() }
    fun clear() = synchronized(lock) { ring.clear(); _entries.value = emptyList() }

    fun d(channel: String, tag: String, msg: String) = log(LogLevel.DEBUG, channel, tag, msg)
    fun i(channel: String, tag: String, msg: String) = log(LogLevel.INFO, channel, tag, msg)
    fun w(channel: String, tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN, channel, tag, msg, t)
    fun e(channel: String, tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, channel, tag, msg, t)
}
