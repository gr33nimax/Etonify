package io.hydrabox.platform.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import io.hydrabox.core.contract.RuntimeCommand
import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.runtime.Effect
import io.hydrabox.core.runtime.RuntimeInput

class HydraVpnService : VpnService() {
    private lateinit var runtime: AndroidRuntime
    private lateinit var endpoint: BinderRuntimeEndpoint
    private lateinit var store: AppStore
    private lateinit var monitor: DefaultNetworkMonitor
    private lateinit var observer: CoreObserver
    private var commandServer: CommandServer? = null

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        monitor = DefaultNetworkMonitor(this).apply { start() }
        observer = CoreObserver(dispatch = { input -> runtime.dispatch(input) })
        runtime = AndroidRuntime(::execute)
        endpoint = BinderRuntimeEndpoint(runtime)
        runtime.subscribe { event ->
            if (event is io.hydrabox.core.contract.RuntimeEvent.Snapshot && event.snapshot.state != RuntimeState.STOPPED) {
                startForeground(NOTIFICATION_ID, notification(event.snapshot.state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            ACTION_MEASURE -> observer.measure(io.hydrabox.core.config.SELECTOR_TAG)
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (VpnService.SERVICE_INTERFACE == intent?.action) super.onBind(intent) else endpoint

    override fun onDestroy() {
        stopRuntime()
        monitor.stop()
        super.onDestroy()
    }

    private fun start() {
        startForeground(NOTIFICATION_ID, notification(RuntimeState.STARTING))
        runtime.submit(RuntimeCommand.Start(RuntimeMode.VPN))
        startForeground(NOTIFICATION_ID, notification(runtime.snapshot().state))
    }

    private fun execute(effect: Effect) = when (effect) {
        is Effect.StartCore -> startCore(effect.commandGeneration)
        is Effect.StopCore -> {
            stopRuntime()
            runtime.dispatch(RuntimeInput.Released(effect.commandGeneration, true))
        }
        is Effect.SelectCoreOutbound -> {
            // Applied inside the running core; a restart here would drop every connection.
            observer.select(effect.selection.groupId, effect.selection.outboundId)
            Unit
        }
        is Effect.ReloadCore -> {
            observer.reload()
            Unit
        }
        is Effect.RebindNetwork -> {
            runCatching { commandServer?.resetNetwork() }
            Unit
        }
    }

    private fun startCore(commandGeneration: Long) {
        // Every failure on this path has to reach the user as text. A crash here reads as
        // "it just does not work", which is the one report nobody can act on.
        val outcome = runCatching {
            val content = store.generateConfig() ?: error("no usable server in any subscription")
            ensureLibboxSetup()
            Libbox.checkConfig(content)
            stopRuntime()
            commandServer = Libbox.newCommandServer(handler, AndroidVpnPlatform(this, monitor)).also {
                it.start()
                it.startOrReloadService(content, OverrideOptions())
            }
        }
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            store.recordStartFailure(failure.message ?: failure::class.java.simpleName)
            stopRuntime()
            runtime.dispatch(RuntimeInput.Released(commandGeneration, false))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        store.clearStartFailure()
        observer.start()
        runtime.dispatch(RuntimeInput.Launched(commandGeneration, commandGeneration))
        runtime.dispatch(RuntimeInput.Health(commandGeneration, commandGeneration, TransportHealth(applicable = false, runtimeGeneration = RuntimeGeneration(commandGeneration))))
        startForeground(NOTIFICATION_ID, notification(runtime.snapshot().state))
    }

    private fun stop() {
        runtime.submit(RuntimeCommand.Stop)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRuntime() {
        observer.stop()
        commandServer?.closeService()
        commandServer?.close()
        commandServer = null
    }

    private fun ensureLibboxSetup() = synchronized(HydraVpnService::class.java) {
        if (libboxReady) return
        val base = filesDir.resolve("libbox-base").apply { mkdirs() }
        val work = filesDir.resolve("libbox-work").apply { mkdirs() }
        val temp = cacheDir.resolve("libbox-temp").apply { mkdirs() }
        Libbox.setup(SetupOptions().apply {
            basePath = base.path
            workingPath = work.path
            tempPath = temp.path
            debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        })
        libboxReady = true
    }

    private fun notification(state: RuntimeState) = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).let { manager ->
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HydraBox VPN", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Shows whether the tunnel is up"
            },
        )
        val snapshot = runtime.snapshot()
        val detail = if (snapshot.traffic.available) {
            "${snapshot.state.name.lowercase()} · ↓ ${rate(snapshot.traffic.downlink)} ↑ ${rate(snapshot.traffic.uplink)}"
        } else {
            snapshot.state.name.lowercase()
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
                    null,
                    "Disconnect",
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

    private fun rate(value: Long): String {
        val units = listOf("B", "KiB", "MiB", "GiB")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit += 1
        }
        return "${amount.toLong()} ${units[unit]}/s"
    }

    companion object {
        const val ACTION_START = "io.hydrabox.platform.android.START"
        const val ACTION_STOP = "io.hydrabox.platform.android.STOP"
        const val ACTION_MEASURE = "io.hydrabox.platform.android.MEASURE"
        private const val CHANNEL_ID = "hydrabox-vpn"
        private const val NOTIFICATION_ID = 1
        @Volatile private var libboxReady = false
        private val handler = object : CommandServerHandler {
            override fun getSystemProxyStatus() = SystemProxyStatus().apply { available = false; enabled = false }
            override fun serviceReload() = Unit
            override fun serviceStop() = Unit
            override fun setSystemProxyEnabled(enabled: Boolean) = Unit
            override fun writeDebugMessage(message: String?) = Unit
        }
    }
}
