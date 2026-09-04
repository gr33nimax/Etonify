package io.hydrabox.platform.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors

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

    override fun onStartJob(params: JobParameters?): Boolean {
        io.execute {
            val store = AppStore(applicationContext)
            val sources = runCatching { store.records() }.getOrDefault(emptyList())
            HydraLog.info(AREA, "background refresh of ${sources.size} sources")
            var failed = false
            sources.forEach { record ->
                runCatching { store.refreshSubscription(record.id) }
                    .onFailure { failure ->
                        // A source without a URL is not a failure to retry: it was pasted in.
                        val retryable = failure !is IllegalStateException
                        failed = failed || retryable
                        HydraLog.warn(AREA, "a source could not be refreshed", failure)
                    }
            }
            // Rescheduling on failure is the platform's job, and it backs off on its own.
            jobFinished(params, failed)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val AREA = "refresh"
        private const val JOB_ID = 0x48425852
        private const val INTERVAL_MILLIS = 6L * 60 * 60 * 1000

        /**
         * Idempotent: the same job id replaces the previous schedule, so calling this on every
         * start neither multiplies jobs nor resets the interval of an already-running one.
         */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SubscriptionRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MILLIS)
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
