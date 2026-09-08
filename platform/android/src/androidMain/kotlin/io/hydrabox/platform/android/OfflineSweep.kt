package io.hydrabox.platform.android

import io.hydrabox.core.contract.OutboundLatency

/**
 * One offline measurement pass: the cheap workerless edge questions first, the sequential
 * standalone HTTP sessions after, each published as its own answer.
 *
 * The rules live here, away from the service, so they are testable without a core, the
 * platform or a device. The pass ends the moment its epoch moves or its sweep is
 * cancelled — a stop, a revoke, or a handover — and nothing it measured is published
 * unless the network it was measured on is still the network the device is on: results
 * collected across a handover are a comparison that only holds within one network.
 */
internal class OfflineSweep(
    private val isCancelled: () -> Boolean,
    private val networkStillCurrent: () -> Boolean,
    private val measureEdge: (String) -> OutboundLatency?,
    private val measureHttp: (String) -> OutboundLatency,
    private val publishEdge: (List<OutboundLatency>) -> Unit,
    private val publishHttp: (List<OutboundLatency>) -> Unit,
    private val reportStopped: (Int) -> Unit = {},
) {
    data class Target(val id: String, val type: String?)

    fun run(targets: List<Target>) {
        // The cheap questions go first, as their own pass: each costs a bounded couple of
        // seconds, while every HTTP session ahead of it builds a whole core instance — and
        // the sessions used to spend the edge pass's entire budget before its turn came,
        // leaving the call rows nothing to show for the press that asked for them.
        val edgeAnswers = mutableListOf<OutboundLatency>()
        for (target in targets) {
            if (isCancelled()) break
            if (!target.type.equals("call", ignoreCase = true)) continue
            measureEdge(target.id)?.let { edgeAnswers += it }
        }
        if (edgeAnswers.isNotEmpty() && !isCancelled() && networkStillCurrent()) {
            publishEdge(edgeAnswers)
        }
        // ponytail: sessions own a full core instance; parallelize only if sequential sweeps
        // become slower than the flood-control and memory cost of concurrent call transports.
        val answers = mutableListOf<OutboundLatency>()
        for (target in targets) {
            if (target.type.equals("call", ignoreCase = true)) continue
            if (isCancelled()) {
                reportStopped(answers.size)
                break
            }
            answers += measureHttp(target.id)
        }
        if (answers.isEmpty() || isCancelled()) return
        // A handover under the pass invalidates its answers with everything else: they
        // were asked of a network the device is no longer on, and publishing them under
        // generation zero would keep them past the session that measured them.
        if (!networkStillCurrent()) return
        publishHttp(answers)
    }
}
