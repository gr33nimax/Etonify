package io.hydrabox.platform.android

import java.net.HttpURLConnection
import java.net.URL

/**
 * Where the traffic comes out.
 *
 * This is the one fact a person actually wants from a VPN and the one the app never showed:
 * 1.x put it under the server name in its footer, and without it "Подключено" is a claim with
 * no evidence behind it. The request goes through the tunnel like everything else the app does,
 * so the address that comes back is the tunnel's own.
 *
 * Cloudflare's trace endpoint is used because the answer is two lines of `key=value` — no JSON,
 * no API key, no third party learning more about the person than the exit node already knows.
 */
object ExitAddressProbe {
    private const val AREA = "exit"
    private const val ENDPOINT = "https://www.cloudflare.com/cdn-cgi/trace"

    data class Result(val address: String, val countryCode: String?)

    /** Blocking; call it off the main thread. Null when the answer did not arrive in time. */
    fun probe(): Result? {
        val connection = runCatching {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
                setRequestProperty("Accept", "text/plain")
                instanceFollowRedirects = false
            }
        }.getOrElse {
            HydraLog.warn(AREA, "could not open the trace request", it)
            return null
        }
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

    private const val TIMEOUT = 4_000
    private const val FIELDS = 24
}
