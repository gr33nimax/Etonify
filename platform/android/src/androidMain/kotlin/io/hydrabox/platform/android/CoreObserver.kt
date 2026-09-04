package io.hydrabox.platform.android

import android.os.SystemClock
import io.hydrabox.core.contract.OutboundLatency
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
) {
    private var client: CommandClient? = null

    /** The log stream, which is separate so it can be absent. */
    private var logClient: CommandClient? = null

    /** Guards [logClient]: the stream is opened and closed from the runtime's dispatch threads. */
    private val logLock = Any()

    /** The command generation the running core belongs to, for the health it reports. */
    @Volatile private var generation: Long = 0

    /** Health is reported once per start; the core does not repeat itself. */
    @Volatile private var reported = false

    /**
     * Whether the core's transport health describes the route in use. It only ever describes
     * the VK transport, so on any other server it is a fact about something nobody asked for,
     * and letting it decide the product state would drag a working tunnel into recovery.
     */
    @Volatile private var transportApplicable = false

    /** The last health published, so a snapshot a second is not a dispatch a second. */
    @Volatile private var lastHealth: TransportHealth? = null

    fun start(commandGeneration: Long = 0, transportApplicable: Boolean = false) {
        generation = commandGeneration
        reported = false
        lastHealth = null
        this.transportApplicable = transportApplicable
        if (client != null) return
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandGroup)
            statusInterval = STATUS_INTERVAL_NANOS
        }
        val created = Libbox.newCommandClient(handler, options) ?: return
        client = created
        runCatching { created.connect() }
            .onFailure { onLog(LEVEL_WARN, "status stream unavailable: ${it.message}") }
    }

    /**
     * Turns the core's own log stream on or off while the tunnel runs.
     *
     * It used to be part of the one subscription and therefore always on. That is the expensive
     * half: every line the core emits crosses the language boundary through a gomobile iterator,
     * and profiled on the device the crossing — not the lines — was around seven percent of one
     * core, for a journal nobody had open. Counters and group updates stay on their own client so
     * this can come and go without disturbing them.
     */
    fun setLogStream(enabled: Boolean) = synchronized(logLock) {
        if (enabled == (logClient != null)) return@synchronized
        if (!enabled) {
            runCatching { logClient?.disconnect() }
            logClient = null
            return@synchronized
        }
        val options = CommandClientOptions().apply { addCommand(Libbox.CommandLog) }
        val created = Libbox.newCommandClient(handler, options) ?: return@synchronized
        logClient = created
        runCatching { created.connect() }.onFailure {
            logClient = null
            HydraLog.warn(AREA, "the core's log stream would not open: ${it.message}")
        }
    }

    fun stop() {
        synchronized(logLock) {
            runCatching { logClient?.disconnect() }
            logClient = null
        }
        runCatching { client?.disconnect() }
        client = null
        reported = false
        dispatch(RuntimeInput.Traffic(TrafficCounters(available = false)))
    }

    /** Selects inside the running core, so switching server does not restart the tunnel. */
    fun select(group: String, outbound: String): Boolean =
        runCatching { requireNotNull(client).selectOutbound(group, outbound) }.isSuccess

    fun reload(): Boolean = runCatching { requireNotNull(client).serviceReload() }.isSuccess

    /**
     * Measures every member of a group now, instead of waiting for the next interval.
     *
     * The bare `startURLTest` was leaving the core to its own defaults, which is why the
     * stored probe timeout and concurrency never did anything, and why a tap on "measure"
     * could return the same cached figures it had just shown: without [force] the core skips
     * every member whose last result is younger than the group interval — half an hour.
     */
    fun measure(group: String, request: MeasureRequest): Boolean = runCatching {
        requireNotNull(client).startURLTestWithOptions(
            group,
            // No single target and nothing excluded: this is the whole group, on demand.
            "",
            request.priorityTag.orEmpty(),
            "",
            request.url,
            request.timeoutMillis,
            request.concurrency,
            request.deadlineMillis,
            true,
        )
    }.isSuccess

    private val handler = object : CommandClientHandler {
        override fun connected() = Unit

        override fun disconnected(message: String?) {
            HydraLog.warn(AREA, "status stream disconnected${message?.let { ": $it" }.orEmpty()}")
            dispatch(RuntimeInput.Traffic(TrafficCounters(available = false)))
        }

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

        override fun writeStatus(message: StatusMessage?) {
            message ?: return
            // The first status message is the proof that the core is not merely holding a
            // configuration but running with a command socket to answer on. That is what the
            // product calls connected; before it, "connected" was a guess. On the VK transport
            // that proof is not enough — the core knows how many of its lanes are alive, and
            // a tunnel with none of them carries nothing while looking connected.
            if (transportApplicable) {
                publishTransport()
            } else if (!reported) {
                reported = true
                HydraLog.info(AREA, "the core is answering, traffic available ${message.trafficAvailable}")
                dispatch(
                    RuntimeInput.Health(
                        commandGeneration = generation,
                        runtimeGeneration = generation,
                        health = TransportHealth(
                            state = TransportHealthState.HEALTHY,
                            activeLanes = 1,
                            applicable = true,
                            runtimeGeneration = RuntimeGeneration(generation),
                        ),
                        observedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    ),
                )
            }
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

        /**
         * The transport as the core actually reports it: stage, lanes alive out of the lanes it
         * wants, and the typed failure with the wait the provider asked for. Published only when
         * it changes, and it is the same value that promotes the product to connected — so a
         * tunnel whose lanes never came up stays "connecting" instead of claiming success.
         */
        private fun publishTransport() {
            val health = TransportState.read(generation) ?: return
            if (health == lastHealth) return
            lastHealth = health
            val line = TransportState.describe(health)
            if (health.state == TransportHealthState.FAILED) HydraLog.warn(AREA, line) else HydraLog.info(AREA, line)
            dispatch(
                RuntimeInput.Health(
                    commandGeneration = generation,
                    runtimeGeneration = generation,
                    health = health,
                    // A captcha is not a failure to recover from: it is a person being asked
                    // something, and the reducer has a longer deadline for exactly that.
                    challenge = health.state == TransportHealthState.WAITING_USER,
                    shouldRecover = health.state == TransportHealthState.FAILED,
                    observedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                ),
            )
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            val collected = mutableListOf<OutboundLatency>()
            while (message?.hasNext() == true) {
                val group = message.next()
                // What the core says it is actually routing through. It is not the same question
                // as what was stored: the core keeps its own selection in the cache file and
                // restores it ahead of the configuration's `default`, so the two can disagree
                // and only this tells us which way.
                group.selected?.takeIf { it.isNotEmpty() }?.let { onSelected(group.tag.orEmpty(), it) }
                val items = group.items
                while (items.hasNext()) {
                    val item = items.next()
                    val status = item.urlTestStatus.orEmpty()
                    if (item.urlTestDelay > 0 || status.isNotEmpty()) {
                        collected += OutboundLatency(item.tag, item.urlTestDelay, status)
                    }
                }
            }
            if (collected.isNotEmpty()) dispatch(RuntimeInput.Latencies(collected))
        }
    }

    private companion object {
        const val AREA = "core-observer"
        const val STATUS_INTERVAL_NANOS = 1_000_000_000L

        /** The core's own number for a warning, for the one line this class emits itself. */
        const val LEVEL_WARN = 3
    }
}
