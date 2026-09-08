package io.hydrabox.core.runtime

import io.hydrabox.core.contract.EdgeLatencyStatus
import io.hydrabox.core.contract.FailureDomain
import io.hydrabox.core.contract.HydraCoreErrorCode
import io.hydrabox.core.contract.NetworkGeneration
import io.hydrabox.core.contract.OutboundSelection
import io.hydrabox.core.contract.RuntimeFailure
import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.OutboundLatency
import io.hydrabox.core.contract.TrafficCounters
import io.hydrabox.core.contract.TransportHealth

enum class RuntimeDeadline(val milliseconds: Long) {
    START(45_000),
    CHALLENGE(120_000),
    RECOVERY(60_000),
    CLOSE(5_000),
}

sealed interface RuntimeInput {
    data class Start(val mode: RuntimeMode) : RuntimeInput
    data object Stop : RuntimeInput
    data object Reload : RuntimeInput
    data class SelectOutbound(val selection: OutboundSelection) : RuntimeInput
    data class NetworkChanged(val generation: NetworkGeneration) : RuntimeInput
    data class Launched(val commandGeneration: Long, val runtimeGeneration: Long) : RuntimeInput
    data class Health(
        val commandGeneration: Long,
        val runtimeGeneration: Long,
        val health: TransportHealth,
        val challenge: Boolean = false,
        val shouldRecover: Boolean = false,
        val observedAtElapsedRealtimeMillis: Long = 0,
    ) : RuntimeInput
    data class Deadline(val commandGeneration: Long) : RuntimeInput

    /**
     * The core for this command is not running any more, with the reason when there is one.
     *
     * It is reported for a start that failed as well as for a close that finished: `startCore`
     * cannot leave the runtime in STARTING with the core already released, because nothing else
     * would ever take it out of there — the state used to sit at "connecting" until the
     * forty-five second deadline fired and the person read a refusal as a dead network.
     */
    data class Released(
        val commandGeneration: Long,
        val success: Boolean,
        val failure: RuntimeFailure? = null,
    ) : RuntimeInput
    data object DeviceIdleExit : RuntimeInput
    /** Counters observed from the core; carries no decision, only what to display. */
    data class Traffic(val counters: TrafficCounters) : RuntimeInput

    /**
     * Latencies measured by the core's own group, or by a standalone sweep; display-only.
     *
     * [generation] is the command generation they were measured under, and zero means "measured
     * without a running core". Results from a session that has since been replaced are dropped
     * rather than shown: a delay measured through the previous server is not a delay.
     *
     * What a value says about itself decides which list it merges into: the group answers in
     * its own words and the workerless edge question in [EdgeLatencyStatus], the two
     * vocabularies are disjoint, and a caller that mixes the kinds in one list — as the
     * offline sweep once did — cannot make an edge answer overwrite a server's HTTP figure
     * or the other way round.
     */
    data class Latencies(
        val values: List<OutboundLatency>,
        val generation: Long = 0,
    ) : RuntimeInput

    /** Which outbound the core says it is routing through, per group. Observed, not commanded. */
    data class SelectionObserved(
        val commandGeneration: Long,
        val selection: OutboundSelection,
    ) : RuntimeInput
}

data class RuntimeModel(
    val state: RuntimeState = RuntimeState.STOPPED,
    val commandGeneration: Long = 0,
    val runtimeGeneration: Long = 0,
    val networkGeneration: NetworkGeneration = NetworkGeneration(0),
    val mode: RuntimeMode? = null,
    val wantRunning: Boolean = false,
    val recoveryAttempts: Int = 0,
    val health: TransportHealth = TransportHealth(),
    val selectedOutbounds: List<OutboundSelection> = emptyList(),
    /**
     * What the core reports it is actually routing through, per group, as against what was asked
     * for above. The two disagree more often than they should — a selection restored from the
     * cache file, and an automatic group whose choice is made inside the core — and the screens
     * were showing the request as though it were the answer.
     */
    val observedOutbounds: List<OutboundSelection> = emptyList(),
    val failure: RuntimeFailure? = null,
    val failAfterRelease: Boolean = false,
    val deferredStart: RuntimeMode? = null,
    val traffic: TrafficCounters = TrafficCounters(),
    val latencies: List<OutboundLatency> = emptyList(),
    /** Which command generation the latencies were measured under; zero for a standalone sweep. */
    val latencyGeneration: Long = 0,
    /** The workerless edge round trips, kept apart from the group's HTTP delays. */
    val edgeLatencies: List<OutboundLatency> = emptyList(),
    val connectedAtElapsedRealtimeMillis: Long? = null,
)

