package io.hydrabox.platform.android

import io.nekohasekai.libbox.CommandClientHandler
import java.util.concurrent.atomic.AtomicInteger

/**
 * The log stream's lifecycle: opened on demand, closed on demand, and reconnected on its own
 * when it is lost.
 *
 * The runtime stream always recovered from losing the core; this one used to leave a dead
 * client behind that blocked every later enable until the process ended. It stands apart
 * from [CoreObserver] so the rules can be tested without a core: the factory decides what a
 * client is, the scheduler decides when a retry runs, and both are injected.
 */
internal class LogStream(
    private val base: CommandClientHandler,
    private val newClient: (handler: CommandClientHandler) -> Client?,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Unit,
    private val report: (message: String, severe: Boolean) -> Unit = { _, _ -> },
    private val maxAttempts: Int = 5,
    private val backoffMillis: Long = 500,
) {
    /** The client, as this wrapper owns it: the real one is a gomobile class, a test's is not. */
    interface Client {
        fun connect()
        fun disconnect()
    }

    private val lock = Any()
    private var client: Client? = null
    private var token: Any? = null

    @Volatile private var wanted = false
    private val attempts = AtomicInteger(0)

    val open: Boolean get() = synchronized(lock) { client != null }

    /**
     * Turns the stream on or off. Returns the state after the call, so a caller that asks
     * for what is already there can stop believing it caused it.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val openNow: Boolean
        synchronized(lock) {
            wanted = enabled
            attempts.set(0)
            if (!enabled) {
                token = null
                runCatching { client?.disconnect() }
                client = null
                return false
            }
            openNow = client == null
        }
        if (openNow) openStream()
        return open
    }

    private fun openStream() {
        val token = Any()
        val created = synchronized(lock) {
            if (!wanted || client != null) return
            val built = newClient(handlerFor(token))
                ?: return scheduleAttach("the core would not give out a command client")
            client = built
            this.token = token
            built
        }
        runCatching { created.connect() }.onFailure { failure ->
            forget(token)
            runCatching { created.disconnect() }
            scheduleAttach(failure.message ?: "connect refused")
        }
    }

    private fun handlerFor(token: Any) = object : CommandClientHandler by base {
        override fun disconnected(message: String?) {
            // Through the token: a late goodbye from a stream that was already replaced
            // must not take the new one down with it.
            forget(token)
            scheduleAttach(message ?: "log stream closed")
        }
    }

    private fun forget(forgotten: Any) {
        synchronized(lock) {
            if (token === forgotten) {
                client = null
                token = null
            }
        }
    }

    private fun scheduleAttach(reason: String) {
        if (!wanted) return
        val attempt = attempts.incrementAndGet()
        if (attempt > maxAttempts) {
            report("the core's log stream is unreachable after $attempt attempts: $reason", true)
            return
        }
        val delay = backoffMillis shl (attempt - 1).coerceAtMost(4)
        report("the core's log stream would not open ($reason); trying again in ${delay}ms", false)
        schedule(delay) { openStream() }
    }

    fun close() {
        setEnabled(false)
    }
}
