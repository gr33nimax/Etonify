package io.hydrabox.platform.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.hydrabox.core.contract.RuntimeState

/**
 * Quick Settings tile. It reflects the runtime rather than its own idea of state: the tile
 * reads the snapshot over the same binder the UI uses, so it cannot drift from reality.
 */
class HydraTileService : TileService() {
    private var transport: BinderRuntimeTransport? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            transport = binder?.let(::BinderRuntimeTransport)
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transport = null
            render()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!bound) {
            // Deliberately without BIND_AUTO_CREATE. Pulling down the shade should not start a
            // second process with the core's native library in it, and it did: `batterystats`
            // counted nine launches of `HydraVpnService` for one actual start, alongside thirteen
            // tile bindings. Without the flag the bind succeeds only while the tunnel is already
            // up, which is the only case where there is any state to read; otherwise there is
            // nothing running and the tile is inactive, which is the truth.
            bound = runCatching { bindService(Intent(this, HydraVpnService::class.java), connection, 0) }
                .getOrDefault(false)
            // A bind that found nothing still leaves the connection registered.
            if (!bound) runCatching { unbindService(connection) }
        }
        render()
    }

    override fun onStopListening() {
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
            transport = null
        }
        super.onStopListening()
    }

    // The pre-34 overload is the only way to start an activity from a tile on those
    // versions, and minSdk is 26. The modern overload is used wherever it exists.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (state() == RuntimeState.RUNNING || state() == RuntimeState.STARTING) {
            startService(Intent(this, HydraVpnService::class.java).setAction(HydraVpnService.ACTION_STOP))
        } else {
            // Starting can need the system VPN consent dialog, which a tile cannot show,
            // so the activity is asked to start instead of the service directly. The
            // PendingIntent overload only exists from API 34; below that the deprecated
            // Intent overload is the only way.
            val target = Intent(this, RuntimeControlActivity::class.java)
                .setAction(RuntimeControlActivity.ACTION_REQUEST_START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, target, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(target)
            }
        }
        render()
    }

    private fun render() {
        val current = state()
        qsTile?.apply {
            state = when (current) {
                RuntimeState.RUNNING -> Tile.STATE_ACTIVE
                null, RuntimeState.STOPPED, RuntimeState.FAILED -> Tile.STATE_INACTIVE
                else -> Tile.STATE_UNAVAILABLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The words the product uses, not the reducer's state names: the tile used to
                // read `running` and `stopping` in English under a Russian interface.
                subtitle = getString(
                    when (current) {
                        RuntimeState.RUNNING -> R.string.notification_connected
                        RuntimeState.STOPPING -> R.string.notification_disconnecting
                        RuntimeState.FAILED -> R.string.notification_failed
                        RuntimeState.STARTING, RuntimeState.RECOVERING -> R.string.notification_connecting
                        null, RuntimeState.STOPPED -> R.string.tile_disconnected
                    },
                )
            }
            updateTile()
        }
    }

    private fun state(): RuntimeState? = runCatching { transport?.snapshot()?.state }.getOrNull()
}
