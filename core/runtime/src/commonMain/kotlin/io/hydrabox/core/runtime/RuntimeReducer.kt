package io.hydrabox.core.runtime

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
    RELOAD(15_000),
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
    ) : RuntimeInput
    data class Deadline(val commandGeneration: Long) : RuntimeInput
    data class Released(val commandGeneration: Long, val success: Boolean) : RuntimeInput
    data object DeviceIdleExit : RuntimeInput
    /** Counters observed from the core; carries no decision, only what to display. */
    data class Traffic(val counters: TrafficCounters) : RuntimeInput
    /** Latencies measured by the core's own group; likewise display-only. */
    data class Latencies(val values: List<OutboundLatency>) : RuntimeInput
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
    val failure: RuntimeFailure? = null,
    val failAfterRelease: Boolean = false,
    val deferredStart: RuntimeMode? = null,
    val traffic: TrafficCounters = TrafficCounters(),
    val latencies: List<OutboundLatency> = emptyList(),
)

sealed interface Effect {
    data class StartCore(val mode: RuntimeMode, val commandGeneration: Long) : Effect
    data class StopCore(val commandGeneration: Long) : Effect
    data class ReloadCore(val commandGeneration: Long) : Effect
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
    is RuntimeInput.Latencies -> Decision(state.copy(latencies = input.values))
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
    RuntimeInput.Reload -> if (state.state == RuntimeState.RUNNING) Decision(
        state,
        effects = listOf(Effect.ReloadCore(state.commandGeneration)),
        timers = listOf(TimerOp.Arm(state.commandGeneration, RuntimeDeadline.RELOAD)),
    ) else Decision(state)
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
            stop(state, failAfterRelease = true, wantRunning = false)
        // A close that never reports back must not leave the runtime in STOPPING for good.
        // `stop` arms this deadline, and nothing was answering it: the state machine had no
        // way out of STOPPING except a release that, by definition, was not coming.
        state.state == RuntimeState.STOPPING ->
            released(state, RuntimeInput.Released(input.commandGeneration, success = false))
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
        state.copy(state = RuntimeState.STARTING, commandGeneration = generation, runtimeGeneration = 0, mode = mode, wantRunning = true, recoveryAttempts = 0, failure = null),
        effects = listOf(Effect.StartCore(mode, generation)),
        timers = listOf(TimerOp.Arm(generation, RuntimeDeadline.START)),
    )
}

private fun stop(
    state: RuntimeModel,
    failAfterRelease: Boolean = false,
    deferredStart: RuntimeMode? = null,
    wantRunning: Boolean = state.wantRunning,
): Decision {
    val generation = state.commandGeneration + 1
    return Decision(
        state.copy(state = RuntimeState.STOPPING, commandGeneration = generation, failAfterRelease = failAfterRelease, deferredStart = deferredStart, wantRunning = wantRunning),
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
        state.copy(state = RuntimeState.RECOVERING, commandGeneration = generation, runtimeGeneration = 0, recoveryAttempts = state.recoveryAttempts + 1),
        effects = listOf(Effect.StartCore(mode, generation)),
        timers = listOf(TimerOp.Cancel(state.commandGeneration), TimerOp.Arm(generation, RuntimeDeadline.RECOVERY)),
    )
}

private fun released(state: RuntimeModel, input: RuntimeInput.Released): Decision {
    if (state.state != RuntimeState.STOPPING || input.commandGeneration != state.commandGeneration) return Decision(state)
    val cleared = state.copy(
        state = if (input.success && !state.failAfterRelease) RuntimeState.STOPPED else RuntimeState.FAILED,
        runtimeGeneration = 0,
        mode = null,
        health = TransportHealth(),
        deferredStart = null,
    )
    val timers = listOf(TimerOp.Cancel(state.commandGeneration))
    return state.deferredStart?.takeIf { input.success && !state.failAfterRelease }?.let { start(cleared, it).copy(timers = timers + TimerOp.Arm(cleared.commandGeneration + 1, RuntimeDeadline.START)) }
        ?: Decision(cleared, timers = timers)
}

private fun health(state: RuntimeModel, input: RuntimeInput.Health): Decision {
    if (input.commandGeneration != state.commandGeneration || input.runtimeGeneration != state.runtimeGeneration) return Decision(state)
    if (state.state in setOf(RuntimeState.STARTING, RuntimeState.RECOVERING)) {
        return when {
            input.challenge -> Decision(state.copy(health = input.health), timers = listOf(TimerOp.Arm(state.commandGeneration, RuntimeDeadline.CHALLENGE)))
            input.health.isReady -> Decision(state.copy(state = RuntimeState.RUNNING, health = input.health), timers = listOf(TimerOp.Cancel(state.commandGeneration)))
            // A transport that has already given up does not become ready by being waited for.
            // The plan settles this: a dial refused at zero active lanes fails immediately,
            // because waiting out the start deadline reads to the person as a dead network
            // rather than as a refusal they can retry. The core reports it within a couple of
            // seconds and the runtime used to sit in STARTING for the remaining forty-three.
            input.shouldRecover -> stop(state.copy(health = input.health), failAfterRelease = true, wantRunning = false)
            else -> Decision(state.copy(health = input.health))
        }
    }
    return if (state.state == RuntimeState.RUNNING && input.shouldRecover) Decision(
        state.copy(state = RuntimeState.RECOVERING, health = input.health),
        timers = listOf(TimerOp.Arm(state.commandGeneration, RuntimeDeadline.RECOVERY)),
    ) else Decision(state.copy(health = input.health))
}
