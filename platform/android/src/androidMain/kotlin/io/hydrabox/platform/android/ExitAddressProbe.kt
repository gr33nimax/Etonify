package io.hydrabox.platform.android

import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.diagnostics.Secret
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Base64

/**
 * Where the traffic comes out.
 *
 * This is the one fact a person actually wants from a VPN and the one the app never showed:
 * 1.x put it under the server name in its footer, and without it "Подключено" is a claim with
 * no evidence behind it.
 *
 * Cloudflare's trace endpoint is used because the answer is two lines of `key=value` — no JSON,
 * no API key, no third party learning more about the person than the exit node already knows.
 */
object ExitAddressProbe {
    private const val AREA = "exit"
    private const val ENDPOINT = "https://www.cloudflare.com/cdn-cgi/trace"

    data class Result(val address: String, val countryCode: String?)

    /** How a trace request is proven to travel inside the tunnel. */
    sealed interface Route {
        /** The system tunnel carries the request; nothing has to be configured. */
        data object ThroughTun : Route

        /**
         * The request is pushed through the core's own local inbound. This is the only path
         * left when there is no system tunnel (proxy-only mode) or when it does not carry
         * this app (split routing), and it proves the same thing: the answer names the
         * outbound the tunnel selected.
         *
         * The inbound is spoken to as an HTTP proxy rather than as SOCKS on purpose: a
         * `CONNECT` carries the hostname to the core, so the name is resolved by the tunnel's
         * resolver, and the credentials ride in a header instead of the process-wide
         * `Authenticator` nothing else should be touching.
         */
        data class ThroughLocalProxy(val proxy: Proxy, val authorization: String?) : Route

        /** No provable path: an answer would name the device's own address, not the tunnel's. */
        data object Unprovable : Route
    }

    /**
     * Decides how a trace request can be made to leave through the tunnel, from the runtime
     * mode and this app's place in the routing. Pure: the caller holds the settings.
     */
    fun route(
        mode: RuntimeMode?,
        appIncluded: Boolean,
        proxyInboundEnabled: Boolean,
        proxyPort: Int,
        proxyUsername: String? = null,
        proxyPassword: Secret? = null,
    ): Route = when {
        mode == null -> Route.Unprovable
        mode == RuntimeMode.VPN && appIncluded -> Route.ThroughTun
        proxyInboundEnabled -> Route.ThroughLocalProxy(
            proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)),
            authorization = authorization(proxyUsername, proxyPassword),
        )
        else -> Route.Unprovable
    }

    /** Blocking; call it off the main thread. Null when the answer did not arrive in time. */
    fun probe(via: Route.ThroughLocalProxy? = null, onConnection: (HttpURLConnection?) -> Unit = {}): Result? {
        val connection = runCatching {
            (URL(ENDPOINT).openConnection(via?.proxy ?: Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                setRequestProperty("Accept", "text/plain")
                via?.authorization?.let { setRequestProperty("Proxy-Authorization", it) }
                instanceFollowRedirects = false
            }
        }.getOrElse {
            HydraLog.warn(AREA, "could not open the trace request", it)
            return null
        }
        onConnection(connection)
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                HydraLog.warn(AREA, "the trace endpoint answered ${connection.responseCode}")
                return null
            }
            val fields = connection.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().take(FIELDS).mapNotNull { line ->
                    line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
            }
            val address = fields["ip"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val country = fields["loc"]?.trim()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            HydraLog.info(AREA, "the tunnel comes out in ${country ?: "an unknown country"}")
            Result(address, country?.uppercase())
        } catch (failure: Throwable) {
            HydraLog.warn(AREA, "the trace request failed", failure)
            null
        } finally {
            onConnection(null)
            runCatching { connection.disconnect() }
        }
    }

    /** A country code as a flag, built from regional indicators rather than from an asset. */
    fun flagOf(countryCode: String?): String? {
        val code = countryCode?.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase() ?: return null
        val builder = StringBuilder()
        code.forEach { letter -> builder.appendCodePoint(0x1F1E6 + (letter.code - 'A'.code)) }
        return builder.toString()
    }

    private fun authorization(username: String?, password: Secret?): String? {
        val user = username?.takeIf(String::isNotEmpty) ?: return null
        val secret = password ?: return null
        return secret.use { value ->
            "Basic " + Base64.getEncoder().encodeToString("$user:$value".toByteArray())
        }
    }

    private const val TIMEOUT = 4_000
    private const val FIELDS = 24
}
