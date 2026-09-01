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
import io.hydrabox.core.projection.LogDetail
import io.hydrabox.core.projection.TlsFragmentation
import io.hydrabox.core.projection.TunnelStack
import io.hydrabox.core.settings.AppLanguage
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
import java.util.concurrent.Executors

/**
 * Composition root. It binds the runtime, combines the read models and hands them to the
 * projection. It holds no phase of its own and no branch on runtime state; what it does own
 * is what only the platform can know: the system consent, how long the tunnel has been up,
 * and which thread a store call runs on.
 */
class RuntimeControlActivity : ComponentActivity() {
    private lateinit var store: AppStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val navigation = AppNavigation()
    private var transport: BinderRuntimeTransport? = null
    private var subscription: AutoCloseable? = null

    private var snapshot by mutableStateOf(stoppedSnapshot())
    private var revision by mutableStateOf(0)
    private var notice by mutableStateOf<Notice?>(null)
    private var busy by mutableStateOf<OperationState<Unit>>(OperationState.Idle)
    private var showApps by mutableStateOf(false)
    private var updatingRules by mutableStateOf(false)
    private var permissionMissing by mutableStateOf(false)

    /** When the tunnel started carrying traffic, on the clock that survives sleep. */
    private var connectedSinceUptime: Long? = null

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binder ?: return
            BinderRuntimeTransport(binder).let { bound ->
                transport = bound
                observe(runCatching { bound.snapshot() }.getOrElse { stoppedSnapshot() })
                subscription = runCatching {
                    bound.subscribe { event ->
                        (event as? RuntimeEvent.Snapshot)?.let { update -> main.post { observe(update.snapshot) } }
                    }
                }.getOrNull()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            subscription = null
            transport = null
            observe(stoppedSnapshot())
        }
    }

    /** The only state derived from a transition rather than from the snapshot itself. */
    private fun observe(next: RuntimeSnapshot) {
        val wasUp = snapshot.state == RuntimeState.RUNNING
        val isUp = next.state == RuntimeState.RUNNING
        connectedSinceUptime = when {
            isUp && !wasUp -> SystemClock.elapsedRealtime()
            isUp -> connectedSinceUptime
            else -> null
        }
        snapshot = next
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = AppStore(this)
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
        if (intent?.action == ACTION_REQUEST_START) prepareAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_REQUEST_START) prepareAndStart()
    }

    override fun onDestroy() {
        runCatching { subscription?.close() }
        runCatching { unbindService(connection) }
        io.shutdown()
        super.onDestroy()
    }

    private fun readModel(): AppReadModel {
        revision.let { }
        val settings = store.settings()
        return AppReadModel(
            runtime = snapshot,
            sources = store.summaries(),
            servers = store.serverGroups(),
            autoServer = store.autoServer(),
            selectedServerId = store.selectedTag(),
            settings = store.settingsSummary(settings),
            diagnostics = diagnostics(),
            apps = if (showApps) store.installedApps() else emptyList(),
            ruleSets = store.ruleSetStatus().let { status ->
                RuleSetsSummary(
                    available = status.available,
                    blockedDomains = status.blockedDomains,
                    updatedAt = status.updatedAtMillis?.let(::readableDate),
                    downloading = updatingRules,
                )
            },
            sourceOperation = busy,
            legalAccepted = settings.acceptedLegalAtMillis != null,
            vpnPermissionMissing = permissionMissing,
            connectedForSeconds = connectedSinceUptime
                ?.let { ((SystemClock.elapsedRealtime() - it) / 1000).toInt() },
            notice = notice,
        )
    }

    /**
     * Structural facts only. The generated configuration is deliberately not shown: it
     * carries server addresses and credentials, and a diagnostics screen is the one place a
     * person is most likely to screenshot.
     */
    private fun diagnostics() = DiagnosticsSummary(
        level = "warn",
        recentEvents = listOfNotNull(
            "core ${BuildConfig.HYDRACORE_VERSION}",
            "selected ${store.selectedTag() ?: "auto"}",
            "generations c${snapshot.commandGeneration.value} r${snapshot.runtimeGeneration.value} n${snapshot.networkGeneration.value}",
            "lanes ${snapshot.transportHealth.activeLanes}, ready ${snapshot.transportHealth.isReady}",
            snapshot.lastFailure?.let { "failure ${it.domain.name.lowercase()} / ${it.code.code}" },
            store.startFailure()?.let { "start rejected: $it" },
            store.summaries().mapNotNull { source -> store.parseError(source.id)?.let { "source rejected: $it" } }
                .firstOrNull(),
        ),
        exportState = "idle",
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
        onToggleNotification = { enabled ->
            background(null) { store.saveSettings(store.settings().copy(statusNotificationEnabled = enabled)) }
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
                    revision += 1
                }
            }
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
        onSetLogDetail = { detail ->
            reconnectAware {
                store.saveSettings(
                    store.settings().copy(
                        logLevel = when (detail) {
                            LogDetail.TRACE -> LogLevel.TRACE
                            LogDetail.DEBUG -> LogLevel.DEBUG
                            LogDetail.INFO -> LogLevel.INFO
                            LogDetail.WARN -> LogLevel.WARN
                            LogDetail.ERROR -> LogLevel.ERROR
                        },
                    ),
                )
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
        onSetLanguage = ::applyLanguage,
        onSetProxyDns = { value -> reconnectAware { store.saveSettings(store.settings().copy(dnsProxyResolver = value)) } },
        onSetDirectDns = { value -> reconnectAware { store.saveSettings(store.settings().copy(dnsDirectResolver = value)) } },
        onSetMtu = { mtu -> reconnectAware { store.saveSettings(store.settings().copy(vpnMtu = mtu)) } },
        onToggleApp = { packageName -> reconnectAware { store.toggleExcludedApp(packageName) } },
        onLoadApps = { showApps = true; revision += 1 },
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
            main.post {
                busy = failure?.let { OperationState.Failed(OperationError(it.message ?: "failed")) }
                    ?: OperationState.Idle
                notice = if (failure != null) noticeOf(failure) else success
                revision += 1
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
        val body = diagnostics().recentEvents.joinToString(separator = System.lineSeparator())
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
                revision += 1
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
