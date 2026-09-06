package io.hydrabox.platform.android

import io.hydrabox.core.contract.CommandGeneration
import io.hydrabox.core.contract.EventSequence
import io.hydrabox.core.contract.OutboundSelection
import io.hydrabox.core.contract.ProcessEpoch
import io.hydrabox.core.contract.RuntimeCommand
import io.hydrabox.core.contract.RuntimeEvent
import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.RuntimeSnapshot
import io.hydrabox.core.contract.RuntimeTransport
import io.hydrabox.core.runtime.Effect
import io.hydrabox.core.runtime.RuntimeInput
import io.hydrabox.core.runtime.RuntimeModel
import io.hydrabox.core.runtime.TimerOp
import io.hydrabox.core.runtime.reduce
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The runtime as this process runs it: the pure reducer, plus the two things a reducer
 * cannot do — run effects, and let time pass.
 *
 * The deadlines are the half the alpha left out. `RuntimeDeadline.START` is 45 seconds
 * because a core that has not reported readiness by then is not going to; with nothing arming
 * that timer, a start that hung left the interface saying "connecting" for as long as the
 * person was willing to look at it.
 */
class AndroidRuntime(private val execute: (Effect) -> Unit) : RuntimeTransport {
    private val epoch = ProcessEpoch(UUID.randomUUID().toString())
    private var model = RuntimeModel()
    private var sequence = 0L
    private val listeners = mutableSetOf<(RuntimeEvent) -> Unit>()
    private val clock = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val armed = mutableMapOf<Long, ScheduledFuture<*>>()

    /**
     * The one thread that runs lifecycle effects.
     *
     * Effects used to run on whatever thread dispatched, and three different ones did: the main
     * thread of the service, a binder thread carrying a command from the interface, and the timer
     * that fires deadlines. `StartCore` and `StopCore` block on the core for seconds, so a command
     * arriving over binder held the interface's main thread for the whole of a close — and two of
     * them could be inside the core at once. One thread makes them a queue instead, and the caller
     * gets its acknowledgement as soon as the state has moved.
     */
    private val lifecycle = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        { runnable -> Thread(runnable, "runtime-lifecycle").apply { isDaemon = true } },
    )

    override fun submit(command: RuntimeCommand) {
        dispatch(
            when (command) {
                is RuntimeCommand.Start -> RuntimeInput.Start(command.mode)
                RuntimeCommand.Stop -> RuntimeInput.Stop
                RuntimeCommand.Reload -> RuntimeInput.Reload
                is RuntimeCommand.SelectOutbound ->
                    RuntimeInput.SelectOutbound(OutboundSelection(command.groupId, command.outboundId))
                is RuntimeCommand.NetworkChanged -> RuntimeInput.NetworkChanged(command.generation)
            },
        )
    }

    @Synchronized
    fun dispatch(input: RuntimeInput) {
        if (closed) return
        val decision = reduce(model, input)
        decision.timers.forEach(::apply)
        val changed = decision.state != model
        model = decision.state
        if (changed) sequence += 1
        // Queue under the reducer lock, after installing the new state. An effect may immediately
        // dispatch its completion from the executor thread; it must see the state that declared it.
        decision.effects.forEach { effect -> lifecycle.execute { execute(effect) } }
        if (!changed) return
        val event = RuntimeEvent.Snapshot(EventSequence(sequence), snapshot())
        listeners.toList().forEach { listener ->
            runCatching { listener(event) }
                .onFailure { HydraLog.warn(AREA, "runtime subscriber failed", it) }
        }
    }
    private fun apply(operation: TimerOp) = when (operation) {
        is TimerOp.Arm -> synchronized(armed) {
            armed.remove(operation.commandGeneration)?.cancel(false)
            armed[operation.commandGeneration] = clock.schedule(
                {
                    HydraLog.warn(
                        AREA,
                        "${operation.deadline.name.lowercase()} deadline expired for command ${operation.commandGeneration}",
                    )
                    dispatch(RuntimeInput.Deadline(operation.commandGeneration))
                },
                operation.deadline.milliseconds,
                TimeUnit.MILLISECONDS,
            )
            Unit
        }

        is TimerOp.Cancel -> synchronized(armed) {
            armed.remove(operation.commandGeneration)?.cancel(false)
            Unit
        }
    }

    /**
     * Runs work on the one thread that owns the core.
     *
     * Anything that builds a core instance belongs here — a start, a close, and the offline sweep
     * that creates a whole core per server — because two of those inside the core at once compete
     * for the same memory and the same flood-controlled VK join.
     */
    fun onLifecycleThread(block: () -> Unit) {
        runCatching { lifecycle.execute(block) }
            .onFailure { HydraLog.warn(AREA, "the runtime is closing; work was not queued") }
    }

    @Volatile private var closed = false

    @Synchronized
    fun close(cleanup: () -> Unit = {}) {
        if (closed) return
        closed = true
        synchronized(armed) {
            armed.values.forEach { it.cancel(false) }
            armed.clear()
        }
        clock.shutdownNow()
        listeners.clear()
        lifecycle.queue.clear()
        lifecycle.execute(cleanup)
        lifecycle.shutdown()
    }

    @Synchronized
    override fun snapshot() = RuntimeSnapshot(
        processEpoch = epoch,
        commandGeneration = CommandGeneration(model.commandGeneration),
        runtimeGeneration = RuntimeGeneration(model.runtimeGeneration),
        networkGeneration = model.networkGeneration,
        lastEventSequence = EventSequence(sequence),
        state = model.state,
        mode = model.mode,
        selectedOutbounds = model.selectedOutbounds,
        observedOutbounds = model.observedOutbounds,
        transportHealth = model.health,
        lastFailure = model.failure,
        traffic = model.traffic,
        latencies = model.latencies,
        connectedAtElapsedRealtimeMillis = model.connectedAtElapsedRealtimeMillis,
    )

    override fun subscribe(listener: (RuntimeEvent) -> Unit): AutoCloseable = synchronized(this) {
        listeners += listener
        listener(RuntimeEvent.Snapshot(EventSequence(sequence), snapshot()))
        AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    private companion object { const val AREA = "runtime" }
}
