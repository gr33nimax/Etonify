package io.hydrabox.platform.android

import android.os.SystemClock
import io.hydrabox.core.contract.FailureDomain
import io.hydrabox.core.contract.HydraCoreErrorCode
import io.hydrabox.core.contract.OutboundLatency
import io.hydrabox.core.contract.OutboundSelection
import io.hydrabox.core.contract.RuntimeFailure
import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.TrafficCounters
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.contract.TransportHealthState
import io.hydrabox.core.runtime.RuntimeInput
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.RuntimeEventHandler
import io.nekohasekai.libbox.RuntimeEvents
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator

/**
 * What one manual measurement is allowed to cost.
 *
 * Every field is a setting a person can change and none of them reached the core before:
 * how long a single probe may take, how many run at once, how long the whole sweep may last,
 * and which server is measured first — the one they are connected through, so its figure
 * arrives before the list settles.
 */
data class MeasureRequest(
    val url: String,
    val timeoutMillis: Int,
    val concurrency: Int,
    val deadlineMillis: Int,
    val priorityTag: String? = null,
    /** One member the group must not measure, by the core's own tag. */
    val excludeTag: String? = null,
)

/**
 * Observes the running core: traffic counters and the delay its own latency group measured
 * per outbound. Observation only — it issues no decision, and everything it learns reaches
 * the UI through the snapshot, never directly.
 */
