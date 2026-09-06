package io.hydrabox.platform.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tracks the network the system would use if the tunnel did not exist, and tells the core
 * about it.
 *
 * Two rules carried over from 1.x, both learned the hard way. A network carrying
 * `TRANSPORT_VPN` is never a candidate — otherwise our own tunnel is treated as a usable
 * upstream. And the current value is replayed to a listener the moment it registers,
 * because the core registers after the first callback has already fired, and without the
 * replay a cold start never learns which interface to bind to.
 */
class DefaultNetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val listeners = CopyOnWriteArraySet<InterfaceUpdateListener>()
    private val lock = Any()

    @Volatile private var current: Iface? = null
    @Volatile private var generation: Long = 0
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Told when the underlying network changes, so the runtime can raise its network
     * generation and have the core rebind. Without this the core keeps dialling through an
     * interface that has gone, and the only symptom is a tunnel that stops carrying traffic
     * after a walk out of Wi-Fi range.
     */
    @Volatile var onChanged: ((Long) -> Unit)? = null

    data class Iface(val name: String, val index: Int)

    /**
     * The system's own handle for that network. The platform resolver needs it: a DNS query
     * has to leave through the network the tunnel is built on, never through the tunnel.
     */
    @Volatile var currentNetwork: Network? = null
        private set

    val networkGeneration get() = generation

    fun start() = synchronized(lock) {
        if (callback != null) return@synchronized
        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = refresh()
        }
        callback = created
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                created,
            )
        }
        refresh()
    }

    fun stop() = synchronized(lock) {
        callback?.let { registered -> runCatching { connectivity.unregisterNetworkCallback(registered) } }
        callback = null
    }

    fun addListener(listener: InterfaceUpdateListener) {
        listeners += listener
        // Replay, not wait: the value may already be known.
        publish(listener, current)
    }

    /**
     * Hands the current interface to the core. Called by the runtime, never by this monitor:
     * whether a network change may reach the core at all depends on what the tunnel is doing,
     * and only the runtime knows that. 1.x draws the same line — its monitor reports to the
     * runtime, and `CoreRuntimeService` decides whether to publish, publish and rebind, or
     * reject the change outright.
     */
    fun publishCurrent() {
        val iface = current
        listeners.forEach { publish(it, iface) }
    }

    fun removeListener(listener: InterfaceUpdateListener) {
        listeners -= listener
    }

    /**
     * Re-resolves the default interface and raises the generation if it moved.
     *
     * The decision and the increment are one step. They were two: the comparison happened
     * outside the lock and the increment inside, so two `ConnectivityManager` callbacks — and
     * there are always several, capabilities and link properties both fire — could both find the
     * interface changed and both raise the generation for one handover. The journal shows it:
     * `generation 1` and `generation 2` one millisecond apart.
     */
    private fun refresh() {
        val resolved = resolve()
        val raised = synchronized(lock) {
            if (resolved?.iface == current && resolved?.network == currentNetwork) return
            current = resolved?.iface
            currentNetwork = resolved?.network
            generation += 1
            generation
        }
        HydraLog.info(AREA, "default network is now ${resolved?.iface?.name ?: "none"}, generation $raised")
        onChanged?.invoke(raised)
    }

    /**
     * A missing interface is not published as `-1`.
     *
     * 1.x refuses that update explicitly (`publishDefaultInterface` returns when the index is
     * negative or the name is `tun0`), and the reason shows on a handover: telling the core it
     * has no default interface makes it drop the one it had, and a transport whose lanes are
     * mid-replacement loses them for good instead of moving them to the new network.
     */
    private fun publish(listener: InterfaceUpdateListener, iface: Iface?) {
        if (iface == null || iface.index <= 0 || iface.name.startsWith("tun")) return
        runCatching { listener.updateDefaultInterface(iface.name, iface.index, false, false) }
    }

    private data class Resolved(val iface: Iface, val network: Network)

    /** The best non-VPN network with internet, resolved down to an interface index. */
    private fun resolve(): Resolved? {
        val candidates = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        val systemDefault = connectivity.activeNetwork
        val ranked = candidates.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            val name = connectivity.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
            if (name.startsWith("tun")) return@mapNotNull null
            val transportRank = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> 3
            }
            RankedNetwork(
                defaultRank = if (network == systemDefault) 0 else 1,
                validationRank = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 0 else 1,
                transportRank = transportRank,
                name = name,
                network = network,
            )
        }.sortedWith(compareBy(RankedNetwork::validationRank, RankedNetwork::defaultRank, RankedNetwork::transportRank))
        val best = ranked.firstOrNull() ?: return null
        val index = runCatching { JavaNetworkInterface.getByName(best.name)?.index }.getOrNull() ?: return null
        return if (index > 0) Resolved(Iface(best.name, index), best.network) else null
    }

    private data class RankedNetwork(
        val defaultRank: Int,
        val validationRank: Int,
        val transportRank: Int,
        val name: String,
        val network: Network,
    )

    private companion object {
        const val AREA = "network"
    }
}
