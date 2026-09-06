package io.hydrabox.platform.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deadline is structural, not hopeful: a caller that must answer (a binder thread) waits
 * at most this long, whatever the question is stuck on.
 */
class BoundedCallsTest {
    @Test fun `a question that answers in time is returned`() {
        val calls = BoundedCalls(deadlineMillis = 1_000)
        assertEquals("answer", calls.ask { "answer" })
    }

    @Test fun `a question that misses the deadline is abandoned, not waited for`() {
        val calls = BoundedCalls(deadlineMillis = 100)
        val released = CountDownLatch(1)
        try {
            assertNull(calls.ask { released.await(10, TimeUnit.SECONDS); "late" },
                "a question past the deadline must not be answered with its late result")
        } finally {
            released.countDown()
        }
    }

    @Test fun `a question that throws is a null answer, not a crash of the caller`() {
        val calls = BoundedCalls(deadlineMillis = 1_000)
        assertNull(calls.ask<String> { error("the core refused") })
    }

    @Test fun `a stuck question consumes a slot, not the process`() {
        // Two slots, both taken by questions that never answer: the third caller is refused
        // immediately rather than queued behind them forever.
        val calls = BoundedCalls(deadlineMillis = 150, maxThreads = 2)
        val released = CountDownLatch(1)
        try {
            calls.ask { released.await(10, TimeUnit.SECONDS) }
            calls.ask { released.await(10, TimeUnit.SECONDS) }
            val started = System.nanoTime()
            assertNull(calls.ask { "never runs" }, "no slot was free to ask")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 100, "the refusal was immediate")
        } finally {
            released.countDown()
        }
    }
}
