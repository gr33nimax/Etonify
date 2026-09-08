package io.hydrabox.platform.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.content.pm.ApplicationInfo
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.hydrabox.core.contract.NetworkGeneration
import io.hydrabox.core.contract.EdgeLatencyStatus
import io.hydrabox.core.contract.OutboundLatency
import io.hydrabox.core.contract.RuntimeCommand
import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.runtime.Effect
import io.hydrabox.core.settings.LogLevel
import io.hydrabox.core.settings.NotificationTrafficDisplayMode
import io.hydrabox.core.runtime.RuntimeInput

class HydraVpnService : VpnService() {
    private lateinit var runtime: AndroidRuntime
    private lateinit var endpoint: BinderRuntimeEndpoint
    private lateinit var store: AppStore
    private lateinit var monitor: DefaultNetworkMonitor
    private lateinit var observer: CoreObserver
    private var commandServer: CommandServer? = null
    private var idleWatch: BroadcastReceiver? = null

    /**
     * The generation of ad-blocking rule sets the running core is reading. Holding it stops the
     * next update from deleting those files while the core still has them open; it is released
     * when the core goes away or is replaced by the lease of a newer configuration.
     */
    private var ruleSetLease: AdBlockRuleSets.Lease? = null

    /**
     * Journal lines on their way to the shared database, and the flush that is already
     * scheduled. The core emits in bursts — a start writes dozens of lines in a second — so
     * they are coalesced into one transaction instead of one transaction each.
     */
    private val journal = java.util.concurrent.ConcurrentLinkedQueue<HydraLog.Entry>()
    private val journalWriter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val journalPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The state the notification currently shows, so it is not rewritten for nothing. */
    private var posted: RuntimeState? = null

    /** Read once per start: what the notification carries under the state, if anything. */
    private var liveTraffic = true
    private var trafficDisplay = NotificationTrafficDisplayMode.SPEED

    /** The quietest core line the journal keeps. Read at start, from the chosen log level. */
    private var journalFloor = HydraLog.Level.WARN

    /**
     * Whether the person asked to see the core's own lines. At information level or lower the log
     * stream stays open for as long as the tunnel does; above it, only across starts and failures.
     */
    @Volatile private var wantsCoreDetail = false

    /**
     * Whether the running core can produce lines at all.
     *
     * A core started with logging off used to be mute for the rest of its life, and the only
     * honest answer to "turn it up" was to ask for a reconnect. A core that reports
     * `runtime_log_level` builds a factory that can be enabled afterwards, keeping the loggers
     * its components already hold, so on such a core this is true from the start — while on an
     * older pinned AAR it still depends on how the core was started.
     */
    @Volatile private var coreLogFactoryAvailable = false
    @Volatile private var callTransportTags: Set<String> = emptySet()

    /**
     * Set the moment a stop is asked for. A traffic tick can still arrive while the core is
     * closing, and posting it would put "disconnecting" back on screen with nothing left to
     * take it down again.
     */
    @Volatile private var stopping = false

    /** Prevents repeated taps from queueing complete offline sweeps. */
    private val measuring = java.util.concurrent.atomic.AtomicBoolean(false)
    private val measurementStartId = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Which sweep is current. A start or a stop raises it and the sweep checks it between servers,
     * so pressing Connect during an offline measurement ends the measurement instead of racing it:
     * both build whole core instances, platform DNS resources and, through the VK transport, VK
     * joins of their own.
     */
    private val sweepEpoch = java.util.concurrent.atomic.AtomicInteger()

    /** The probe session in flight, so cancelling can close the one that is blocking. */
    private val probe = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>()

    /** The most recent startId, so giving up started ownership cannot cancel a newer command. */
    private val currentStartId = java.util.concurrent.atomic.AtomicInteger()

    private val handler = object : CommandServerHandler {
        override fun getSystemProxyStatus() = SystemProxyStatus().apply { available = false; enabled = false }
        override fun serviceReload(): Unit = error("live reload is unsupported; reconnect the tunnel")
        override fun serviceStop() = Unit
        override fun setSystemProxyEnabled(enabled: Boolean) = Unit
        override fun writeDebugMessage(message: String?) = Unit
    }

