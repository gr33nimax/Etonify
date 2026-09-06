package io.hydrabox.platform.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Keeps stored subscriptions current in the background.
 *
 * A subscription is a lease: the provider rotates servers and moves the expiry, and a
 * document that is a week old describes servers that may no longer answer. 1.x refreshed on a
 * schedule (`SubscriptionRefreshScheduler`); the alpha refreshed only when someone pressed
 * the button, so the first symptom of a stale document was a tunnel that would not come up.
 *
 * `JobScheduler` rather than a library: the platform already has it, it survives reboots when
 * asked to, and it will not run without a network.
 */
class SubscriptionRefreshJob : JobService() {
    private val io = Executors.newSingleThreadExecutor()
    private val jobLock = Any()
    private var work: Future<*>? = null
    private var cancellation: SubscriptionFetcher.Cancellation? = null
    private var gate: RefreshGate? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val request = SubscriptionFetcher.Cancellation()
        val run = RefreshGate()
        synchronized(jobLock) {
            cancellation = request
            gate = run
        }
        val submitted = io.submit {
            var store: AppStore? = null
            var failed = false
            try {
                val opened = AppStore(applicationContext)
                store = opened
                val sources = opened.refreshableSources()
                HydraLog.info(AREA, "background refresh of ${sources.size} due sources")
                for (record in sources) {
                    if (!run.active() || request.isCancelled()) break
                    runCatching { opened.refreshSubscription(record.id, request) }
                        .onFailure { failure ->
                            if (request.isCancelled()) return@onFailure
                            failed = failed || failure !is IllegalStateException
                            HydraLog.warn(AREA, "a source could not be refreshed", failure)
                        }
                    if (!run.active() || request.isCancelled()) break
                }
            } catch (failure: Exception) {
                if (!request.isCancelled()) {
                    failed = true
                    HydraLog.warn(AREA, "background refresh failed", failure)
                }
            } finally {
                if (run.claimFinish()) synchronized(jobLock) {
                    if (cancellation === request && !request.isCancelled()) {
                        jobFinished(params, failed)
                        if (!failed) runCatching { schedule(applicationContext) }
                            .onFailure { HydraLog.warn(AREA, "next refresh could not be scheduled", it) }
                    }
                }
                synchronized(jobLock) {
                    if (cancellation === request) cancellation = null
                    if (gate === run) { gate = null; work = null }
                }
                runCatching { store?.close() }
            }
        }
        synchronized(jobLock) { work = submitted }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        cancelWork()
        return true
    }

    override fun onDestroy() {
        cancelWork()
        io.shutdown()
        super.onDestroy()
    }

    private fun cancelWork() = synchronized(jobLock) {
        gate?.stop()
        cancellation?.cancel()
        work?.cancel(true)
    }

    companion object {
        private const val AREA = "refresh"
        private const val JOB_ID = 0x48425852
        private const val MIN_DELAY_MILLIS = 15L * 60 * 1000

        /**
         * The scheduler wakes near the next source's advertised interval. Periodic jobs cannot
         * honour an interval shorter than their fixed period, and waking every fifteen minutes
         * regardless of due sources wastes radio time.
         */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val store = AppStore(context.applicationContext)
            val due = try { store.nextRefreshAtMillis() } finally { store.close() }
            if (due == null) {
                scheduler.cancel(JOB_ID)
                return
            }
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SubscriptionRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency((due - System.currentTimeMillis()).coerceAtLeast(MIN_DELAY_MILLIS))
                .setPersisted(true)
                .build()
            val outcome = runCatching { scheduler.schedule(job) }.getOrDefault(JobScheduler.RESULT_FAILURE)
            HydraLog.info(
                AREA,
                if (outcome == JobScheduler.RESULT_SUCCESS) "background refresh scheduled" else "background refresh was refused",
            )
        }
    }
}

/** The one-shot gate shared by the source loop and JobService's stop callback. */
internal class RefreshGate {
    private val lock = Any()
    private var stopped = false
    private var finished = false

    fun stop() = synchronized(lock) { stopped = true }

    fun active(): Boolean = synchronized(lock) { !stopped }

    fun claimFinish(): Boolean = synchronized(lock) {
        if (stopped || finished) false else { finished = true; true }
    }
}
