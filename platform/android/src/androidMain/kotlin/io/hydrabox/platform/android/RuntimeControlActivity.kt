package io.hydrabox.platform.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.hydrabox.core.contract.CommandGeneration
import io.hydrabox.core.contract.EventSequence
import io.hydrabox.core.contract.NetworkGeneration
import io.hydrabox.core.contract.ProcessEpoch
import io.hydrabox.core.contract.RuntimeCommand
import io.hydrabox.core.contract.RuntimeEvent
import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeSnapshot
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.model.OperationError
import io.hydrabox.core.model.OperationState
import io.hydrabox.core.projection.AppReadModel
import io.hydrabox.core.projection.DiagnosticsSummary
import io.hydrabox.core.projection.Notice
import io.hydrabox.core.projection.RuleSetsSummary
import io.hydrabox.core.projection.ScreenProjection
import io.hydrabox.core.projection.Appearance
import io.hydrabox.core.projection.AppsMode
import io.hydrabox.core.projection.Language
import io.hydrabox.core.projection.DnsMode
import io.hydrabox.core.projection.ExitAddress
import io.hydrabox.core.projection.JournalEntry
import io.hydrabox.core.projection.JournalLevel
import io.hydrabox.core.projection.LogDetail
import io.hydrabox.core.projection.NotificationDetail
import io.hydrabox.core.projection.TlsFragmentation
import io.hydrabox.core.projection.TunnelStack
import io.hydrabox.core.settings.AppLanguage
import io.hydrabox.core.settings.DnsStrategy
import io.hydrabox.core.settings.NotificationTrafficDisplayMode
import io.hydrabox.core.settings.PerformanceMode
import io.hydrabox.core.settings.LogLevel
import io.hydrabox.core.settings.SplitRoutingMode
import io.hydrabox.core.settings.TlsFragmentationMode
import io.hydrabox.core.settings.TunStack
import io.hydrabox.core.settings.ThemeMode
import io.hydrabox.core.subscription.SourceFailure
import io.hydrabox.core.subscription.SubscriptionException
import io.hydrabox.ui.app.AppActions
import io.hydrabox.ui.app.AppNavigation
import io.hydrabox.ui.app.HydraApp
import io.hydrabox.ui.app.Route
import java.util.concurrent.Executors

/**
 * Composition root. It binds the runtime, combines the read models and hands them to the
 * projection. It holds no phase of its own and no branch on runtime state; what it does own
 * is what only the platform can know: the system consent and which thread a store call runs on.
 */
class RuntimeControlActivity : ComponentActivity() {
    private lateinit var store: AppStore
    private val io = Executors.newSingleThreadExecutor()

    /**
     * Reading the stored model has its own thread. Sharing [io] with imports and backups meant
     * a refresh queued behind a network fetch, and the screens showed the previous world for as
     * long as the fetch took — including the first run's welcome screen over a device that
     * already had a subscription.
     */
    private val reader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val navigation = AppNavigation()
    private var transport: BinderRuntimeTransport? = null
    private var subscription: AutoCloseable? = null

    private var snapshot by mutableStateOf(stoppedSnapshot())

    /** The stored half of the read model. Replaced wholesale by [refresh]. */
    private var stored by mutableStateOf(AppReadModel(runtime = stoppedSnapshot()))
    private var notice by mutableStateOf<Notice?>(null)
    private var busy by mutableStateOf<OperationState<Unit>>(OperationState.Idle)
    private var showApps by mutableStateOf(false)
    private var updatingRules by mutableStateOf(false)
    private var permissionMissing by mutableStateOf(false)

    /**
     * Where the tunnel comes out. Probed once per connection and on demand, never on the main
     * thread, and cleared the moment the tunnel goes down so a stale address cannot outlive it.
     */
    private var exit by mutableStateOf(ExitAddress())