class CoreObserver(
    private val dispatch: (RuntimeInput) -> Unit,
    /**
     * One line the core emitted, with **the level the core gave it**.
     *
     * The level used to be dropped here and guessed again from the text of the message. It never
     * belonged there: `WriteMessage` passes the level as its own argument and the platform
     * formatter need not repeat it in the words, so matching on the text classified whatever it
     * failed to recognise as debug — and at a warning threshold that silently discarded the line
     * after paying to carry it across.
     */
    private val onLog: (Int, String) -> Unit = { _, _ -> },
    /**
     * Which outbound the core reports as the one in use, per group, whenever that is published.
     *
     * The core's own answer is the only trustworthy one. `SelectOutbound` writes the choice into
     * the cache file, and on the next start `outboundSelect` reads the cache **before** it looks
     * at the configuration's `default` — so a server chosen once keeps being used after the
     * person has chosen a different one, while every screen shows the new name.
     */
    private val onSelected: (String, String) -> Unit = { _, _ -> },
    private val isCallTransport: (String) -> Boolean = { false },
    private val staleAfterMillis: () -> Long = { 0 },
) {
    private var client: CommandClient? = null

    private class Observation(val generation: Long) {
        @Volatile var lastHealth: TransportHealth? = null
        @Volatile var selectedTag = ""
        @Volatile var autoTag = ""
        @Volatile var coreResponding = false
        @Volatile var coreHealth: io.nekohasekai.libbox.TransportHealth? = null
    }

    /** Replacing this object invalidates every callback that captured the previous session. */
    @Volatile private var observation = Observation(0)

    /**
     * Whether this observer is meant to be watching. Not the same thing as [client] being
     * non-null: a client that failed to connect used to be left in place, and since `start`
     * returns early when one exists, that one failure stopped the application observing the core
     * for the rest of the process's life.
     */
    @Volatile private var watching = false

    /** Guards [client] against the reconnect thread and the runtime's dispatch threads. */
    private val clientLock = Any()
    private var clientToken: Any? = null

    /** How many times attaching has been tried since the last one that worked. */
    private val attempts = java.util.concurrent.atomic.AtomicInteger(0)

    /** Reattaches, and nothing else: one thread, so an attempt cannot overlap another. */
    private val reconnects = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "core-observer-attach").apply { isDaemon = true }
    }

    fun start(commandGeneration: Long = 0) {
        val current = Observation(commandGeneration)
        observation = current
        attempts.set(0)
        watching = true
        attach(current)
    }

    /**
     * Opens the runtime stream, and leaves nothing behind if it will not open.
     *
     * The client used to be stored before `connect` and never cleared when that threw. Since an
     * existing client is what makes `start` return early, one failed attach — the command socket
     * not yet listening, which is a race with the core's own startup — meant no counters, no
     * groups and no transport health for the rest of the process, behind a screen that went on
     * showing whatever it last knew.
     */
    private fun attach(current: Observation) {
        val token = Any()
        lateinit var created: CommandClient
        synchronized(clientLock) {
            if (!isCurrent(current) || client != null) return
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandRuntimeEvents)
                runtimeEventIntervalMillis = RUNTIME_EVENT_INTERVAL_MILLIS
            }
            created = Libbox.newCommandClient(runtimeClientHandler(current, token), options)
                ?: return scheduleAttach(current, "the core would not give out a command client")
            created.setRuntimeEventHandler(runtimeHandler(current))
            client = created
            clientToken = token
        }
        runCatching { created.connect() }.fold(
            onSuccess = {
                val keep = synchronized(clientLock) {
                    val active = isCurrent(current) && clientToken === token
                    if (!active && clientToken === token) {
                        client = null
                        clientToken = null
                    }
                    active
                }
                if (keep) attempts.set(0) else runCatching { created.disconnect() }
            },
            onFailure = { failure ->
                val retry = synchronized(clientLock) {
                    if (clientToken === token) {
                        client = null
                        clientToken = null
                    }
                    isCurrent(current)
                }
                runCatching { created.disconnect() }
                if (retry) scheduleAttach(current, failure.message ?: "connect refused")
            },
        )
    }

    private fun isCurrent(current: Observation): Boolean = watching && observation === current

    private fun scheduleAttach(current: Observation, reason: String) {
        if (!isCurrent(current)) return
        val attempt = attempts.incrementAndGet()
        if (attempt > MAX_ATTACH_ATTEMPTS) {
            HydraLog.error(AREA, "the runtime stream is unreachable after $attempt attempts: $reason")
            reportObservationLost(current)
            return
        }
        val delay = ATTACH_BACKOFF_MILLIS shl (attempt - 1).coerceAtMost(4)
        HydraLog.warn(AREA, "the runtime stream would not open ($reason); trying again in ${delay}ms")
        runCatching {
            reconnects.schedule(
                { attach(current) },
                delay,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * Losing sight of the core is its own state, and not evidence that the transport is well.
     *
     * It is published as a failure of this process's own plumbing rather than of the transport, so
     * the journal and the failure screen name the right thing; the runtime treats it as a reason
     * to recover, which for a command server that is gone is the only useful answer.
     */
    private fun reportObservationLost(current: Observation) {
        if (!isCurrent(current)) return
        val lost = TransportHealth(
            state = TransportHealthState.FAILED,
            activeLanes = 0,
            applicable = true,
            runtimeGeneration = RuntimeGeneration(current.generation),
            failure = RuntimeFailure(
                domain = FailureDomain.INTERNAL,
                code = HydraCoreErrorCode.RUNTIME_IPC_LOST,
                retryable = true,
            ),
        )
        current.lastHealth = lost
        dispatch(
            RuntimeInput.Health(
                commandGeneration = current.generation,
                runtimeGeneration = current.generation,
                health = lost,
                shouldRecover = true,
                observedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            ),
        )
    }

    /**
     * The core's log stream, which is separate so it can be absent.
     *
     * It used to be part of the one subscription and therefore always on. That is the
     * expensive half: every line the core emits crosses the language boundary through a
     * gomobile iterator, and profiled on the device the crossing — not the lines — was
     * around seven percent of one core, for a journal nobody had open. Counters and group
     * updates stay on their own client so this can come and go without disturbing them,
     * and losing it no longer leaves a dead client behind that blocks every later enable.
     */
    fun stop() {
        watching = false
        logStream.close()
        val runtimeClient = synchronized(clientLock) {
            val existing = client
            client = null
            clientToken = null
            existing
        }
        runCatching { runtimeClient?.disconnect() }
        observation = Observation(0)
        dispatch(RuntimeInput.Traffic(TrafficCounters(available = false)))
    }

    /** Ends the attach thread with the service. The observer cannot be started again after this. */
    fun close() {
        stop()
        reconnects.shutdownNow()
    }

    /** Selects inside the running core, so switching server does not restart the tunnel. */
    fun select(group: String, outbound: String): Boolean =
        runCatching { requireNotNull(current()).selectOutbound(group, outbound) }.isSuccess

    /** Reads one fresh core snapshot, used after Doze before deciding whether recovery is needed. */
    fun refresh(): Boolean {
        val active = observation
        val snapshot = runCatching { requireNotNull(current()).getRuntimeSnapshot() }.getOrNull() ?: return false
        handleSnapshot(active, snapshot)
        return true
    }

    private fun current(): CommandClient? = synchronized(clientLock) { client }

    /**
     * Measures every member of a group now, instead of waiting for the next interval.
     *
     * The bare `startURLTest` was leaving the core to its own defaults, which is why the
     * stored probe timeout and concurrency never did anything, and why a tap on "measure"
     * could return the same cached figures it had just shown: without [force] the core skips
     * every member whose last result is younger than the group interval — half an hour.
     */
    fun measure(group: String, request: MeasureRequest): Boolean = runCatching {
        requireNotNull(current()).startURLTestWithOptions(
            group,
            // No single target: this is the whole group, on demand — except the member the
            // caller excluded, which answers a different question on its own thread.
            "",
            request.priorityTag.orEmpty(),
            request.excludeTag.orEmpty(),
            request.url,
            request.timeoutMillis,
            request.concurrency,
            request.deadlineMillis,
            true,
        )
    }.isSuccess

    /**
     * Where an outbound comes out, asked of the core.
     *
     * The app's own HTTP request only ever proves where the *app* comes out. That is the same
     * thing while every byte of this process goes through the tunnel, and a different thing in
     * proxy-only mode, with the app excluded from the tunnel by the split rules, or under a route
     * rule that sends the endpoint direct — cases where the address shown was the person's real
     * one presented as the exit. The core dials the endpoint through the named outbound, so the
     * answer is evidence about the route. Blocking; call it off the main thread.
     */
    fun exitAddress(outboundTag: String): Pair<String, String?>? = runCatching {
        val info = requireNotNull(current()).lookupOutboundExternalInfo(outboundTag)
        val address = info.ip.orEmpty().trim().takeIf(String::isNotEmpty) ?: return null
        address to info.countryCode.orEmpty().trim().takeIf { it.length == 2 && it.all(Char::isLetter) }
    }.onFailure { HydraLog.debug(AREA, "the core could not report the exit of $outboundTag: ${it.message}") }
        .getOrNull()

    private val handler = object : CommandClientHandler {
        override fun connected() = Unit

        override fun disconnected(message: String?) = Unit

        override fun clearLogs() = Unit

        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit

        override fun setDefaultLogLevel(level: Int) = Unit

        override fun updateClashMode(newMode: String?) = Unit

        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit

        override fun writeLogs(messageList: LogIterator?) {
            while (messageList?.hasNext() == true) {
                val entry = messageList.next()
                onLog(entry.level, entry.message.orEmpty())
            }
        }

        override fun writeStatus(message: StatusMessage?) = Unit

        override fun writeGroups(message: OutboundGroupIterator?) = Unit
    }

    /**
     * The core's log stream, which is separate so it can be absent.
     *
     * It used to be part of the one subscription and therefore always on. That is the
     * expensive half: every line the core emits crosses the language boundary through a
     * gomobile iterator, and profiled on the device the crossing — not the lines — was
     * around seven percent of one core, for a journal nobody had open. Counters and group
     * updates stay on their own client so this can come and go without disturbing them,
     * and losing it no longer leaves a dead client behind that blocks every later enable.
     */
    private val logStream = LogStream(
        base = handler,
        newClient = { streamHandler ->
            Libbox.newCommandClient(streamHandler, CommandClientOptions().apply { addCommand(Libbox.CommandLog) })
                ?.let { client ->
                    object : LogStream.Client {
                        override fun connect() = client.connect()
                        override fun disconnect() = client.disconnect()
                    }
                }
        },
        schedule = { delay, action ->
            reconnects.schedule(action, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        },
        report = { message, severe ->
            if (severe) HydraLog.error(AREA, message) else HydraLog.warn(AREA, message)
        },
    )

    fun setLogStream(enabled: Boolean) {
        logStream.setEnabled(enabled)
    }

    private fun handleStatus(current: Observation, message: StatusMessage?) {
        if (!isCurrent(current)) return
        message ?: return
        current.coreResponding = true
        publishTransport(current)
        dispatch(
            RuntimeInput.Traffic(
                TrafficCounters(
                    available = message.trafficAvailable,
                    uplink = message.uplink,
                    downlink = message.downlink,
                    uplinkTotal = message.uplinkTotal,
                    downlinkTotal = message.downlinkTotal,
                    connectionsOut = message.connectionsOut,
                ),
            ),
        )
    }

    private fun handleGroups(current: Observation, message: OutboundGroupIterator?) {
        if (!isCurrent(current)) return
        val collected = mutableListOf<OutboundLatency>()
        while (message?.hasNext() == true) {
            val group = message.next()
            group.selected?.takeIf { it.isNotEmpty() }?.let {
                if (group.tag == io.hydrabox.core.config.SELECTOR_TAG) current.selectedTag = it
                if (group.tag == io.hydrabox.core.config.AUTO_TAG) current.autoTag = it
                onSelected(group.tag.orEmpty(), it)
                dispatch(
                    RuntimeInput.SelectionObserved(
                        commandGeneration = current.generation,
                        selection = OutboundSelection(group.tag.orEmpty(), it),
                    ),
                )
            }
            val items = group.items
            while (items.hasNext()) {
                val item = items.next()
                val status = item.urlTestStatus.orEmpty()
                if (item.urlTestDelay > 0 || status.isNotEmpty()) {
                    collected += OutboundLatency(
                        tag = item.tag,
                        delayMillis = item.urlTestDelay,
                        status = status,
                        observedAtMillis = item.urlTestTime * 1000,
                        staleAfterMillis = staleAfterMillis(),
                    )
                }
            }
        }
        if (!isCurrent(current)) return
        dispatch(RuntimeInput.Latencies(collected, current.generation))
        publishTransport(current)
    }

    private fun handleSnapshot(current: Observation, snapshot: io.nekohasekai.libbox.RuntimeSnapshot) {
        if (!isCurrent(current)) return
        current.coreHealth = snapshot.transportHealth
        handleGroups(current, snapshot.groups())
        handleStatus(current, snapshot.status)
    }

    /**
     * The runtime stream's own handler.
     *
     * It used to be shared with the log stream, so closing the log stream — which happens on every
     * transition into RUNNING, by design — reported the counters as unavailable and looked exactly
     * like losing the core.
     */
    private fun runtimeClientHandler(current: Observation, token: Any) = object : CommandClientHandler by handler {
        override fun writeStatus(message: StatusMessage?) = handleStatus(current, message)

        override fun writeGroups(message: OutboundGroupIterator?) = handleGroups(current, message)

        override fun disconnected(message: String?) {
            if (!isCurrent(current)) return
            HydraLog.warn(AREA, "the runtime stream disconnected${message?.let { ": $it" }.orEmpty()}")
            dispatch(RuntimeInput.Traffic(TrafficCounters(available = false)))
            current.coreResponding = false
            val disconnected = synchronized(clientLock) {
                if (clientToken !== token) return@synchronized null
                val existing = client
                client = null
                clientToken = null
                existing
            }
            runCatching { disconnected?.disconnect() }
            if (isCurrent(current)) scheduleAttach(current, message ?: "stream closed")
        }
    }

    /**
     * Applies transport health only to the outbound and runtime generation that produced it.
     * A previous VK bridge may still report while the selector already routes through VLESS;
     * that event must not move the active tunnel into recovery.
     */
    private fun publishTransport(current: Observation) {
        if (!isCurrent(current)) return
        // The route in use, following the chain rather than stopping at the first name in it. The
        // selector may be routing through `auto`, and `auto`'s own choice is the server that
        // actually carries the traffic — so checking the selector's answer alone let an automatic
        // choice sitting on a sick VK transport report itself as an ordinary healthy server.
        val selected = current.selectedTag.takeIf { it != io.hydrabox.core.config.AUTO_TAG }
            ?: current.autoTag.takeIf(String::isNotEmpty)
            ?: current.selectedTag
        val expected = selected.isNotEmpty() && isCallTransport(selected)
        val reported = current.coreHealth?.takeIf {
            it.transportTag == selected && it.runtimeGeneration == current.generation
        }
        if (expected && reported == null) return
        if (!expected && !current.coreResponding) return
        val health = if (expected) {
            TransportState.from(requireNotNull(reported))
        } else {
            TransportHealth(
                state = TransportHealthState.HEALTHY,
                activeLanes = 1,
                applicable = false,
                runtimeGeneration = RuntimeGeneration(current.generation),
            )
        }
        if (health == current.lastHealth) return
        current.lastHealth = health
        val line = TransportState.describe(health)
        if (health.state == TransportHealthState.FAILED) HydraLog.warn(AREA, line) else HydraLog.info(AREA, line)
        dispatch(
            RuntimeInput.Health(
                commandGeneration = current.generation,
                runtimeGeneration = current.generation,
                health = health,
                challenge = health.state == TransportHealthState.WAITING_USER,
                shouldRecover = health.state == TransportHealthState.FAILED,
                observedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            ),
        )
    }

    private fun runtimeHandler(current: Observation) = object : RuntimeEventHandler {
        override fun writeRuntimeEvents(events: RuntimeEvents?) {
            if (!isCurrent(current)) return
            events ?: return
            events.snapshot?.let { handleSnapshot(current, it) }
            val iterator = events.events()
            while (iterator.hasNext()) {
                val event = iterator.next()
                when (event.type) {
                    Libbox.RuntimeEventStatus -> handleStatus(current, event.status)
                    Libbox.RuntimeEventGroups -> handleGroups(current, event.groups())
                    Libbox.RuntimeEventTransportHealth -> {
                        current.coreHealth = event.transportHealth
                        publishTransport(current)
                    }
                }
            }
        }
    }

    private companion object {
        const val AREA = "core-observer"
        const val RUNTIME_EVENT_INTERVAL_MILLIS = 1_000L

        /**
         * How many times the runtime stream is opened before the core is declared unreachable, and
         * the first wait between attempts — it doubles, so five attempts span about eight seconds.
         * The command socket is in this process; if it is not answering by then, it will not.
         */
        const val MAX_ATTACH_ATTEMPTS = 5
        const val ATTACH_BACKOFF_MILLIS = 500L
    }
}
