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

    fun dispatch(input: RuntimeInput) {
        val decision = synchronized(this) {
            reduce(model, input).also { next ->
                model = next.state
                sequence += 1
            }
        }
        // Effects and timers run outside the lock: `StartCore` blocks on the core, and a
        // deadline firing while it does must not wait for it.
        //
        // Timers are armed BEFORE the effects they guard. `StartCore` and `StopCore` block on
        // the core for as long as it needs and report their own result by dispatching
        // re-entrantly from inside `execute`, so arming afterwards armed a deadline for work
        // that had already finished — and worse, the cancellation that release issues was
        // processed while nothing was armed yet, so the arm that followed could never be
        // cancelled. That is why every ordinary disconnect wrote `close deadline expired` into
        // the journal five seconds after a close that had taken under a second.
        decision.timers.forEach(::apply)
        decision.effects.forEach(execute)
        val event = RuntimeEvent.Snapshot(EventSequence(sequence), snapshot())
        synchronized(this) { listeners.toList() }.forEach { it(event) }
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

    fun close() {
        synchronized(armed) {
            armed.values.forEach { it.cancel(false) }
            armed.clear()
        }
        clock.shutdownNow()
    }

    override fun snapshot() = RuntimeSnapshot(
        processEpoch = epoch,
        commandGeneration = CommandGeneration(model.commandGeneration),
        runtimeGeneration = RuntimeGeneration(model.runtimeGeneration),
        networkGeneration = model.networkGeneration,
        lastEventSequence = EventSequence(sequence),
        state = model.state,
        mode = model.mode,
        selectedOutbounds = model.selectedOutbounds,
        transportHealth = model.health,
        lastFailure = model.failure,
        traffic = model.traffic,
        latencies = model.latencies,
    )

    override fun subscribe(listener: (RuntimeEvent) -> Unit): AutoCloseable = synchronized(this) {
        listeners += listener
        listener(RuntimeEvent.Snapshot(EventSequence(sequence), snapshot()))
        AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    private companion object {
        const val AREA = "runtime"
    }
}
