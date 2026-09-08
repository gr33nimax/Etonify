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
     *
     * A repeated enable is a no-op by design: the service calls this on every runtime
     * snapshot change, and each call used to reset the retry budget and open another
     * client while a retry was still pending — one refusal turned into a stream's worth
     * of attempts. The budget is reset only by a real off→on and by a connection that
     * actually succeeded.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            val openNow: Boolean?
            synchronized(lock) {
                if (wanted) {
                    openNow = null
                } else {
                    wanted = true
                    attempts.set(0)
                    openNow = client == null
                }
            }
            if (openNow == true) openStream()
            return open
        }
        wanted = false
        val closed = synchronized(lock) {
            token = null
            val existing = client
            client = null
            existing
        }
        runCatching { closed?.disconnect() }
        return false
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
        runCatching { created.connect() }.fold(
            onSuccess = {
                // The stream may have been disabled, or replaced by its own goodbye,
                // while the connect was in flight: a client nobody wants any more is
                // released here rather than left running behind the wrapper's back.
                val keep = synchronized(lock) {
                    val active = wanted && this.token === token
                    if (!active && this.token === token) {
                        client = null
                        this.token = null
                    }
                    active
                }
                if (keep) {
                    // The budget is per outage, not for the life of the stream: a tunnel that
                    // survives more outages than the retry count used to be left without core
                    // lines forever, one reconnect too many later.
                    attempts.set(0)
                } else {
                    runCatching { created.disconnect() }
                }
            },
            onFailure = { failure ->
                val wasCurrent = forget(token) != null
                runCatching { created.disconnect() }
                if (wasCurrent) scheduleAttach(failure.message ?: "connect refused")
            },
        )
    }

    private fun handlerFor(token: Any) = object : CommandClientHandler by base {
        override fun disconnected(message: String?) {
            // Through the token: a late goodbye from a stream that was already replaced
            // must not take the new one down with it, and must not spend its retry
            // budget either — only a goodbye from the live client reconnects.
            val lost = forget(token)
            if (lost != null) {
                runCatching { lost.disconnect() }
                scheduleAttach(message ?: "log stream closed")
            }
        }
    }

    /** Releases the client for [forgotten] and returns it, or null when it was already gone. */
    private fun forget(forgotten: Any): Client? =
        synchronized(lock) {
            if (token === forgotten) {
                val existing = client
                client = null
                token = null
                existing
            } else {
                null
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
