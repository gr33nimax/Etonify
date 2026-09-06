package io.hydrabox.platform.android

import android.util.Log
import io.hydrabox.core.diagnostics.redactSecrets

/**
 * The application journal.
 *
 * Two things depend on this existing at all. A person who cannot add a subscription has to
 * be able to say what happened, and we have to be able to read it back from `logcat` with
 * one tag; the alpha had neither, so every failure arrived as "it did not work". The buffer
 * is per-process and bounded: the interface process shows its own entries on the diagnostics
 * screen, and the core process writes its lines where `adb logcat -s HydraBox` finds them.
 *
 * Nothing secret is written here. Call sites pass hosts, sizes, formats and failure names —
 * never a subscription body, a key, a passphrase or a server credential.
 */
object HydraLog {
    const val TAG = "HydraBox"

    /** The area core tracing arrives under; [core] is the only writer of it. */
    const val CORE_AREA = "core"
    private const val CAPACITY = 256

    private const val CORE_PANIC = 0
    private const val CORE_FATAL = 1
    private const val CORE_ERROR = 2
    private const val CORE_WARN = 3
    private const val CORE_NOTICE = 4
    private const val CORE_INFO = 5

    data class Entry(val atMillis: Long, val level: Level, val area: String, val message: String)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * The core's own level for a line, as the level this journal uses.
     *
     * The core numbers them panic, fatal, error, warn, notice, info, debug, trace — and hands the
     * number over with every line. It used to be thrown away and guessed again from the text of
     * the message, which classified as debug anything whose wording it did not recognise.
     */
    fun levelOf(coreLevel: Int): Level = when (coreLevel) {
        CORE_PANIC, CORE_FATAL, CORE_ERROR -> Level.ERROR
        CORE_WARN -> Level.WARN
        CORE_NOTICE, CORE_INFO -> Level.INFO
        else -> Level.DEBUG
    }

    private val entries = ArrayDeque<Entry>(CAPACITY)

    /**
     * Where a line goes when it has to outlive this process or be read from another one. The
     * core runs in `:core` and the journal is drawn in the interface process, so the core sets
     * this to a sink that batches lines into the shared database.
     *
     * Every level is offered. Which ones are worth a write is the sink's decision, because
     * only the sink knows what it costs: the core's own log level already decides how much
     * arrives here at all, and a journal that silently drops everything below a warning is a
     * journal that cannot explain a successful-looking start.
     */
    @Volatile var sink: ((Entry) -> Unit)? = null

    fun debug(area: String, message: String) = record(Level.DEBUG, area, message, null)

    fun info(area: String, message: String) = record(Level.INFO, area, message, null)

    fun warn(area: String, message: String, error: Throwable? = null) = record(Level.WARN, area, message, error)

    fun error(area: String, message: String, error: Throwable? = null) = record(Level.ERROR, area, message, error)

    /** A line the core itself emitted, with the level the core gave it. */
    fun core(level: Level, message: String) = record(level, CORE_AREA, message, null)

    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    /** The journal as text, newest last, for the share sheet. */
    fun asText(): String = entries().joinToString(separator = "\n") { entry ->
        "${entry.atMillis} ${entry.level.name.lowercase()} ${entry.area}: ${entry.message}"
    }

    private fun record(level: Level, area: String, message: String, error: Throwable?) {
        // Redacted here rather than at every call site. The lines that carry a credential are the
        // ones nobody expected to: a subscription address whose path is the token, and a failure
        // message the other side of the language boundary wrote. Both reach `logcat`, the
        // diagnostics screen and the exported report, and export-time redaction is too late —
        // `logcat` already has it.
        val text = redactSecrets(if (error == null) message else "$message: ${describe(error)}")
        val entry = Entry(System.currentTimeMillis(), level, area, text)
        // Core tracing goes to the journal and nowhere else.
        //
        // It used to go to `logcat` as well, which at debug level was 266 lines a second and
        // 82% of everything the device logged — the app's own operational lines were pushed out
        // of the ring buffer by its own tracing, and so was every other app's. It also used to
        // enter the in-memory buffer of the `:core` process, where nothing reads it: the copy the
        // interface draws comes from the database. So `adb logcat -s HydraBox` now shows what
        // happened rather than every packet, and the buffer stays usable for everyone.
        if (area == CORE_AREA) {
            runCatching { sink?.invoke(entry) }
            return
        }
        when (level) {
            Level.DEBUG -> Log.d(TAG, "[$area] $text")
            Level.INFO -> Log.i(TAG, "[$area] $text")
            Level.WARN -> Log.w(TAG, "[$area] $text")
            Level.ERROR -> Log.e(TAG, "[$area] $text")
        }
        synchronized(this) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(entry)
        }
        runCatching { sink?.invoke(entry) }
    }

    /**
     * A throwable as one line that still names the cause. `Exception.message` alone is
     * routinely empty for failures crossing the Go boundary, and the class name alone says
     * nothing, so both travel, and so does the cause chain.
     */
    fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            if (depth > 0) append(" <- ")
            append(current::class.java.simpleName)
            current.message?.takeIf(String::isNotBlank)?.let { append('(').append(it.trim()).append(')') }
            current = current.cause?.takeIf { it !== current }
            depth += 1
        }
    }
}