    /**
     * The outbound the person chose, as the tag the core knows it by, and how many times we have
     * had to tell the core about it since this start.
     *
     * Kept here rather than read from the store on the groups callback, and reset on every core
     * start so a stale intention cannot outlive the configuration it belonged to.
     */
    @Volatile private var wantedOutbound: String = io.hydrabox.core.config.AUTO_TAG
    private val corrections = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The workerless edge probes run on their own single thread: one question at a time is
     * the whole parallelism a reachability check on demand deserves, and it must never sit
     * in front of the lifecycle.
     */
    private val edgeProbes = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "turn-edge-probe").apply { isDaemon = true }
    }

    /**
     * The core questions a binder thread must answer, each under a hard deadline: a stuck
     * one is abandoned where it stands, never waited for past the point the caller's reply
     * is worth anything.
     */
    private val exitLookups = BoundedCalls(deadlineMillis = EXIT_LOOKUP_DEADLINE_MILLIS)

    /** Edge answers, kept per edge and network generation for less than a minute. */
    private val edgeCache = TurnEdgeProbe.Cache()

    /**
     * The edge sweep in flight, so a stop or a handover can close the one that is blocking.
     */
    private val edgeSweep = java.util.concurrent.atomic.AtomicReference<EdgeSweep?>()

    /**
     * The TURN edge recorded for the transport this session confirmed ready, as the triple
     * that decided it: the tag, the runtime generation and the network generation.
     * Selection alone never did.
     */
    @Volatile private var recordedTurnEdge: Triple<String, Long, Long>? = null

    /**
     * Makes the core route through the server that was actually chosen.
     *
     * The core does not take the configuration's word for it. `Selector.outboundSelect` reads its
     * cache file first and only falls back to `default`, and `SelectOutbound` writes to that cache
     * on every switch — so once a server has been picked in place, every later start goes back to
     * it while the screens, which read the app's own store, show the newer choice. That is the
     * "chose one server, connected to another" report, and nothing in the app could see it before:
     * the group message carries the core's answer and it was being thrown away.
     */
    private fun reconcileSelection(group: String, actual: String) {
        if (group != io.hydrabox.core.config.SELECTOR_TAG) return
        // A change of selection is not an attribution: after a switch across the VK
        // boundary the new transport has not allocated yet, and the edge on file still
        // belongs to the previous one. The edge is filed when the new transport reports
        // itself ready, not when the selector announces it.
        val wanted = wantedOutbound
        if (actual == wanted) return
        if (corrections.incrementAndGet() > MAX_SELECTION_CORRECTIONS) return
        HydraLog.warn(AREA, "the core is routing through $actual, not the chosen $wanted")
        if (observer.select(group, wanted)) {
            HydraLog.info(AREA, "the core now routes through $wanted")
        } else {
            HydraLog.error(AREA, "the core would not switch to $wanted")
        }
    }

    /**
     * When to read the core's global TURN edge record: only once a call transport of this
     * runtime generation has reported itself ready, which cannot happen before its
     * allocation succeeded — the one moment the attribution is already decided. A change
     * of selection is not that moment: after a switch across the VK boundary the new
     * transport has not allocated yet, and the edge on file still belongs to the previous
     * one.
     */
    private fun recordTurnEdgeIfReady(snapshot: io.hydrabox.core.contract.RuntimeSnapshot) {
        val health = snapshot.transportHealth
        val tag = health.transportTag
        if (tag.isEmpty() || !health.isReady) return
        if (tag !in callTransportTags) return
        if (health.runtimeGeneration.value != snapshot.runtimeGeneration.value) return
        val attribution = Triple(tag, snapshot.runtimeGeneration.value, snapshot.networkGeneration.value)
        if (recordedTurnEdge == attribution) return
        if (recordTurnEdgeFor(tag, snapshot.runtimeGeneration.value)) recordedTurnEdge = attribution
    }

    /**
     * Files the core's TURN edge under the transport that actually reached it.
     *
     * The core's record is global; its attribution — the transport tag and the runtime
     * generation the allocation happened under — is the only thing that makes it this
     * server's edge. A record from an older core carries neither and belongs to nobody in
     * particular; a record of another transport or another runtime belongs to that one.
     * In either case nothing is filed, and the measurement says "not measured" rather than
     * guessing the previous transport's edge for the next server's profile.
     */
    private fun recordTurnEdgeFor(tag: String, runtimeGeneration: Long): Boolean {
        if (!CoreFeatures.turnEdgeAttribution) return false
        val record = runCatching { Libbox.hydraCoreTurnEdgeAttribution() }.getOrNull() ?: return false
        if (record.transportTag.orEmpty() != tag) return false
        if (record.runtimeGeneration != runtimeGeneration) return false
        val endpoint = record.endpoint.orEmpty().takeIf(String::isNotEmpty) ?: return false
        return runCatching { store.recordTurnEdge(tag, endpoint) }
            .onFailure { HydraLog.warn(AREA, "the TURN edge could not be recorded", it) }
            .isSuccess
    }

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        // Everything this process logs has to be readable from the interface process, which is
        // where the journal is drawn.
        HydraLog.sink = { entry ->
            journal.add(entry)
            if (journalPending.compareAndSet(false, true)) {
                journalWriter.schedule(::flushJournal, JOURNAL_FLUSH_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        monitor = DefaultNetworkMonitor(this).apply { start() }
        observer = CoreObserver(
            dispatch = { input -> runtime.dispatch(input) },
            onLog = ::recordCoreLine,
            onSelected = ::reconcileSelection,
            staleAfterMillis = { store.settings().urlTestIntervalSeconds * 1000L },
            isCallTransport = { it in callTransportTags },
        )
        runtime = AndroidRuntime(::execute)
        // The exit lookup crosses on the binder thread and blocks there for as long as the
        // core's own bounded lookup takes; the alternative — answering it from the app's
        // own traffic — proves where the app comes out, not where the tunnel does. The
        // deadline is structural here too: the binder thread has to write its reply before
        // returning, so it never waits on the core longer than the core's own budget plus
        // room for a slow one.
        endpoint = BinderRuntimeEndpoint(runtime) { outboundTag ->
            val started = android.os.SystemClock.elapsedRealtime()
            val answer = exitLookups.ask { observer.exitAddress(outboundTag) }
            HydraLog.info(
                AREA,
                "the exit lookup of $outboundTag " +
                    (answer?.let { "answered ${it.first} in ${android.os.SystemClock.elapsedRealtime() - started}ms" }
                        ?: "has no answer after ${android.os.SystemClock.elapsedRealtime() - started}ms"),
            )
            answer
        }
        runtime.subscribe { event ->
            if (event !is io.hydrabox.core.contract.RuntimeEvent.Snapshot) return@subscribe
            // The core's log stream is worth its cost while the tunnel is coming up or has
            // failed — that is where the one line explaining a refusal lives — and not while it
            // is simply running, which is where all of the time is spent. Someone who asked for
            // information or lower keeps it throughout, because they asked.
            if (commandServer != null) {
                runtime.onLifecycleThread {
                    observer.setLogStream(
                        commandServer != null && coreLogFactoryAvailable && (wantsCoreDetail || runtime.snapshot().state != RuntimeState.RUNNING),
                    )
                }
            }
            if (event.snapshot.state == RuntimeState.STOPPED ||
                event.snapshot.state == RuntimeState.FAILED ||
                stopping
            ) {
                // The tunnel is down, and the last thing posted was "disconnecting". Nothing
                // else takes that notification away: the interface process is bound to this
                // service, so `stopSelf` does not destroy it and Android does not clear a
                // foreground notification of a service that is still alive. That is why
                // "Отключаюсь…" stayed on screen after every disconnect.
                posted = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                // Started ownership ends here, whichever path took the tunnel down. A stop asked
                // for through the interface goes straight to the runtime over binder and never
                // reached `stop()`, so nothing gave the ownership up: the service stayed started
                // after the screens had let go of it, and only Android reclaiming the process ended
                // it. `stopSelfResult` is what makes that safe against a start that has already
                // arrived — a newer command id means this stop is stale and is not honoured.
                if (event.snapshot.state != RuntimeState.STARTING) {
                    stopSelfResult(currentStartId.get())
                }
                return@subscribe
            }
            recordTurnEdgeIfReady(event.snapshot)
            // The counters tick once a second. Reposting the notification on every tick is
            // what the person turned off when they turned off the traffic display, so with it
            // off the notification is rewritten only when the tunnel's state changes.
            val changed = event.snapshot.state != posted
            posted = event.snapshot.state
            if (changed || liveTraffic) startForeground(NOTIFICATION_ID, notification(event.snapshot.state))
        }
        // A network that moves under a running tunnel has to reach the runtime, or the core
        // keeps dialling through an interface that is gone. It also ends any measurement in
        // flight: an offline sweep builds a core session per server on the network it was
        // started on, and its edge questions are asked of that network's sockets — their
        // answers are a comparison that only holds within one network, so the sweep is
        // cancelled outright and the session in flight is closed, not merely waited out.
        monitor.onChanged = { generation ->
            cancelSweep()
            runtime.submit(RuntimeCommand.NetworkChanged(NetworkGeneration(generation)))
        }
        registerIdleWatch()
    }

    /**
     * Leaving Doze is the moment a tunnel that was frozen for hours has to prove it still
     * carries traffic. The runtime already knows what to do with it; nothing was telling it.
     */
    private fun registerIdleWatch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        idleWatch = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (power.isDeviceIdleMode) return
                HydraLog.info(AREA, "the device left idle mode; checking the running core")
                runtime.onLifecycleThread {
                    if (!observer.refresh()) HydraLog.warn(AREA, "the core did not answer the post-idle check")
                }
            }
        }.also {
            runCatching {
                registerReceiver(it, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
            }
        }
    }

    /** A manual measurement uses the running core when one exists, otherwise isolated sessions. */
    private fun measureNow(startId: Int) {
        val state = runtime.snapshot().state
        if (state == RuntimeState.STOPPED || state == RuntimeState.FAILED) {
            measurementStartId.set(startId)
            if (!measuring.compareAndSet(false, true)) return
            val epoch = sweepEpoch.get()
            // On the runtime lifecycle thread: one owner for everything that builds a core, so a
            // sweep and a real start can never be inside the core at the same time.
            runtime.onLifecycleThread {
                runCatching { measureStandalone(epoch) }
                    .onFailure { HydraLog.warn(AREA, "standalone measurement failed", it) }
                measuring.set(false)
                stopSelfResult(measurementStartId.get())
            }
            return
        }
        if (state != RuntimeState.RUNNING) return
        val settings = store.settings()
        val selected = store.selectedTag()
        observer.measure(
            group = io.hydrabox.core.config.SELECTOR_TAG,
            request = MeasureRequest(
                url = settings.urlTestUrl,
                timeoutMillis = settings.urlTestTimeoutSeconds * 1000,
                concurrency = settings.urlTestConcurrency,
                deadlineMillis = settings.urlTestTimeoutSeconds * 3000,
                priorityTag = selected,
                // The chosen call transport answers through its edge, not through an HTTP
                // round trip the group would raise a whole VK transport for: the workerless
                // question asks it separately, on its own thread, and its figure must not
                // be the group's to overwrite.
                excludeTag = selected?.takeIf { it in callTransportTags },
            ),
        )
        // Call transports are not members of the running group — the configuration does not
        // even carry them unless one is the chosen route — so the on-demand measurement asks
        // their edges the workerless question on its own thread, never the lifecycle's.
        val sweep = newEdgeSweep(
            runtimeGeneration = runtime.snapshot().runtimeGeneration.value,
            timeoutMillis = settings.urlTestTimeoutSeconds * 3_000L,
        )
        edgeProbes.execute { probeAllTurnEdges(sweep) }
        HydraLog.info(
            AREA,
            "measuring on demand, timeout ${settings.urlTestTimeoutSeconds}s, " +
                "concurrency ${settings.urlTestConcurrency}",
        )
    }

    /**
     * The workerless edge question, for every call transport in the catalogue.
     *
     * One socket per probe, protected from this service's own tunnel and bound to the
     * network underneath it, one attempt with a single repeat, and results cached per edge
     * and network generation for less than a minute — several profiles of one provider name
     * the same edge, and a handover invalidates every answer at once.
     *
     * One sweep at a time, and the sweep belongs to the session that started it: the
     * runtime and network generations are read once when it begins, every probe and the
     * publication check them, and a stop or a handover cancels the sweep outright — closing
     * the socket a waiting probe is blocked on — instead of letting its answers surface
     * under a session they were not measured for.
     */
    private fun probeAllTurnEdges(sweep: EdgeSweep) {
        val settings = runCatching { store.settings() }.getOrNull() ?: return
        if (sweep.cancelled) return
        val results = store.serverGroups().flatMap { it.servers }
            .filter { it.type.equals("call", ignoreCase = true) }
            .mapNotNull { target -> measureTurnEdge(target.id, settings, sweep) }
        if (results.isEmpty() || sweep.cancelled) return
        if (!sweep.stillCurrent(monitor)) return
        val current = runtime.snapshot()
        if (current.state != RuntimeState.RUNNING || current.runtimeGeneration.value != sweep.runtimeGeneration) return
        runtime.dispatch(RuntimeInput.Latencies(results, generation = sweep.runtimeGeneration))
    }

    /**
     * One call transport's edge, as a defined answer whatever it turns out to be.
     *
     * A server with no recorded edge, or one that only a TCP allocation ever reached, used
     * to be skipped silently — indistinguishable from a server nobody had measured. Both
     * are answers now, with their own words, and so are a silence from the edge itself and
     * a question the sweep's budget never reached. Only a cancelled sweep, or one whose
     * network has moved, has nothing to say — its results will not be published anyway.
     */
    private fun measureTurnEdge(
        tag: String,
        settings: io.hydrabox.core.settings.Settings,
        sweep: EdgeSweep,
    ): OutboundLatency? {
        if (sweep.cancelled || !sweep.stillCurrent(monitor)) return null
        // The budget ran out before this question was asked: an outcome in words, not a
        // silent skip that leaves the previous figure standing as though it were fresh.
        if (!sweep.withinDeadline()) return edgeOutcome(tag, EdgeLatencyStatus.NOT_MEASURED)
        // The live transport may have rotated its edge since readiness — a lane replaced
        // after a handover allocates again — and the record on file would name the old
        // one. Refreshed at the moment of asking, under the same attribution checks, the
        // question reaches the edge this transport is actually using. An offline sweep
        // has no live transport to refresh: the edge it asks about is the one already
        // filed, and no allocation is running that could have replaced it.
        val live = recordedTurnEdge
        if (live != null && live.first == tag && live.second == sweep.runtimeGeneration) {
            recordTurnEdgeFor(tag, sweep.runtimeGeneration)
        }
        val staleAfter = settings.urlTestIntervalSeconds * 1000L
        val endpoint = runCatching { store.turnEdge(tag) }.getOrNull()
            ?.let(TurnEdgeProbe::parseEndpoint)
        if (endpoint == null) {
            // No edge this profile's transport ever reached — an honest "not measured",
            // never a guess at an address obtained by performing a VK authorisation.
            return edgeOutcome(tag, EdgeLatencyStatus.NO_EDGE)
        }
        if (!endpoint.probeable) {
            // A TCP or TLS edge is recorded but does not answer a datagram: a fact about
            // the edge, not a failure to reach it.
            return edgeOutcome(tag, EdgeLatencyStatus.UNSUPPORTED)
        }
        val cacheKey = TurnEdgeProbe.cacheKey(endpoint, sweep.networkGeneration)
        val cached = edgeCache.get(cacheKey, System.currentTimeMillis())
        var rtt = cached?.rttMillis
        var observedAt = cached?.observedAtMillis ?: 0L
        if (cached == null) {
            // One answer per edge per sweep, absence included: an unreachable edge used to
            // be re-asked for every profile that named it, each time paying the full budget
            // of a question that had already gone unanswered.
            val remembered = sweep.answer(cacheKey)
            if (remembered != null) {
                rtt = remembered.rttMillis
                observedAt = remembered.observedAtMillis
            } else {
                val asked = System.currentTimeMillis()
                val measured = runCatching {
                    TurnEdgeProbe.probe(
                        endpoint,
                        openSocket = { turnEdgeSocket(sweep) },
                        resolve = { host -> resolveEdgeAddress(sweep, host) },
                        budgetMillis = sweep.remainingBudgetMillis().coerceAtMost(EDGE_PROBE_BUDGET_MILLIS),
                        isCancelled = { sweep.cancelled },
                    )
                }.getOrNull()
                // A cached or remembered answer keeps the time it was actually measured:
                // a fresh timestamp would call a forty-five-second-old round trip new.
                observedAt = asked
                sweep.remember(cacheKey, measured, asked)
                if (measured != null) edgeCache.put(cacheKey, measured, asked, System.currentTimeMillis())
                rtt = measured
            }
        }
        if (rtt != null) {
            HydraLog.info(AREA, "the TURN edge of $tag answered in $rtt ms")
            return OutboundLatency(
                tag = tag,
                delayMillis = rtt.toInt(),
                status = EdgeLatencyStatus.ANSWERED,
                observedAtMillis = observedAt,
                staleAfterMillis = staleAfter,
            )
        }
        // No round trip: the budget dying mid-question means the edge was never really
        // asked, and a cancelled sweep means the answer will not be published — only a
        // question that ran its course unanswered is a silence.
        return when {
            sweep.cancelled -> null
            !sweep.withinDeadline() -> edgeOutcome(tag, EdgeLatencyStatus.NOT_MEASURED)
            else -> OutboundLatency(
                tag = tag,
                delayMillis = 0,
                status = EdgeLatencyStatus.SILENT,
                observedAtMillis = observedAt,
                staleAfterMillis = staleAfter,
            )
        }
    }

    /** An edge answer that carries no figure: what happened, at the moment it was decided. */
    private fun edgeOutcome(tag: String, status: String): OutboundLatency =
        OutboundLatency(tag = tag, delayMillis = 0, status = status, observedAtMillis = System.currentTimeMillis())

    /**
     * A datagram socket that answers outside the tunnel, on the network underneath it. Only
     * this service can protect a socket from its own VPN, and a socket bound to the
     * network the sweep was started on follows the network the edge was measured on
     * instead of whatever the system routes by default. A socket that cannot be bound to
     * that network is no measurement of it: the answer is unavailable rather than a round
     * trip taken through whichever route was left.
     *
     * The socket is registered with the sweep before it is handed over, and a cancellation
     * that landed while it was being built closes it here: a cancel that only filters
     * results would leave a probe sending on a socket nobody can close any more.
     */
    private fun turnEdgeSocket(sweep: EdgeSweep): java.net.DatagramSocket {
        val socket = java.net.DatagramSocket()
        if (!protect(socket)) {
            socket.close()
            throw java.io.IOException("the edge socket could not be protected from the tunnel")
        }
        val network = sweep.network
        if (network != null) {
            val bound = runCatching { network.bindSocket(socket) }
            if (bound.isFailure) {
                socket.close()
                throw java.io.IOException(
                    "the edge socket would not bind to the current network",
                    bound.exceptionOrNull(),
                )
            }
        }
        sweep.liveSocket.set(socket)
        if (sweep.cancelled) {
            socket.close()
            sweep.liveSocket.compareAndSet(socket, null)
            throw java.io.IOException("the edge sweep was cancelled")
        }
        return socket
    }

    /**
     * Resolves an edge name on the network the sweep runs on.
     *
     * The system's resolver is never consulted as a fallback: with the tunnel up it answers
     * through the very tunnel the probe measures beside, and a failure of the underlying
     * network's DNS is a fact about that network, not a reason to ask another one. No
     * underlying network at all is the same refusal — there is no honest question to ask.
     * The probe bounds the blocking call itself under its budget, so a resolver that never
     * answers costs the sweep its patience, not its thread.
     */
    private fun resolveEdgeAddress(sweep: EdgeSweep, host: String): java.net.InetAddress? {
        val network = sweep.network ?: return null
        return runCatching { network.getAllByName(host).firstOrNull() }.getOrNull()
    }

    /**
     * Starts one edge sweep, ending any that is still in flight: repeated taps replace the
     * running pass instead of queueing another behind it.
     */
    private fun newEdgeSweep(runtimeGeneration: Long, timeoutMillis: Long): EdgeSweep {
        cancelEdgeSweep()
        val sweep = EdgeSweep(
            runtimeGeneration = runtimeGeneration,
            networkGeneration = monitor.networkGeneration,
            network = monitor.currentNetwork,
            deadlineElapsedRealtime = android.os.SystemClock.elapsedRealtime() + timeoutMillis,
        )
        edgeSweep.set(sweep)
        return sweep
    }

    /**
     * Ends the edge sweep in flight, if there is one. Idempotent, and safe from any thread:
     * the sweep's socket is closed, which unblocks the probe waiting on it, and the
     * cancelled flag stops the next profile from starting.
     */
    private fun cancelEdgeSweep() {
        edgeSweep.getAndSet(null)?.cancel()
    }

    /**
     * Cancels an offline sweep and lets go of the session it is inside.
     *
     * Called before anything else takes the core. The sweep owns a complete core instance per
     * server, and a real start beginning underneath it competes for the same memory, the same
     * platform DNS resources and, through the VK transport, the same flood-controlled join.
     */
    private fun cancelSweep() {
        cancelEdgeSweep()
        if (!measuring.get()) return
        sweepEpoch.incrementAndGet()
        // Closing the session in flight unblocks the probe already waiting on it; the epoch stops
        // the next one from starting.
        runCatching { probe.getAndSet(null)?.close() }
        HydraLog.info(AREA, "cancelling the offline measurement")
    }

    /** Measures every concrete outbound without opening a TUN or local inbound. */
    private fun measureStandalone(epoch: Int) {
        val settings = store.settings()
        val targets = store.serverGroups().flatMap { it.servers }
        if (targets.isEmpty()) return
        ensureLibboxSetup()
        // The edge questions of this sweep belong to a runtime that is not there: their
        // answers travel with generation zero, like every other result of this pass.
        val sweep = newEdgeSweep(
            runtimeGeneration = 0,
            timeoutMillis = settings.urlTestTimeoutSeconds * 3_000L,
        )
        // The pass itself — the order of its questions, when it ends, what it publishes —
        // lives in OfflineSweep, so the rules are testable without a core, the platform
        // or a device: this wiring only supplies the service's own answers.
        OfflineSweep(
            isCancelled = { sweepEpoch.get() != epoch || sweep.cancelled },
            networkStillCurrent = { sweep.stillCurrent(monitor) },
            measureEdge = { tag -> measureTurnEdge(tag, settings, sweep) },
            measureHttp = { tag -> measureHttpSession(tag, settings, epoch) },
            publishEdge = { runtime.dispatch(RuntimeInput.Latencies(it, generation = 0)) },
            publishHttp = { results ->
                // Generation zero: measured with no core behind it, so it belongs to no
                // session and is not discarded when one ends.
                runtime.dispatch(RuntimeInput.Latencies(results, generation = 0))
                HydraLog.info(AREA, "standalone measurement finished for ${results.size} servers")
            },
            reportStopped = { count ->
                HydraLog.info(AREA, "the offline measurement stopped after $count servers")
            },
        ).run(targets.map { OfflineSweep.Target(it.id, it.type) })
    }

    /** One server's standalone HTTP measurement: a whole core instance, leased and closed. */
    private fun measureHttpSession(
        tag: String,
        settings: io.hydrabox.core.settings.Settings,
        epoch: Int,
    ): OutboundLatency {
        val observedAt = System.currentTimeMillis()
        return runCatching {
            // A measurement session loads the same rule sets, so it takes its own lease for
            // as long as it runs instead of borrowing the tunnel's.
            val lease = AdBlockRuleSets.acquire(this)
            try {
                val content = store.generateConfig(tag, lease.paths.toRouteData())
                    ?: error("no usable server configuration")
                val session = Libbox.newStandaloneURLTestSession(AndroidVpnPlatform(this, monitor))
                probe.set(AutoCloseable { runCatching { session.close() } })
                try {
                    check(sweepEpoch.get() == epoch) { "measurement cancelled" }
                    session.run(
                        content,
                        io.hydrabox.core.config.SELECTOR_TAG,
                        tag,
                        settings.urlTestUrl,
                        settings.urlTestTimeoutSeconds * 1000,
                        settings.urlTestTimeoutSeconds * 3000,
                    )
                } finally {
                    probe.set(null)
                    runCatching { session.close() }
                }
            } finally {
                lease.close()
            }
        }.fold(
            onSuccess = { result ->
                OutboundLatency(
                    tag = tag,
                    delayMillis = result.delayMillis.toInt(),
                    status = result.status,
                    observedAtMillis = result.timeSeconds * 1000,
                    staleAfterMillis = settings.urlTestIntervalSeconds * 1000L,
                )
            },
            onFailure = { failure ->
                HydraLog.warn(AREA, "standalone probe for $tag failed", failure)
                OutboundLatency(
                    tag = tag,
                    delayMillis = 0,
                    status = "unavailable",
                    observedAtMillis = observedAt,
                    staleAfterMillis = settings.urlTestIntervalSeconds * 1000L,
                )
            },
        )
    }

    /**
     * Applies a newly chosen log level to a core that is already running.
     *
     * It used to take a reconnect, which is the wrong price for turning the detail up on a fault
     * that is happening right now — the reconnect ends the fault you were trying to look at. Three
     * things move together: what the core formats, what the journal keeps, and whether the log
     * stream is subscribed at all.
     *
     * "Off" is not a level — it is the instruction that removes the cost of formatting lines
     * nobody reads, so it travels as its own word and the core releases the factory it may
     * have built. Going the other way — off to any real level on a core that was started
     * with logging disabled — works only on a core that reports `runtime_log_level`; on an
     * older one the call is accepted and changes nothing, so a reconnect is asked for
     * instead of reporting a level that is not in effect.
     */
    private fun refreshSettings() {
        val settings = store.settings()
        liveTraffic = settings.statusNotificationEnabled
        trafficDisplay = settings.notificationTrafficDisplayMode
        val level = settings.logLevel
        journalFloor = when (level) {
            LogLevel.OFF, LogLevel.ERROR -> HydraLog.Level.ERROR
            LogLevel.TRACE, LogLevel.DEBUG -> HydraLog.Level.DEBUG
            LogLevel.INFO -> HydraLog.Level.INFO
            LogLevel.WARN -> HydraLog.Level.WARN
        }
        wantsCoreDetail = journalFloor <= HydraLog.Level.INFO
        val core = if (level == LogLevel.OFF) "off" else level.name.lowercase()
        val applied = coreLogFactoryAvailable && runCatching {
            requireNotNull(commandServer).setLogLevel(core)
        }.isSuccess
        val message = when {
            // An older core started with logging off stays off on its own: no factory was
            // ever built for it, and there is nothing to tell it.
            level == LogLevel.OFF && !coreLogFactoryAvailable -> "the core's log factory is off"
            level != LogLevel.OFF && !coreLogFactoryAvailable ->
                "core logging was disabled at start; reconnect to enable $core"
            applied && level == LogLevel.OFF -> "the core's log factory is off"
            applied -> "the core now logs at $core"
            else -> "the core would not take the level $core"
        }
        HydraLog.info(AREA, message)
        val state = runtime.snapshot().state
        runtime.onLifecycleThread {
            observer.setLogStream(commandServer != null && coreLogFactoryAvailable && (wantsCoreDetail || runtime.snapshot().state != RuntimeState.RUNNING))
        }
        if (state in setOf(RuntimeState.STOPPED, RuntimeState.FAILED)) {
            stopSelfResult(currentStartId.get())
        } else {
            startForeground(NOTIFICATION_ID, notification(state))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentStartId.set(startId)
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            ACTION_MEASURE -> measureNow(startId)
            ACTION_REFRESH_SETTINGS, ACTION_LOG_LEVEL -> runtime.onLifecycleThread(::refreshSettings)
            // Always-on VPN: the system starts the service itself, with the tunnel's own
            // action and no user in front of the screen. Ignoring it, as the alpha did, is
            // why "always on" turned the switch on and nothing happened.
            SERVICE_INTERFACE -> {
                HydraLog.info(AREA, "started by the system as an always-on tunnel")
                start()
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (VpnService.SERVICE_INTERFACE == intent?.action) super.onBind(intent) else endpoint

    override fun onDestroy() {
        HydraLog.sink = null
        // The lifecycle cleanup drains the journal before closing its database.
        cancelSweep()
        edgeProbes.shutdownNow()
        exitLookups.shutdown()
        idleWatch?.let { watch -> runCatching { unregisterReceiver(watch) } }
        idleWatch = null
        monitor.onChanged = null
        monitor.stop()
        runtime.close {
            try {
                stopRuntime()
            } finally {
                observer.close()
                journalWriter.execute {
                    try { flushJournal() } finally { store.close() }
                }
                journalWriter.shutdown()
            }
        }
        super.onDestroy()
    }

    /**
     * One line from the core, at the level the core gave it.
     *
     * The level arrives as a number on `LogEntry` and is used as such. It used to be discarded and
     * reconstructed by matching words in the message, which is not something the message promises
     * to contain — `WriteMessage` passes the level separately and the platform formatter need not
     * repeat it — so anything unrecognised was filed as debug and dropped at a warning threshold
     * after the crossing had already been paid for.
     *
     * The colour codes go: they are terminal escapes, and on a screen they are noise.
     */
    private fun recordCoreLine(coreLevel: Int, raw: String) {
        val level = HydraLog.levelOf(coreLevel)
        // A stream the client itself closed is the ordinary end of a request, and the core says so
        // at warning level — 4733 of those in one ten-minute run. Left at that level the warning
        // view is nothing but them, which is the same as having no warning view. Now that the level
        // is the core's own, this is a deliberate product judgement rather than a guess about text.
        val demoted = if (level == HydraLog.Level.WARN && BENIGN.any { raw.contains(it) }) {
            HydraLog.Level.DEBUG
        } else {
            level
        }
        if (demoted < journalFloor) return
        val clean = raw.replace(ANSI, "").trim()
        // What is left after the level and the uptime bracket is the line itself.
        HydraLog.core(demoted, clean.substringAfter("] ", clean))
    }

    private fun flushJournal() {
        journalPending.set(false)
        val batch = ArrayList<HydraLog.Entry>(64)
        while (true) {
            val entry = journal.poll() ?: break
            batch += entry
        }
        if (batch.isEmpty()) return
        // The store keeps its own budget per kind and trims inside the same transaction, so this
        // cap is only about how much one transaction is allowed to carry. At debug level the core
        // produces a few hundred lines a second, which is roughly what one flush window holds.
        runCatching { store.recordJournal(if (batch.size > JOURNAL_BATCH) batch.takeLast(JOURNAL_BATCH) else batch) }
    }

    /**
     * The system took the tunnel away: the person revoked the consent, or another VPN was
     * started. The file descriptor is dead either way, and a runtime that still says it is
     * connected is the worst of the possible answers.
     */
    override fun onRevoke() {
        HydraLog.warn(AREA, "the system revoked the tunnel")
        stopping = true
        cancelSweep()
        runtime.submit(RuntimeCommand.Stop)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    private fun start() {
        stopping = false
        // Nothing else may be inside the core when a real start begins.
        cancelSweep()
        startForeground(NOTIFICATION_ID, notification(RuntimeState.STARTING))
        // Proxy-only means no system tunnel: the core opens a local port and nothing else,
        // which is 1.x's `proxy_inbound_enabled` without `vpn_inbound_enabled`.
        val settings = store.settings()
        liveTraffic = settings.statusNotificationEnabled
        trafficDisplay = settings.notificationTrafficDisplayMode
        journalFloor = when (settings.logLevel) {
            LogLevel.OFF, LogLevel.ERROR -> HydraLog.Level.ERROR
            LogLevel.TRACE, LogLevel.DEBUG -> HydraLog.Level.DEBUG
            LogLevel.INFO -> HydraLog.Level.INFO
            LogLevel.WARN -> HydraLog.Level.WARN
        }
        wantsCoreDetail = journalFloor <= HydraLog.Level.INFO
        coreLogFactoryAvailable = CoreFeatures.runtimeLogLevel || settings.logLevel != LogLevel.OFF
        val mode = if (store.proxyOnly(settings)) RuntimeMode.PROXY else RuntimeMode.VPN
        runtime.submit(RuntimeCommand.Start(mode))
        startForeground(NOTIFICATION_ID, notification(runtime.snapshot().state))
    }

    private fun execute(effect: Effect) = when (effect) {
        is Effect.StartCore -> startCore(effect.commandGeneration)
        is Effect.StopCore -> {
            stopRuntime()
            runtime.dispatch(RuntimeInput.Released(effect.commandGeneration, true))
        }
        is Effect.SelectCoreOutbound -> {
            // The intention is recorded before the attempt, so the reconciliation that runs off
            // the core's own group message knows what to compare against even if this fails.
            val previous = wantedOutbound
            wantedOutbound = effect.selection.outboundId
            corrections.set(0)
            // A switch across the VK boundary cannot happen in place. In one direction the
            // chosen route is not in the running configuration at all; in the other it is, and
            // switching away would leave the transport's workers, calls and TURN allocations
            // alive until the whole core closes — hours after nobody chose it. The core starts
            // again instead: the connections are dropped once, with a warning on the screen,
            // rather than the transport living on behind a route nobody selected.
            val crossing = io.hydrabox.core.config.selectionCrossesCallBoundary(
                previous,
                effect.selection.outboundId,
                store::isCallTransport,
            )
            if (crossing) {
                restartForSelection()
            } else {
                // Applied inside the running core; a restart here would drop every connection.
                val applied = observer.select(effect.selection.groupId, effect.selection.outboundId)
                // Except for the one server the configuration deliberately does not carry: the VK
                // transport is left out until it is chosen, so choosing it is the one switch that
                // cannot happen in place.
                if (!applied) {
                    if (store.isCallTransport(effect.selection.outboundId)) {
                        restartForSelection()
                    } else {
                        // Not fatal and not silent: the store now says one thing and the core does
                        // another, and this is the line that says so. The group message will bring
                        // the core's answer round and the reconciliation corrects it from there.
                        HydraLog.warn(AREA, "the core refused the switch to ${effect.selection.outboundId}")
                    }
                }
            }
            Unit
        }
        is Effect.PublishNetwork -> {
            HydraLog.info(AREA, "publishing the default interface, generation ${effect.generation.value}")
            monitor.publishCurrent()
        }

        is Effect.RebindNetwork -> rebind(effect.generation)
    }

    /**
     * Starts the core again so a configuration that now carries the chosen server takes effect.
     *
     * A stop followed by a start is the reducer's own restart idiom: a start received while the
     * runtime is stopping is deferred and applied once the core has confirmed its release.
     */
    private fun restartForSelection() {
        HydraLog.info(AREA, "the chosen server is not in the running configuration, starting the core again")
        val settings = store.settings()
        val mode = if (store.proxyOnly(settings)) RuntimeMode.PROXY else RuntimeMode.VPN
        runtime.dispatch(RuntimeInput.Stop)
        runtime.dispatch(RuntimeInput.Start(mode))
    }

    /**
     * Follows the network under a running tunnel, in the order HydraCore requires.
     *
     * The generation is set in the core **before** the rebind, exactly as 1.x does
     * (`CoreRuntimeService.setNetworkGenerationBeforeRebind`). It is not bookkeeping: with the
     * generation left at zero, `QUICRelay.RebindNetwork` skips its own de-duplication, tears
     * down every lane on every notification, and each lane that comes back re-joins the VK
     * conversation. A few handovers later VK stops handing out TURN credentials
     * (`incomplete_credentials`) and the parasite outbound has no paths at all — a tunnel that
     * worked and then quietly stopped carrying traffic.
     *
     * The tunnel is also told which network it now sits on, so its own sockets follow the
     * handover instead of staying bound to an interface that has gone.
     */
    private fun rebind(generation: NetworkGeneration) {
        HydraLog.info(AREA, "rebinding the core to network generation ${generation.value}")
        // The order is 1.x's, step for step: the tunnel is told which network it sits on, the
        // core is told which generation it is now in, and only then is the interface published.
        val network = monitor.currentNetwork
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
            .onFailure { HydraLog.warn(AREA, "the tunnel would not take the underlying network", it) }
        runCatching { Libbox.hydraCoreSetNetworkGeneration(generation.value) }
            .onFailure { HydraLog.warn(AREA, "the core would not take the network generation", it) }
        // Deliberately not `resetNetwork()`. That closes every live connection and forces an
        // interface update on every endpoint whether or not the interface moved; 1.x never
        // calls it. Publishing the interface is what makes the core replace what has to be
        // replaced — the transport's own generation decides the rest.
        monitor.publishCurrent()
    }

    /** Which part of a start was in progress, so a failure can name the right kind of fault. */
    private enum class StartStage { PREPARE, CHECK, LAUNCH }

    private fun startCore(commandGeneration: Long) {
        // Every failure on this path has to reach the user as text. A crash here reads as
        // "it just does not work", which is the one report nobody can act on.
        HydraLog.info(AREA, "starting the core, command generation $commandGeneration")
        cancelSweep()
        var stage = StartStage.PREPARE
        // The chosen server, as of this configuration. The core will announce what it actually
        // picked and the two are compared from there; the cache file makes them disagree.
        wantedOutbound = store.selectedTag() ?: io.hydrabox.core.config.AUTO_TAG
        corrections.set(0)
        // Taken before the configuration is generated and handed over to the core only once the
        // core is running; on any failure below it is released in the tail of this function.
        var pendingLease: AdBlockRuleSets.Lease? = null
        val outcome = runCatching {
            callTransportTags = store.serverGroups()
                .flatMap { it.servers }
                .filter { it.type.equals("call", ignoreCase = true) }
                .mapTo(mutableSetOf()) { it.id }
            pendingLease = AdBlockRuleSets.acquire(this)
            val content = store.generateConfig(rules = pendingLease.paths.toRouteData())
                ?: error("no usable server in any subscription")
            ensureLibboxSetup()
            // 1.x turns this on by default and the setting was carried over without ever being
            // applied: the core then runs with Go's default GC target, and a `:core` process
            // that keeps more than it needs is a process Android reclaims — which reads as a
            // tunnel that dropped by itself.
            runCatching { Libbox.setMemoryLimit(store.settings().memoryLimitEnabled) }
                .onFailure { HydraLog.warn(AREA, "the core would not take the memory limit", it) }
            // checkConfig is what turns "the tunnel does not come up" into a sentence naming
            // the section the core refused, so its failure is logged before it is rethrown.
            stage = StartStage.CHECK
            runCatching { Libbox.checkConfig(content) }.onFailure {
                HydraLog.error(AREA, "the core refused the generated configuration", it)
            }.getOrThrow()
            stage = StartStage.LAUNCH
            stopRuntime()
            val server = Libbox.newCommandServer(handler, AndroidVpnPlatform(this, monitor))
            try {
                server.start()
                server.startOrReloadService(content, OverrideOptions().apply { runtimeGeneration = commandGeneration })
                commandServer = server
                // The core is running against these files now, so the lease becomes the
                // service's and is no longer this function's to release.
                ruleSetLease?.close()
                ruleSetLease = pendingLease
                pendingLease = null
            } catch (failure: Throwable) {
                runCatching { server.close() }
                throw failure
            }
        }
        pendingLease?.close()
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            HydraLog.error(AREA, "the core did not start", failure)
            store.recordStartFailure(HydraLog.describe(failure))
            stopRuntime()
            // The reason travels with the release. Without it the runtime reached FAILED with no
            // code at all, and the screen could only offer "something went wrong" and a retry.
            runtime.dispatch(
                RuntimeInput.Released(
                    commandGeneration = commandGeneration,
                    success = false,
                    failure = io.hydrabox.core.contract.RuntimeFailure(
                        domain = io.hydrabox.core.contract.FailureDomain.INTERNAL,
                        code = when (stage) {
                            // Nothing was started: what is stored could not be made into a
                            // configuration, or the core refused the one it was given.
                            StartStage.PREPARE, StartStage.CHECK ->
                                io.hydrabox.core.contract.HydraCoreErrorCode.CONFIG_INVALID_PLAN
                            StartStage.LAUNCH ->
                                io.hydrabox.core.contract.HydraCoreErrorCode.RUNTIME_CORE_DIED
                        },
                        retryable = true,
                    ),
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        store.clearStartFailure()
        // The first generation matters as much as the later ones: a lane that starts life on
        // generation zero cannot be superseded in order afterwards.
        runCatching { Libbox.hydraCoreSetNetworkGeneration(monitor.networkGeneration) }
        runCatching { setUnderlyingNetworks(monitor.currentNetwork?.let { arrayOf(it) }) }
        HydraLog.info(AREA, "the core accepted the configuration")
        runtime.dispatch(RuntimeInput.Launched(commandGeneration, commandGeneration))
        // Readiness is not announced here. The core answering on its command socket is what
        // says the tunnel is carrying traffic, and that arrives through the observer; the
        // start deadline is what catches a core that never gets there.
        // The core's transport health speaks for the VK transport only, so it is allowed to
        // decide the product state exactly when that is the route being used.
        // Open before the readiness wait, not after: the lines that explain a refusal are emitted
        // during the start, and the state-driven rule closes the stream again once it is running.
        observer.setLogStream(coreLogFactoryAvailable)
        observer.start(commandGeneration)
        startForeground(NOTIFICATION_ID, notification(runtime.snapshot().state))
    }

    /**
     * Stopping is not instantaneous and must not be cut short. Closing the core makes the
     * parasite outbound leave the VK conversation it joined; a process torn down before that
     * finishes leaves the slot occupied on VK's side, and the next connect is refused with
     * incomplete credentials. So the service stops itself only once the runtime has confirmed
     * the release — and the close runs off the main thread, because it takes seconds.
     */
    /**
     * Asks for the tunnel to be released, and returns.
     *
     * The close itself takes seconds — closing the core makes the parasite outbound leave the VK
     * conversation it joined, and a process torn down before that finishes leaves the slot occupied
     * on VK's side, so the next connect is refused with incomplete credentials. It runs on the
     * runtime's own lifecycle thread, which is also what serialises it against a start. Taking the
     * notification down and giving up started ownership happens where it belongs: on the snapshot
     * that says the runtime has actually stopped.
     */
    private fun stop() {
        stopping = true
        cancelSweep()
        runtime.submit(RuntimeCommand.Stop)
    }

    private fun stopRuntime() {
        observer.stop()
        recordedTurnEdge = null
        // Every path that ends a runtime ends its edge sweep too: a stop asked for over
        // binder goes through the reducer to here without ever passing through `stop()`,
        // and a sweep left running after that would publish under a session that is gone.
        cancelEdgeSweep()
        val server = commandServer
        commandServer = null
        try {
            server?.closeService()
        } finally {
            try {
                server?.close()
            } finally {
                // Nothing is reading the rule sets any more, so the next update may collect
                // this generation. Released last: the core must be gone first.
                ruleSetLease?.close()
                ruleSetLease = null
            }
        }
    }

    private fun ensureLibboxSetup(): Unit = synchronized(HydraVpnService::class.java) {
        if (libboxReady) return
        val base = filesDir.resolve("libbox-base").apply { mkdirs() }
        val work = filesDir.resolve("libbox-work").apply { mkdirs() }
        val temp = cacheDir.resolve("libbox-temp").apply { mkdirs() }
        Libbox.setup(SetupOptions().apply {
            basePath = base.path
            workingPath = work.path
            tempPath = temp.path
            // Not the build type: this makes the core hand every line it writes to the
            // platform as well, which is a call across the language boundary per line and a
            // per-packet cost while a tunnel is up. It is worth paying only when someone
            // asked to see those lines.
            debug = store.settings().logLevel in setOf(LogLevel.DEBUG, LogLevel.TRACE)
        })
        libboxReady = true
    }

    private fun notification(state: RuntimeState) = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).let { manager ->
        if (!channelReady) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    description = getString(R.string.notification_channel_detail)
                },
            )
            channelReady = true
        }
        val snapshot = runtime.snapshot()
        // A runtime phase is not a sentence for a person. `starting` and `running` are what
        // the reducer calls its states; the notification says what is true of the tunnel.
        val headline = when {
            state == RuntimeState.RUNNING && snapshot.transportHealth.isReady -> R.string.notification_connected
            state == RuntimeState.STOPPING -> R.string.notification_disconnecting
            state == RuntimeState.FAILED -> R.string.notification_failed
            else -> R.string.notification_connecting
        }
        val detail = if (snapshot.traffic.available && liveTraffic) {
            val speed = "↓ ${rate(snapshot.traffic.downlink)} ↑ ${rate(snapshot.traffic.uplink)}"
            val total = "Σ ↓ ${size(snapshot.traffic.downlinkTotal)} ↑ ${size(snapshot.traffic.uplinkTotal)}"
            when (trafficDisplay) {
                NotificationTrafficDisplayMode.SPEED -> "${getString(headline)} · $speed"
                NotificationTrafficDisplayMode.TOTAL -> "${getString(headline)} · $total"
                NotificationTrafficDisplayMode.BOTH -> "${getString(headline)} · $speed · $total"
            }
        } else {
            getString(headline)
        }
        android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(snapshot.selectedOutbounds.firstOrNull()?.outboundId ?: "HydraBox")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_hydrabox_status)
            .setOngoing(state != RuntimeState.STOPPED)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, RuntimeControlActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                android.app.Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_hydrabox_status),
                    getString(R.string.notification_disconnect),
                    android.app.PendingIntent.getService(
                        this,
                        1,
                        Intent(this, HydraVpnService::class.java).setAction(ACTION_STOP),
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .build()
    }

    private fun rate(value: Long): String = "${size(value)}/s"

    private fun size(value: Long): String {
        val units = listOf("B", "KiB", "MiB", "GiB")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit += 1
        }
        return "${amount.toLong()} ${units[unit]}"
    }

    /**
     * One pass of the workerless edge question, bound to the session it was asked in.
     *
     * The runtime and network generations and the network itself are read once, at the
     * start, so every probe and the publication compare against what the sweep was
     * actually started on rather than against whatever the moment happens to say. The
     * deadline is elapsed-realtime, so a wall clock moving under the sweep cannot shorten
     * or extend it. Answers are remembered per edge — a miss as firmly as a round trip,
     * because several profiles of one provider name the same edge — together with the
     * moment they were actually measured, and the socket a probe is waiting on is held
     * here so cancelling the sweep unblocks it.
     */
    private class EdgeSweep(
        val runtimeGeneration: Long,
        val networkGeneration: Long,
        val network: android.net.Network?,
        val deadlineElapsedRealtime: Long,
    ) {
        @Volatile var cancelled = false
        val liveSocket = java.util.concurrent.atomic.AtomicReference<java.net.DatagramSocket?>()
        private val answers = java.util.concurrent.ConcurrentHashMap<String, Answer>()

        class Answer(val rttMillis: Long?, val observedAtMillis: Long)

        fun answer(key: String): Answer? = answers[key]

        fun remember(key: String, rttMillis: Long?, observedAtMillis: Long) {
            answers[key] = Answer(rttMillis, observedAtMillis)
        }

        fun withinDeadline(): Boolean =
            android.os.SystemClock.elapsedRealtime() <= deadlineElapsedRealtime

        fun remainingBudgetMillis(): Long = deadlineElapsedRealtime - android.os.SystemClock.elapsedRealtime()

        /**
         * Whether the world the sweep was started in is still the world it is running in.
         * The network is compared by Android's own handle identity — the monitor may hand
         * out an equal `Network` in a new object, and that is the same network — while the
         * generation catches the case of a same-looking but re-validated one.
         */
        fun stillCurrent(monitor: DefaultNetworkMonitor): Boolean =
            !cancelled &&
                monitor.networkGeneration == networkGeneration &&
                monitor.currentNetwork == network

        fun cancel() {
            cancelled = true
            runCatching { liveSocket.getAndSet(null)?.close() }
        }
    }

    companion object {
        private const val AREA = "runtime"
        const val ACTION_START = "io.hydrabox.platform.android.START"
        const val ACTION_STOP = "io.hydrabox.platform.android.STOP"
        const val ACTION_MEASURE = "io.hydrabox.platform.android.MEASURE"
        const val ACTION_REFRESH_SETTINGS = "io.hydrabox.platform.android.REFRESH_SETTINGS"
        /** Compatibility alias for older callers; refresh now applies notification settings too. */
        const val ACTION_LOG_LEVEL = "io.hydrabox.platform.android.LOG_LEVEL"
        private const val CHANNEL_ID = "hydrabox-vpn"
        private const val NOTIFICATION_ID = 1

        /**
         * How long lines are collected before they are written. Long enough that a debug-level
         * burst is one transaction rather than a hundred, short enough that a person watching
         * the journal during a failure sees it happen.
         */
        private const val JOURNAL_FLUSH_MILLIS = 1200L
        private const val JOURNAL_BATCH = 600

        /**
         * The core's own exit lookup is budgeted at about four and a half seconds; the
         * binder reply waits a little past that, so a slow-but-real answer still crosses
         * and a stuck one does not hold the thread.
         */
        private const val EXIT_LOOKUP_DEADLINE_MILLIS = 8_000L

        /**
         * What one edge question may cost in full, name resolution included: the UDP
         * timeout bounds the exchange alone, and a resolver that never answers must not
         * hold the sweep — or the thread it runs on — for its own sake.
         */
        private const val EDGE_PROBE_BUDGET_MILLIS = 4_000L

        /**
         * How many times one core start may be told which outbound to use before we stop. A
         * disagreement the core will not take is a fact for the journal, not something to keep
         * retrying at whatever rate it publishes group updates.
         */
        private const val MAX_SELECTION_CORRECTIONS = 3


        /** Terminal colour codes, which a screen shows as square brackets and digits. */
        private val ANSI = Regex(Char(27) + """\[[0-9;]*m""")

        /**
         * Core lines that arrive as warnings and are not one: the normal end of a request, and a
         * status stream cancelled because we asked for it to be. Matched on the raw line, so the
         * text is the core's, not ours.
         */
        private val BENIGN = listOf(
            "canceled by local with error code 0",
            "context canceled",
        )
        @Volatile private var libboxReady = false
        @Volatile private var channelReady = false
    }
}
