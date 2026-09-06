package io.hydrabox.platform.android

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.InetAddress
import java.util.concurrent.Executor

/**
 * The system resolver, as the core's `local` DNS server.
 *
 * This is not an optimisation. Everything in the generated configuration bootstraps through
 * `dns-local`: the name of the proxy resolver, and the name of every server the subscription
 * describes. Without a platform transport here the core falls back to its own Linux
 * implementation, which reads `/etc/resolv.conf` — a file Android does not have — inside the
 * process that also owns the tunnel. The symptom is a tunnel that comes up and carries
 * nothing, which is the hardest failure to read from the outside.
 *
 * Queries go to the network the system would use if the tunnel were not there, so they never
 * re-enter the tunnel they are needed to establish.
 */
class AndroidLocalResolver(private val monitor: DefaultNetworkMonitor) : LocalDNSTransport {
    private val executor: Executor get() = POOL

    /**
     * From Android 10 the platform answers with a DNS message, which keeps CNAMEs, TTLs and
     * rcodes intact. Below that only a host lookup is available, and the core is told so.
     */
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun exchange(context: ExchangeContext?, message: ByteArray?) {
        context ?: return
        if (message == null) return context.errnoCode(EINVAL)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return context.errnoCode(EINVAL)
        val network = monitor.currentNetwork ?: return context.errnoCode(ENETUNREACH)
        val signal = CancellationSignal()
        val done = java.util.concurrent.CountDownLatch(1)
        // Exactly one answer reaches the core, whoever gets there first: the callback, the
        // deadline, or the core's own cancellation.
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        // Cancellation has to release this thread itself. `DnsResolver` delivers no callback for
        // a query it was told to abandon, so the latch was never counted down and this thread —
        // a core goroutine bound to an OS thread through cgo — was gone for good. Nothing else
        // was ever going to free it: there was no timeout on the wait either.
        context.onCancel {
            signal.cancel()
            answered.set(true)
            done.countDown()
        }
        DnsResolver.getInstance().rawQuery(
            network,
            message,
            DnsResolver.FLAG_EMPTY,
            executor,
            signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    // A non-zero rcode is an answer, not a transport failure: NXDOMAIN has to
                    // reach the core as NXDOMAIN or it retries a name that does not exist.
                    if (answered.compareAndSet(false, true)) {
                        if (rcode == 0) context.rawSuccess(answer) else context.errorCode(rcode)
                    }
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (answered.compareAndSet(false, true)) {
                        HydraLog.warn(AREA, "raw query failed: ${error.message}")
                        context.errnoCode(ENETUNREACH)
                    }
                    done.countDown()
                }
            },
        )
        if (!done.await(QUERY_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            signal.cancel()
            if (answered.compareAndSet(false, true)) {
                HydraLog.warn(AREA, "raw query did not answer within ${QUERY_TIMEOUT_MILLIS}ms")
                context.errnoCode(ETIMEDOUT)
            }
        }
    }

    /**
     * The pre-Android-10 path, where only a host lookup exists. `raw` tells the core which of the
     * two to use, so on anything current this is never called.
     *
     * The lookup itself runs on the pool with a deadline rather than inline: `getAllByName` is
     * blocking with no bound of its own, and the thread it blocks belongs to the core.
     */
    override fun lookup(context: ExchangeContext?, network: String?, domain: String?) {
        context ?: return
        if (domain.isNullOrEmpty()) return context.errnoCode(EINVAL)
        val bound = monitor.currentNetwork ?: return context.errnoCode(ENETUNREACH)
        val wanted = when (network) {
            "ip4" -> 4
            "ip6" -> 6
            else -> 0
        }
        val task = java.util.concurrent.FutureTask { bound.getAllByName(domain.trimEnd('.')) }
        // The core abandoning the query has to reach the lookup, or the pool goes on holding a
        // thread for an answer nobody is waiting for.
        context.onCancel { task.cancel(true) }
        if (!submit(task)) return context.errnoCode(EAGAIN)
        // Four outcomes, and they used to be one. A timeout, a transport failure, a name that does
        // not exist, and a name with no address of the family that was asked for all left through
        // NXDOMAIN — which tells the core the name is gone, so it stops asking, gives up on the
        // other family too, and caches the denial. Only the third of those is actually NXDOMAIN.
        val resolved = try {
            task.get(QUERY_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            HydraLog.warn(AREA, "a name lookup did not answer within " + QUERY_TIMEOUT_MILLIS + "ms")
            return context.errnoCode(ETIMEDOUT)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            return context.errnoCode(ECANCELED)
        } catch (interrupted: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            return context.errnoCode(ECANCELED)
        } catch (failed: java.util.concurrent.ExecutionException) {
            val cause = failed.cause
            // The one real NXDOMAIN: the platform resolver says the name does not exist.
            if (cause is java.net.UnknownHostException) return context.errorCode(RCODE_NAME_ERROR)
            HydraLog.warn(AREA, "a name lookup failed: " + (cause?.message ?: failed.message))
            return context.errnoCode(ENETUNREACH)
        }
        val addresses = resolved
            .orEmpty()
            .filter { address ->
                when (wanted) {
                    4 -> address is java.net.Inet4Address
                    6 -> address is java.net.Inet6Address
                    else -> true
                }
            }
            .mapNotNull(InetAddress::getHostAddress)
        // An empty answer is not a missing name: a host with no AAAA still has an A, and answering
        // NXDOMAIN for the AAAA half denies the whole name. This is the no-data answer instead.
        context.success(addresses.joinToString(separator = "\n"))
    }

    /** True when the pool took the task. A refusal is backpressure, not a name that is missing. */
    private fun submit(task: Runnable): Boolean = runCatching { executor.execute(task) }
        .onFailure { HydraLog.warn(AREA, "the resolver pool is saturated; refusing a query") }
        .isSuccess

    private companion object {
        const val AREA = "dns"

        /**
         * One pool for the process, not one per session.
         *
         * Every `AndroidVpnPlatform` used to build its own — and one of those is created per core
         * start and per standalone probe — each with two core threads and no idle timeout, so every
         * session left two threads behind for the life of the process. This one is shared, its
         * threads retire when idle, and its queue is bounded: work it cannot take is refused rather
         * than accumulated, because a query nobody can answer for minutes is not worth the memory
         * of remembering it.
         */
        val POOL: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
            1,
            16,
            30,
            java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(512),
            { runnable -> Thread(runnable, "hydra-dns").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

        /** `errno` values, which is the only vocabulary the core's callback accepts here. */
        const val EINVAL = 22
        const val ENETUNREACH = 101
        const val ETIMEDOUT = 110

        /**
         * How long one query may hold a core thread. Above the platform resolver's own budget, so
         * an ordinary slow answer still arrives; the point is that a query which never answers at
         * all cannot keep the thread.
         */
        const val QUERY_TIMEOUT_MILLIS = 10_000L

        const val EAGAIN = 11
        const val ECANCELED = 125

        /** NXDOMAIN. */
        const val RCODE_NAME_ERROR = 3
    }
}