sealed interface Effect {
    data class StartCore(val mode: RuntimeMode, val commandGeneration: Long) : Effect
    data class StopCore(val commandGeneration: Long) : Effect
    data class SelectCoreOutbound(val selection: OutboundSelection, val commandGeneration: Long) : Effect

    /**
     * Tell the core which interface the system would use now, and nothing else.
     *
     * This is what a network change means while the tunnel is still coming up: the core has to
     * know where to dial, but there is nothing established to move yet. 1.x calls this
     * `ApplyUnderlying` (`CoreRuntimeService.applyNetworkChangeAction`).
     */
    data class PublishNetwork(val generation: NetworkGeneration) : Effect

    /**
     * Move a running tunnel onto the new network: raise the core's network generation and then
     * publish the interface, in that order. 1.x calls this `ApplyUnderlyingAndRebind`, and the
     * order is the whole point — a lane replaced under a generation the core has not been told
     * about cannot be superseded in sequence, and the transport tears every lane down instead
     * of replacing it.
     */
    data class RebindNetwork(val generation: NetworkGeneration) : Effect
}

sealed interface TimerOp {
    data class Arm(val commandGeneration: Long, val deadline: RuntimeDeadline) : TimerOp
    data class Cancel(val commandGeneration: Long) : TimerOp
}

data class Decision(
    val state: RuntimeModel,
    val effects: List<Effect> = emptyList(),
    val timers: List<TimerOp> = emptyList(),
)

