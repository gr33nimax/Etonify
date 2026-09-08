package io.hydrabox.platform.android

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Runs blocking questions with a hard deadline, for the callers that must answer.
 *
 * A binder thread has to write its reply before returning, so a question it asks in turn may
 * not be allowed to outlive the caller's patience: the exit lookup crosses this way, and an
 * unbounded one held "Проверяю адрес" on the screen forever whenever the core did not answer
 * in time - the reply never came and nothing on the client could tell a slow answer from a
 * lost one. Here the deadline is structural: the caller waits at most that long, an answer
 * that misses it is abandoned where it stands, and at most [maxThreads] questions run at
 * once - a stuck one consumes a slot, never the process.
 */
internal class BoundedCalls(
    private val deadlineMillis: Long,
    maxThreads: Int = 2,
) {
    private val executor = ThreadPoolExecutor(
        0,
        maxThreads,
        30,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable -> Thread(runnable, "bounded-call").apply { isDaemon = true } },
    )

    /**
     * Runs [question] and returns its answer, or null when it did not finish within the
     * deadline or no slot was free to even ask it. The question itself is not interrupted -
     * some of what it blocks on cannot be - it is simply no longer waited for.
     */
    fun <T> ask(question: () -> T): T? = ask(deadlineMillis, question)

    /**
     * The same question with this call's own deadline, for the callers whose remaining
     * budget shrinks as a sweep progresses. A deadline that has already passed answers
     * null without asking at all.
     */
    fun <T> ask(deadlineMillis: Long, question: () -> T): T? {
        if (deadlineMillis <= 0) return null
        return try {
            val task = java.util.concurrent.FutureTask(question)
            executor.execute(task)
            runCatching { task.get(deadlineMillis, TimeUnit.MILLISECONDS) }.getOrNull()
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    /** Ends the threads with the service; questions already asked are left to finish. */
    fun shutdown() {
        executor.shutdownNow()
    }
}