    /**
     * Android 13 shows nothing without this, and the tunnel's notification is the only place
     * a person can see it is up — or press disconnect — without opening the app. The alpha
     * declared the permission and never asked for it.
     */
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        HydraLog.info(AREA, "notification permission ${if (granted) "granted" else "refused"}")
    }

    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            permissionMissing = false
            launch()
        } else {
            permissionMissing = true
            notice = Notice.VPN_PERMISSION_DENIED
        }
    }

    /** Held between the passphrase question and the file picker, and wiped straight after. */
    private var pendingPassphrase: CharArray? = null

    private val exportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> writeBackup(uri) }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        readBackup(uri)
    }

    private val sourceFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        background(Notice.SOURCE_ADDED) {
            val body = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("no_input_stream")
            store.addSubscription("", body)
        }
    }

    /** Whether the screens are on screen. The snapshot stream is worth paying for only then. */
    private var started = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binder ?: return
            BinderRuntimeTransport(binder).let { bound ->
                transport = bound
                observe(runCatching { bound.snapshot() }.getOrElse { stoppedSnapshot() })
                if (started) attach()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            subscription = null
            transport = null
            observe(stoppedSnapshot())
        }
    }

    /**
     * Starts the snapshot stream, which ticks once a second for as long as a tunnel is up.
     *
     * It is bound to visibility rather than to the process: an invisible activity kept
     * receiving every tick, waking the interface process sixty times a minute to write a state
     * nobody was drawing. Measured with the screen off, that made the interface process burn
     * more CPU than the core it was watching. The binding itself stays — it costs nothing idle,
     * and commands still need it — and the tunnel does not depend on either, because the
     * service is started with `startForegroundService`, not merely bound.
     */
    private fun attach() {
        val bound = transport ?: return
        if (subscription != null) return
        subscription = runCatching {
            bound.subscribe { event ->
                (event as? RuntimeEvent.Snapshot)?.let { update -> main.post { observe(update.snapshot) } }
            }
        }.getOrNull()
    }

    private fun detach() {
        runCatching { subscription?.close() }
        subscription = null
    }

    override fun onStart() {
        super.onStart()
        started = true
        // What happened while nobody was looking arrives in one read, before the stream resumes.
        transport?.let { bound -> runCatching { bound.snapshot() }.getOrNull()?.let(::observe) }
        attach()
    }

    override fun onStop() {
        started = false
        detach()
        super.onStop()
    }

    /** The only state derived from a transition rather than from the snapshot itself. */
    private fun observe(next: RuntimeSnapshot) {
        val wasUp = snapshot.state == RuntimeState.RUNNING
        val isUp = next.state == RuntimeState.RUNNING
        // A phase change can change what the stored half says — diagnostics, a source that
        // has just been rejected — while a traffic tick cannot.
        if (next.state != snapshot.state) refresh()
        if (isUp && !wasUp) probeExit()
        if (!isUp && wasUp) exit = ExitAddress()
        snapshot = next
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = AppStore(this)
        // Once, before the first frame: a read model that does not yet know whether the terms
        // were accepted would draw the first run at somebody who finished it months ago.
        stored = runCatching { load() }.getOrElse { stored }
        bindService(Intent(this, HydraVpnService::class.java), connection, Context.BIND_AUTO_CREATE)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!navigation.back()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
        setContent {
            HydraApp(
                state = ScreenProjection.project(readModel()),
                actions = actions(),
                navigation = navigation,
                versionName = BuildConfig.VERSION_NAME,
                coreVersion = BuildConfig.HYDRACORE_VERSION,
            )
        }
        askForNotifications()
        SubscriptionRefreshJob.schedule(this)
        refresh()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) runCatching { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    /**
     * What the app was opened with. A subscription is shared as a link far more often than it
     * is typed, so `hydrabox://import`, sing-box's own import scheme and a plain shared text
     * all end in the same place: the import that the sources screen runs.
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == ACTION_REQUEST_START) return prepareAndStart()
        val candidate = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::sourceOf)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            else -> null
        }?.takeIf(String::isNotEmpty) ?: return
        HydraLog.info(AREA, "opened with a link to import")
        navigation.open(Route.Sources)
        background(Notice.SOURCE_ADDED) { store.addSubscription("", candidate) }
    }

    /**
     * The link inside the link. Both import schemes wrap the real address in a `url`
     * parameter; anything else is passed through as it stands, because a share link is a
     * subscription too.
     */
    private fun sourceOf(uri: android.net.Uri): String? {
        val wrapped = runCatching { uri.getQueryParameter("url") }.getOrNull()?.trim()
        if (!wrapped.isNullOrEmpty()) return wrapped
        return uri.toString().takeUnless { it.startsWith("hydrabox://") || it.startsWith("sing-box://") }
    }

    override fun onDestroy() {
        detach()
        runCatching { unbindService(connection) }
        io.shutdown()
        reader.shutdown()
        super.onDestroy()
    }

    /**
     * What the screens read: the stored half, refreshed when something changes it, plus the
     * live half, which is free to change every second.
     *
     * The split is not tidiness. This was assembled inside the composition, so every
     * recomposition — one per traffic counter, once a second — opened the database, read the
     * settings and parsed every stored subscription document on the main thread.
     */
    private fun readModel(): AppReadModel = stored.copy(
        runtime = snapshot,
        apps = if (showApps) stored.apps else emptyList(),
        ruleSets = stored.ruleSets.copy(downloading = updatingRules),
        sourceOperation = busy,
        vpnPermissionMissing = permissionMissing,
        connectedForSeconds = snapshot.connectedAtElapsedRealtimeMillis
            ?.takeIf { snapshot.state == RuntimeState.RUNNING }
            ?.let { ((SystemClock.elapsedRealtime() - it) / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt() },
        notice = notice,
    )

    /**
     * Reloads the stored half off the main thread. Cheap to call; never called in a draw.
     *
     * Whether the app list is wanted is decided here, on the main thread, and carried into the
     * read: `load` used to enumerate every launchable package and resolve its label on every
     * refresh, and [readModel] threw the result away unless the picker happened to be open. That
     * is a full pass over `PackageManager` for each change of runtime state, for a list nobody
     * asked to see.
     */
    private fun refresh() {
        val withApps = showApps
        reader.execute {
            // A failure here is not cosmetic: the screens would keep showing the previous world,
            // which is how an import that worked looked like an import that did nothing.
            val next = runCatching { load(withApps) }
                .onFailure { HydraLog.error(AREA, "the read model could not be assembled", it) }
                .getOrNull() ?: return@execute
            main.post { stored = next }
        }
    }

    /**
     * Asks where the traffic comes out. The lookup runs on the reader thread rather than on
     * [io], which serialises imports and backups: an address is worth nothing if it arrives
     * after the network fetch that queued ahead of it.
     */
    private fun probeExit() {
        exit = exit.copy(checking = true)
        reader.execute {
            val answer = ExitAddressProbe.probe()
            main.post {
                exit = if (answer == null) {
                    ExitAddress(address = exit.address, countryCode = exit.countryCode, flag = exit.flag)
                } else {
                    ExitAddress(
                        address = answer.address,
                        countryCode = answer.countryCode,
                        flag = ExitAddressProbe.flagOf(answer.countryCode),
                    )
                }
                refresh()
            }
        }
    }

    private fun load(withApps: Boolean = false): AppReadModel {
        val settings = store.settings()
        return AppReadModel(
            runtime = snapshot,
            sources = store.summaries(),
            servers = store.serverGroups(),
            autoServer = store.autoServer(),
            selectedServerId = store.selectedTag(),
            settings = store.settingsSummary(settings),
            diagnostics = diagnostics(),
            apps = if (withApps) store.installedApps() else emptyList(),
            ruleSets = store.ruleSetStatus().let { status ->
                RuleSetsSummary(
                    available = status.available,
                    blockedDomains = status.blockedDomains,
                    updatedAt = status.updatedAtMillis?.let(::readableDate),
                )
            },
            exit = exit,
            legalAccepted = settings.acceptedLegalAtMillis != null,
        )
    }

    /**
     * Support facts and the journal. The generated configuration is deliberately not shown: it
     * carries server addresses and credentials, and this is the screen a person is most likely
     * to screenshot. Runtime phase, transport health, lanes and generations are gone — nobody
     * outside this codebase could act on them, and the journal says the same thing in words.
     */
    private fun diagnostics(): DiagnosticsSummary {
        val settings = store.settings()
        return DiagnosticsSummary(
            level = settings.logLevel.name.lowercase(),
            appVersion = BuildConfig.VERSION_NAME,
            coreVersion = BuildConfig.HYDRACORE_VERSION,
            activeServer = store.selectedTag(),
            dnsResolver = settings.dnsProxyResolver,
            journal = journal(),
        )
    }

    /**
     * The journal as one list: what this process logged, and what the core process wrote into
     * the shared database. Both are kept in arrival order, oldest first, the way 1.x's log page
     * read; consecutive identical lines are folded into one with a count.
     */
    private fun journal(): List<JournalEntry> {
        val local = HydraLog.entries().map { entry ->
            entry.atMillis to JournalEntry(
                id = 0,
                time = clock(entry.atMillis),
                level = journalLevel(entry.level.name),
                source = entry.area,
                message = entry.message,
            )
        }
        val remote = store.coreEvents().map { event ->
            event.atMillis to JournalEntry(
                id = 0,
                time = clock(event.atMillis),
                level = journalLevel(event.level),
                source = event.area,
                message = event.message,
            )
        }
        val folded = mutableListOf<JournalEntry>()
        (local + remote).sortedBy { it.first }.map { it.second }.forEach { entry ->
            val last = folded.lastOrNull()
            if (last != null && last.level == entry.level && last.source == entry.source && last.message == entry.message) {
                folded[folded.lastIndex] = last.copy(repeats = last.repeats + 1, time = entry.time)
            } else {
                folded += entry
            }
        }
        return folded.takeLast(JOURNAL_SHOWN)
            .mapIndexed { index, entry -> entry.copy(id = index.toLong()) }
    }

    private fun clock(atMillis: Long): String = if (atMillis <= 0) {
        "--:--:--"
    } else {
        java.time.Instant.ofEpochMilli(atMillis).atZone(java.time.ZoneId.systemDefault()).format(CLOCK)
    }

    private fun journalLevel(name: String) = when (name.lowercase()) {
        "error" -> JournalLevel.ERROR
        "warn" -> JournalLevel.WARN
        "debug" -> JournalLevel.DEBUG
        else -> JournalLevel.INFO
    }

    /** The structural facts, which are true whether or not anything went wrong. */
    private fun facts() = listOfNotNull(
        "core ${BuildConfig.HYDRACORE_VERSION}",
        "selected ${store.selectedTag() ?: "auto"}",
        snapshot.lastFailure?.let { "failure ${it.domain.name.lowercase()} / ${it.code.code}" },
        store.startFailure()?.let { "start rejected: $it" },
        store.importFailure()?.let { "import failed: $it" },
        store.summaries().mapNotNull { source -> store.parseError(source.id)?.let { "source rejected: $it" } }
            .firstOrNull(),
    )

    private fun actions() = AppActions(
        onConnect = ::prepareAndStart,
        onDisconnect = { send(RuntimeCommand.Stop) },
        onRetry = ::prepareAndStart,
        onGrantPermission = ::prepareAndStart,
        onAddSource = { name, source ->
            background(Notice.SOURCE_ADDED) { store.addSubscription(name, source) }
        },
        onAddSourceFromFile = {
            runCatching { sourceFile.launch(arrayOf("*/*")) }.onFailure { notice = Notice.OPERATION_FAILED }
        },
        onRefreshSource = { id -> background(Notice.SOURCE_UPDATED) { store.refreshSubscription(id) } },
        onRenameSource = { id, name -> background(null) { store.renameSubscription(id, name) } },
        onRemoveSource = { id -> background(Notice.SOURCE_REMOVED) { store.removeSubscription(id) } },
        onRefreshUsage = { id ->
            background(Notice.SOURCE_UPDATED) { store.refreshUsage(id) }
        },
        onSetSourceEnabled = { id, enabled ->
            reconnectAware { store.setSourceEnabled(id, enabled) }
        },
        onSelectServer = { id ->
            // Choosing a server while the tunnel is up switches it in place: nobody should
            // have to disconnect and reconnect to change where they are going.
            background(if (snapshot.state == RuntimeState.RUNNING) Notice.SERVER_SWITCHED else null) {
                store.select(id)
                if (snapshot.state == RuntimeState.RUNNING) {
                    main.post { send(RuntimeCommand.SelectOutbound(SELECT_GROUP, id)) }
                }
            }
        },
        onMeasure = { startService(measureIntent()) },
        onAcceptLegal = {
            background(null) {
                store.saveSettings(
                    store.settings().copy(
                        acceptedLegalVersion = LEGAL_VERSION,
                        acceptedLegalAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        },
        onSetEconomy = { economy ->
            reconnectAware {
                store.saveSettings(
                    store.settings().copy(
                        performanceMode = if (economy) PerformanceMode.ECONOMY else PerformanceMode.STANDARD,
                    ),
                )
            }
        },
        onSetNotificationDetail = { detail ->
            background(null) {
                store.saveSettings(
                    store.settings().copy(
                        statusNotificationEnabled = detail != NotificationDetail.OFF,
                        notificationTrafficDisplayMode = when (detail) {
                            NotificationDetail.TOTAL -> NotificationTrafficDisplayMode.TOTAL
                            NotificationDetail.BOTH -> NotificationTrafficDisplayMode.BOTH
                            // Off keeps whatever was chosen, so turning the line back on does
                            // not silently pick a different one.
                            NotificationDetail.OFF -> store.settings().notificationTrafficDisplayMode
                            NotificationDetail.SPEED -> NotificationTrafficDisplayMode.SPEED
                        },
                    ),
                )
            }
        },
        onSetBlockLeaks = { enabled -> reconnectAware { store.saveSettings(store.settings().copy(blockLeaks = enabled)) } },
        onSetBypassLocalNetwork = { enabled ->
            reconnectAware { store.saveSettings(store.settings().copy(bypassLocalNetwork = enabled)) }
        },
        onSetAppsMode = { mode ->
            reconnectAware {
                store.saveSettings(
                    store.settings().copy(
                        splitRoutingMode = when (mode) {
                            AppsMode.OFF -> SplitRoutingMode.OFF
                            AppsMode.BYPASS_SELECTED -> SplitRoutingMode.BYPASS_SELECTED
                            AppsMode.ONLY_SELECTED -> SplitRoutingMode.ONLY_SELECTED
                        },
                    ),
                )
            }
        },
        onSetAdBlock = { enabled ->
            reconnectAware { store.saveSettings(store.settings().copy(adBlockEnabled = enabled)) }
        },
        onUpdateRuleSets = {
            // The list is a few megabytes and is compiled on the device, so it runs on the io
            // thread with its own busy state rather than the shared one.
            updatingRules = true
            io.execute {
                val failure = runCatching { store.updateRuleSets() }.exceptionOrNull()
                main.post {
                    updatingRules = false
                    notice = if (failure == null) Notice.RULES_UPDATED else Notice.RULES_FAILED
                    refresh()
                }
            }
        },
        onSetTcpFastOpen = { enabled ->
            reconnectAware { store.saveSettings(store.settings().copy(tcpFastOpen = enabled)) }
        },
        onSetTcpMultiPath = { enabled ->
            reconnectAware { store.saveSettings(store.settings().copy(tcpMultiPath = enabled)) }
        },
        onSetProxyOnly = { proxyOnly ->
            reconnectAware {
                store.saveSettings(
                    store.settings().copy(
                        proxyInboundEnabled = proxyOnly,
                        vpnInboundEnabled = !proxyOnly,
                    ),
                )
            }
        },
        onSetProxyPort = { port ->
            reconnectAware { store.saveSettings(store.settings().copy(proxyMixedPort = port)) }
        },
        onSetProxyAllowLan = { allow ->
            reconnectAware { store.saveSettings(store.settings().copy(proxyAllowLan = allow)) }
        },
        onSetStrictRoute = { enabled -> reconnectAware { store.saveSettings(store.settings().copy(vpnStrictRoute = enabled)) } },
        onSetStack = { stack ->
            reconnectAware {
                store.saveSettings(
                    store.settings().copy(
                        vpnTunStack = when (stack) {
                            TunnelStack.SYSTEM -> TunStack.SYSTEM
                            TunnelStack.GVISOR -> TunStack.GVISOR
                            TunnelStack.MIXED -> TunStack.MIXED
                        },
                    ),
                )
            }
        },
        onSetFragmentation = { mode ->
            reconnectAware {
                store.saveSettings(
                    store.settings().copy(
                        tlsFragmentationMode = when (mode) {
                            TlsFragmentation.OFF -> TlsFragmentationMode.DISABLED
                            TlsFragmentation.RECORD -> TlsFragmentationMode.RECORD
                            TlsFragmentation.FRAGMENT -> TlsFragmentationMode.FRAGMENT
                        },
                    ),
                )
            }
        },
        // Not `reconnectAware` any more: the core takes a new level on the running instance, so
        // the promise of a reconnect would be a lie and the reconnect would end the fault someone
        // turned the detail up to look at.
        onSetLogDetail = { detail ->
            background(null) {
                store.saveSettings(
                    store.settings().copy(
                        logLevel = when (detail) {
                            LogDetail.OFF -> LogLevel.OFF
                            LogDetail.TRACE -> LogLevel.TRACE
                            LogDetail.DEBUG -> LogLevel.DEBUG
                            LogDetail.INFO -> LogLevel.INFO
                            LogDetail.WARN -> LogLevel.WARN
                            LogDetail.ERROR -> LogLevel.ERROR
                        },
                    ),
                )
                if (snapshot.state != RuntimeState.STOPPED) {
                    main.post {
                        runCatching {
                            startService(
                                Intent(this@RuntimeControlActivity, HydraVpnService::class.java)
                                    .setAction(HydraVpnService.ACTION_LOG_LEVEL),
                            )
                        }
                    }
                }
            }
        },
        onSetAppearance = { appearance ->
            background(null) {
                store.saveSettings(
                    store.settings().copy(
                        themeMode = when (appearance) {
                            Appearance.SYSTEM -> ThemeMode.SYSTEM
                            Appearance.LIGHT -> ThemeMode.LIGHT
                            Appearance.DARK -> ThemeMode.DARK
                        },
                    ),
                )
            }
        },
        onSetDynamicColour = { dynamic ->
            background(null) { store.saveSettings(store.settings().copy(dynamicColour = dynamic)) }
        },
        onSetLanguage = ::applyLanguage,
        onSetProxyDns = { value -> reconnectAware { store.saveSettings(store.settings().copy(dnsProxyResolver = value)) } },
        onSetDirectDns = { value -> reconnectAware { store.saveSettings(store.settings().copy(dnsDirectResolver = value)) } },
        onSetBootstrapDns = { value ->
            reconnectAware { store.saveSettings(store.settings().copy(bootstrapDnsResolver = value)) }
        },
        onSetDnsMode = { mode ->
            reconnectAware {
                store.saveSettings(
                    store.settings().copy(
                        dnsStrategy = when (mode) {
                            DnsMode.AUTO -> DnsStrategy.AUTO
                            DnsMode.IPV4 -> DnsStrategy.IPV4_ONLY
                            DnsMode.IPV6 -> DnsStrategy.IPV6_ONLY
                        },
                    ),
                )
            }
        },
        onSetFakeIp = { enabled ->
            reconnectAware { store.saveSettings(store.settings().copy(fakeIpEnabled = enabled)) }
        },
        onSetInterruptConnections = { enabled ->
            reconnectAware { store.saveSettings(store.settings().copy(interruptExistingConnections = enabled)) }
        },
        onSetMtu = { mtu -> reconnectAware { store.saveSettings(store.settings().copy(vpnMtu = mtu)) } },
        onClearJournal = {
            HydraLog.clear()
            background(null) { store.clearCoreEvents() }
        },
        onToggleApp = { packageName -> reconnectAware { store.toggleExcludedApp(packageName) } },
        onLoadApps = { showApps = true; refresh() },
        onRefreshExit = ::probeExit,
        onExportDiagnostics = ::shareDiagnostics,
        onExportBackup = { passphrase ->
            pendingPassphrase = passphrase.toCharArray()
            runCatching { exportFile.launch(BACKUP_FILE_NAME) }.onFailure { failBackup() }
        },
        onImportBackup = { passphrase ->
            pendingPassphrase = passphrase.toCharArray()
            runCatching { importFile.launch(arrayOf("*/*")) }.onFailure { failBackup() }
        },
        onResetSettings = { background(Notice.SETTINGS_RESET) { store.resetSettings() } },
        onNoticeShown = { notice = null },
    )

    /**
     * The language is the system's business: Android 13 keeps a per-app locale, shows it in
     * its own settings and survives reinstalls of the app's preferences. Storing our own
     * copy as well keeps the chosen value visible in the interface.
     */
    private fun applyLanguage(language: Language) {
        val stored = when (language) {
            Language.SYSTEM -> AppLanguage.SYSTEM
            Language.RUSSIAN -> AppLanguage.RUSSIAN
            Language.ENGLISH -> AppLanguage.ENGLISH
        }
        background(null) { store.saveSettings(store.settings().copy(language = stored)) }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val tags = when (language) {
            Language.SYSTEM -> ""
            Language.RUSSIAN -> "ru"
            Language.ENGLISH -> "en"
        }
        runCatching {
            getSystemService(android.app.LocaleManager::class.java)
                ?.applicationLocales = android.os.LocaleList.forLanguageTags(tags)
        }
    }

    private fun readableDate(millis: Long): String = java.time.Instant.ofEpochMilli(millis)
        .toString().substringBefore('T')

    /** A setting that only the next tunnel will read says so instead of pretending to apply. */
    private fun reconnectAware(block: () -> Unit) = background(
        if (snapshot.state == RuntimeState.RUNNING) Notice.SETTINGS_NEED_RECONNECT else null,
        block,
    )

    private fun background(success: Notice?, block: () -> Unit) {
        busy = OperationState.Running
        notice = null
        io.execute {
            val failure = runCatching(block).exceptionOrNull()
            runCatching { store.rememberImportFailure(failure) }
            main.post {
                busy = failure?.let { OperationState.Failed(OperationError(it.message ?: "failed")) }
                    ?: OperationState.Idle
                notice = if (failure != null) noticeOf(failure) else success
                refresh()
            }
        }
    }

    /**
     * A typed failure becomes the sentence that matches it. 1.x distinguished seventeen
     * reasons a source could not be read, and told the person which one it was; a single
     * "something went wrong" for all of them is what this avoids.
     */
    private fun noticeOf(failure: Throwable): Notice = when ((failure as? SubscriptionException)?.failure) {
        SourceFailure.TIMEOUT, SourceFailure.NO_NETWORK, SourceFailure.TLS -> Notice.SOURCE_UNREACHABLE
        SourceFailure.HTTP_STATUS, SourceFailure.TOO_MANY_REDIRECTS -> Notice.SOURCE_REJECTED
        SourceFailure.HTML_RESPONSE -> Notice.SOURCE_NOT_A_SUBSCRIPTION
        SourceFailure.CREDENTIALS_REQUIRE_HTTPS -> Notice.SOURCE_INSECURE_LINK
        SourceFailure.UNSAFE_REDIRECT -> Notice.SOURCE_UNSAFE_REDIRECT
        SourceFailure.ENCRYPTED_WITHOUT_KEY -> Notice.SOURCE_NEEDS_KEY
        SourceFailure.CORE_TOO_OLD -> Notice.SOURCE_NEEDS_NEWER_APP
        SourceFailure.TOO_LARGE -> Notice.SOURCE_TOO_LARGE
        SourceFailure.EMPTY_RESPONSE, SourceFailure.NO_USABLE_SERVERS -> Notice.SOURCE_EMPTY
        SourceFailure.EXPIRED -> Notice.SOURCE_FAILED
        SourceFailure.INVALID_URL, SourceFailure.INVALID_CONTENT, SourceFailure.UNKNOWN -> Notice.SOURCE_FAILED
        null -> Notice.OPERATION_FAILED
    }

    private fun prepareAndStart() {
        busy = OperationState.Running
        io.execute {
            val ready = runCatching { store.generateConfig() != null }.getOrDefault(false)
            main.post {
                busy = OperationState.Idle
                if (!ready) {
                    notice = Notice.SOURCE_EMPTY
                    return@post
                }
                notice = null
                // The system consent is about a tunnel. A local proxy port does not need
                // one, so proxy-only starts without asking for it.
                if (store.proxyOnly()) launch() else VpnService.prepare(this)?.let(permission::launch) ?: launch()
            }
        }
    }

    private fun launch() {
        notice = null
        permissionMissing = false
        startForegroundService(Intent(this, HydraVpnService::class.java).setAction(HydraVpnService.ACTION_START))
    }

    private fun measureIntent() =
        Intent(this, HydraVpnService::class.java).setAction(HydraVpnService.ACTION_MEASURE)

    /**
     * The log leaves the app as text through the system share sheet: no file provider, no
     * storage permission, and the person sees exactly what is being sent before sending it.
     */
    private fun shareDiagnostics() {
        // The same lines the journal shows, in the same order, so a report and a screenshot
        // never disagree about what happened.
        val body = (facts() + journal().map { entry -> "${entry.time} ${entry.level.name.lowercase()} ${entry.source}: ${entry.message}" })
            .joinToString(separator = System.lineSeparator())
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "HydraBox diagnostics")
            .putExtra(Intent.EXTRA_TEXT, body)
        runCatching { startActivity(Intent.createChooser(send, null)) }
            .onFailure { notice = Notice.OPERATION_FAILED }
    }

    /**
     * The document is built and encrypted on the io thread: deriving the key from the
     * passphrase is deliberately slow, and doing it on the main thread would freeze the
     * screen for as long as it takes.
     */
    private fun writeBackup(uri: android.net.Uri?) {
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        if (uri == null || passphrase == null) return failBackup(silent = uri == null)
        busy = OperationState.Running
        io.execute {
            val failure = runCatching {
                val bytes = BackupFile.encrypt(store.exportDocument(), passphrase)
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no_output_stream")
            }.exceptionOrNull()
            passphrase.fill(BLANK)
            main.post {
                busy = OperationState.Idle
                notice = if (failure == null) Notice.BACKUP_EXPORTED else Notice.BACKUP_FAILED
            }
        }
    }

    private fun readBackup(uri: android.net.Uri?) {
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        if (uri == null || passphrase == null) return failBackup(silent = uri == null)
        busy = OperationState.Running
        io.execute {
            val failure = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("no_input_stream")
                store.importDocument(BackupFile.decrypt(bytes, passphrase))
            }.exceptionOrNull()
            passphrase.fill(BLANK)
            main.post {
                busy = OperationState.Idle
                notice = if (failure == null) Notice.BACKUP_IMPORTED else Notice.BACKUP_FAILED
                refresh()
            }
        }
    }

    /** A cancelled picker is not a failure; a missing passphrase is. */
    private fun failBackup(silent: Boolean = false) {
        pendingPassphrase = null
        if (!silent) notice = Notice.BACKUP_FAILED
    }

    private fun send(command: RuntimeCommand) {
        val bound = transport
        if (bound == null) {
            notice = Notice.OPERATION_FAILED
            return
        }
        runCatching { bound.submit(command) }.exceptionOrNull()?.let { notice = Notice.OPERATION_FAILED }
    }

    private fun stoppedSnapshot() = RuntimeSnapshot(
        processEpoch = ProcessEpoch("ui"),
        commandGeneration = CommandGeneration(0),
        runtimeGeneration = RuntimeGeneration(0),
        networkGeneration = NetworkGeneration(0),
        lastEventSequence = EventSequence(0),
        state = RuntimeState.STOPPED,
        mode = RuntimeMode.VPN,
    )

    companion object {
        private const val AREA = "ui"

        /** How much of each journal the screen shows before it stops being readable. */
        /** As many lines as a person will actually scroll, newest last. */
        private const val JOURNAL_SHOWN = 300
        private val CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

        /** Sent by the Quick Settings tile, which cannot show the VPN consent dialog. */
        const val ACTION_REQUEST_START = "io.hydrabox.platform.android.REQUEST_START"

        /** The selector group in the generated configuration. */
        private const val SELECT_GROUP = "select"
        private const val LEGAL_VERSION = "1"
        private const val BACKUP_FILE_NAME = "hydrabox-backup.hbk"

        /** Wiping a passphrase array means overwriting it, not dropping the reference. */
        private const val BLANK = '\u0000'
    }
}