/** Pure state transition: platform code alone executes [Effect] and [TimerOp]. */
fun reduce(state: RuntimeModel, input: RuntimeInput): Decision = when (input) {
    is RuntimeInput.Traffic ->
        if (state.state == RuntimeState.RUNNING) Decision(state.copy(traffic = input.counters)) else Decision(state)
    // Measured under a command that is no longer the current one: the servers may be the same,
    // the route through them is not. A sweep with no core behind it (generation zero) is always
    // current, because it measured each server on its own.
    is RuntimeInput.Latencies ->
        if ((input.generation == 0L && state.state in setOf(RuntimeState.STOPPED, RuntimeState.FAILED)) || (input.generation != 0L && input.generation == state.commandGeneration && state.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING, RuntimeState.RECOVERING))) {
            // Two producers measure different servers: the core's group reports its
            // members, and the workerless edge probe reports the call transports the
            // group does not even carry. Each used to replace the whole list, so
            // whichever answered second wiped the other's figures — an edge round
            // trip appeared and vanished when the group's slower measurement landed,
            // and on a mixed list the regular servers lost their figures to the
            // edge's. Answers merge per server instead: what arrived is newer for
            // the servers it names, and silence about a server is not an instruction
            // to forget its last measurement. The two kinds merge into their own
            // lists — the edge question's answers by their own words — so a server
            // can hold both an HTTP delay and an edge round trip, and neither erases
            // the other. A new session still clears everything, in `start`.
            val (edgeAnswers, groupAnswers) = input.values.partition { it.status in EdgeLatencyStatus.ALL }
            Decision(
                state.copy(
                    latencies = (state.latencies.associateBy(OutboundLatency::tag) +
                        groupAnswers.associateBy(OutboundLatency::tag)).values.toList(),
                    edgeLatencies = (state.edgeLatencies.associateBy(OutboundLatency::tag) +
                        edgeAnswers.associateBy(OutboundLatency::tag)).values.toList(),
                    latencyGeneration = input.generation,
                ),
            )
        } else {
            Decision(state)
        }
    // The core's own answer about which member of a group carries the traffic. It is not the same
    // question as what was asked for: the core restores its own choice from the cache file ahead
    // of the configuration's default, and `auto` decides internally and tells nobody.
    is RuntimeInput.SelectionObserved ->
        if (input.commandGeneration == state.commandGeneration) Decision(
            state.copy(
                // Replaced in place, never re-appended: the core re-announces every group in
                // every group message, and moving a re-announced entry to the end flipped the
                // list's order twice per message — which read as a route change, which re-asked
                // the exit for every flip and discarded every answer as superseded. A
                // re-announcement of the same selection is not a change at all.
                observedOutbounds = if (state.observedOutbounds.any { it.groupId == input.selection.groupId }) {
                    state.observedOutbounds.map { existing ->
                        if (existing.groupId == input.selection.groupId) input.selection else existing
                    }
                } else {
                    state.observedOutbounds + input.selection
                },
            ),
        ) else Decision(state)
    is RuntimeInput.Start -> when (state.state) {
        RuntimeState.STOPPED, RuntimeState.FAILED -> start(state, input.mode)
        RuntimeState.RUNNING -> if (state.mode == input.mode) Decision(state) else stop(state, deferredStart = input.mode)
        RuntimeState.STARTING, RuntimeState.RECOVERING -> if (state.mode == input.mode) Decision(state) else Decision(
            state.copy(mode = input.mode, commandGeneration = state.commandGeneration + 1, runtimeGeneration = 0),
            effects = listOf(Effect.StopCore(state.commandGeneration), Effect.StartCore(input.mode, state.commandGeneration + 1)),
            timers = listOf(TimerOp.Cancel(state.commandGeneration), TimerOp.Arm(state.commandGeneration + 1, RuntimeDeadline.START)),
        )
        RuntimeState.STOPPING -> Decision(state.copy(deferredStart = input.mode))
    }
    RuntimeInput.Stop -> if (state.state == RuntimeState.STOPPED) Decision(state) else stop(state, wantRunning = false)
    // Live reload is not supported by this contract: the core closes the old instance
    // before the new one exists, so a failed reload would leave the model RUNNING over a
    // released core with no way back. The command is refused by name — a typed failure,
    // no effect, no deadline — until a reload that can actually roll back exists; the
    // honest path for a new configuration today is Stop followed by Start.
    RuntimeInput.Reload -> Decision(
        state.copy(
            failure = RuntimeFailure(
                domain = FailureDomain.INTERNAL,
                code = HydraCoreErrorCode.RUNTIME_RELOAD_UNSUPPORTED,
                retryable = true,
            ),
        ),
    )
    is RuntimeInput.SelectOutbound -> if (state.state == RuntimeState.RUNNING) Decision(
        state.copy(selectedOutbounds = state.selectedOutbounds.filterNot { it.groupId == input.selection.groupId } + input.selection),
        effects = listOf(Effect.SelectCoreOutbound(input.selection, state.commandGeneration)),
    ) else Decision(state)
    is RuntimeInput.NetworkChanged -> network(state, input)
    is RuntimeInput.Launched -> if (
        state.state in setOf(RuntimeState.STARTING, RuntimeState.RECOVERING) && input.commandGeneration == state.commandGeneration
    ) Decision(state.copy(runtimeGeneration = input.runtimeGeneration)) else Decision(state)
    is RuntimeInput.Health -> health(state, input)
    is RuntimeInput.Deadline -> when {
        input.commandGeneration != state.commandGeneration -> Decision(state)
        state.state in setOf(RuntimeState.STARTING, RuntimeState.RECOVERING) ->
            stop(
                state,
                failAfterRelease = true,
                wantRunning = false,
                // Carried so the screen can say the tunnel gave up waiting rather than offering
                // "something went wrong" with no code behind it.
                failure = state.failure ?: state.health.failure ?: RuntimeFailure(
                    domain = FailureDomain.INTERNAL,
                    code = HydraCoreErrorCode.RUNTIME_START_DEADLINE,
                    retryable = true,
                ),
            )
        // A close that never reports back must not leave the runtime in STOPPING for good.
        // `stop` arms this deadline, and nothing was answering it: the state machine had no
        // way out of STOPPING except a release that, by definition, was not coming.
        state.state == RuntimeState.STOPPING ->
            released(state, RuntimeInput.Released(input.commandGeneration, success = false, failure = RuntimeFailure(FailureDomain.INTERNAL, HydraCoreErrorCode.RUNTIME_STOP_UNCONFIRMED, retryable = true)))
        else -> Decision(state)
    }
    is RuntimeInput.Released -> released(state, input)
    RuntimeInput.DeviceIdleExit -> if (state.state == RuntimeState.RUNNING && state.wantRunning && state.recoveryAttempts < 2) recover(state) else Decision(state)
}

/**
 * What a network change means, per runtime state. The table is 1.x's
 * (`CoreRuntimeService.reduce` for `NetworkChanged`), including the two rejections: a tunnel
 * that is stopping has nothing to move, and one that is stopped has nowhere to move it.
 *
 * A generation that is not newer than the one already applied is dropped rather than
 * replayed. Duplicate notifications are normal on Android — capabilities and link properties
 * both fire — and passing each one to the core is what turns a single handover into several
 * teardowns.
 */
private fun network(state: RuntimeModel, input: RuntimeInput.NetworkChanged): Decision {
    if (input.generation.value <= state.networkGeneration.value) return Decision(state)
    val moved = state.copy(networkGeneration = input.generation)
    return when (state.state) {
        RuntimeState.RUNNING -> Decision(moved, effects = listOf(Effect.RebindNetwork(input.generation)))
        RuntimeState.STARTING, RuntimeState.RECOVERING ->
            Decision(moved, effects = listOf(Effect.PublishNetwork(input.generation)))
        RuntimeState.STOPPING, RuntimeState.STOPPED, RuntimeState.FAILED -> Decision(state)
    }
}

private fun start(state: RuntimeModel, mode: RuntimeMode): Decision {
    val generation = state.commandGeneration + 1
    return Decision(
        state.copy(state = RuntimeState.STARTING, commandGeneration = generation, runtimeGeneration = 0, mode = mode, wantRunning = true, recoveryAttempts = 0, failure = null, health = TransportHealth(), traffic = TrafficCounters(), latencies = emptyList(), latencyGeneration = 0, edgeLatencies = emptyList(), observedOutbounds = emptyList(), selectedOutbounds = emptyList(), connectedAtElapsedRealtimeMillis = null),
        effects = listOf(Effect.StartCore(mode, generation)),
        timers = listOf(TimerOp.Arm(generation, RuntimeDeadline.START)),
    )
}

private fun stop(
    state: RuntimeModel,
    failAfterRelease: Boolean = false,
    deferredStart: RuntimeMode? = null,
    wantRunning: Boolean = state.wantRunning,
    failure: RuntimeFailure? = null,
): Decision {
    val generation = state.commandGeneration + 1
    return Decision(
        state.copy(state = RuntimeState.STOPPING, commandGeneration = generation, failAfterRelease = failAfterRelease, deferredStart = deferredStart, wantRunning = wantRunning, failure = failure ?: state.failure),
        effects = listOf(Effect.StopCore(generation)),
        // The deadline of the command being superseded is cancelled, not left to fire. It was
        // left: after a start that failed fast, the forty-five second START deadline of the dead
        // generation still went off and wrote `start deadline expired` into the journal — ignored
        // by the reducer on the generation check, but read by a person as a second failure.
        timers = listOf(TimerOp.Cancel(state.commandGeneration), TimerOp.Arm(generation, RuntimeDeadline.CLOSE)),
    )
}

private fun recover(state: RuntimeModel): Decision {
    val generation = state.commandGeneration + 1
    val mode = requireNotNull(state.mode)
    return Decision(
        state.copy(state = RuntimeState.RECOVERING, commandGeneration = generation, runtimeGeneration = 0, recoveryAttempts = state.recoveryAttempts + 1, connectedAtElapsedRealtimeMillis = null),
        effects = listOf(Effect.StartCore(mode, generation)),
        timers = listOf(TimerOp.Cancel(state.commandGeneration), TimerOp.Arm(generation, RuntimeDeadline.RECOVERY)),
    )
}

/**
 * The core for a command has let go of the tunnel.
 *
 * It is accepted while the runtime is still coming up as well as while it is closing. A start that
 * throws releases the core and reports it, and this used to be dropped on the state check: the
 * runtime stayed in STARTING with nothing running, until the start deadline fired forty-odd
 * seconds later and wrote a second, unrelated-looking failure into the journal. The reason travels
 * with it now — from the platform, from the transport's own health, or from whatever put this stop
 * in motion — so a FAILED screen can name what happened instead of offering a retry with no
 * explanation.
 */
private fun released(state: RuntimeModel, input: RuntimeInput.Released): Decision {
    if (input.commandGeneration != state.commandGeneration) return Decision(state)
    val terminal = setOf(RuntimeState.STOPPING, RuntimeState.STARTING, RuntimeState.RECOVERING)
    if (state.state !in terminal) return Decision(state)
    // A release that arrives while the runtime is still starting is a start that did not finish,
    // whatever it says about its own success.
    val failed = !input.success || state.failAfterRelease || state.state != RuntimeState.STOPPING
    val cleared = state.copy(
        state = if (failed) RuntimeState.FAILED else RuntimeState.STOPPED,
        runtimeGeneration = 0,
        mode = null,
        health = TransportHealth(),
        deferredStart = null,
        connectedAtElapsedRealtimeMillis = null,
        wantRunning = if (failed) false else state.wantRunning,
        failure = if (failed) {
            input.failure ?: state.failure ?: state.health.failure ?: RuntimeFailure(
                domain = FailureDomain.INTERNAL,
                code = HydraCoreErrorCode.RUNTIME_CORE_DIED,
                retryable = true,
            )
        } else {
            null
        },
        // Delays measured through a tunnel that is gone are not delays. A standalone sweep keeps
        // its results: it measured each server on its own and owes nothing to this session.
        latencies = if (state.latencyGeneration == 0L) state.latencies else emptyList(),
        edgeLatencies = if (state.latencyGeneration == 0L) state.edgeLatencies else emptyList(),
        latencyGeneration = 0,
        observedOutbounds = emptyList(),
    )
    val timers = listOf(TimerOp.Cancel(state.commandGeneration))
    return state.deferredStart?.takeIf { !failed }?.let { start(cleared, it).copy(timers = timers + TimerOp.Arm(cleared.commandGeneration + 1, RuntimeDeadline.START)) }
        ?: Decision(cleared, timers = timers)
}

private fun health(state: RuntimeModel, input: RuntimeInput.Health): Decision {
    if (state.state !in setOf(RuntimeState.STARTING, RuntimeState.RUNNING, RuntimeState.RECOVERING)) return Decision(state)
    if (input.commandGeneration != state.commandGeneration || input.runtimeGeneration != state.runtimeGeneration) return Decision(state)
    if (state.state in setOf(RuntimeState.STARTING, RuntimeState.RECOVERING)) {
        return when {
            input.challenge -> Decision(state.copy(health = input.health), timers = listOf(TimerOp.Arm(state.commandGeneration, RuntimeDeadline.CHALLENGE)))
            input.health.isReady -> Decision(
                state.copy(
                    state = RuntimeState.RUNNING,
                    health = input.health,
                    connectedAtElapsedRealtimeMillis = input.observedAtElapsedRealtimeMillis.takeIf { it > 0 }
                        ?: state.connectedAtElapsedRealtimeMillis,
                ),
                timers = listOf(TimerOp.Cancel(state.commandGeneration)),
            )
            // A transport that has already given up does not become ready by being waited for.
            // The plan settles this: a dial refused at zero active lanes fails immediately,
            // because waiting out the start deadline reads to the person as a dead network
            // rather than as a refusal they can retry. The core reports it within a couple of
            // seconds and the runtime used to sit in STARTING for the remaining forty-three.
            input.shouldRecover -> stop(
                state.copy(health = input.health),
                failAfterRelease = true,
                wantRunning = false,
                failure = input.health.failure,
            )
            else -> Decision(state.copy(health = input.health))
        }
    }
    return if (state.state == RuntimeState.RUNNING && input.shouldRecover) Decision(
        state.copy(state = RuntimeState.RECOVERING, health = input.health, connectedAtElapsedRealtimeMillis = null),
        timers = listOf(TimerOp.Arm(state.commandGeneration, RuntimeDeadline.RECOVERY)),
    ) else Decision(state.copy(health = input.health))
}
