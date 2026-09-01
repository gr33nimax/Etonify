package io.hydrabox.platform.android

import android.content.Context
import io.hydrabox.core.subscription.HydraSubscriptionUri
import io.hydrabox.core.subscription.SourceFailure
import io.hydrabox.core.subscription.SubscriptionException
import io.hydrabox.core.subscription.SubscriptionMetadata
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/** A fetched subscription: the document, and what the server said about the subscription. */
data class FetchedSubscription(val body: String, val metadata: SubscriptionMetadata)

/**
 * Fetches a subscription body over HTTP, with the security rules HydraBox 1.x had
 * (`lib/data/subscription/subscription_fetcher.dart`).
 *
 * Three of those rules are not conveniences: a link that carries a credential is refused
 * over plain HTTP rather than sent; a redirect may not downgrade to HTTP; and the device
 * identity header is dropped the moment a redirect leaves the origin it was derived for,
 * because that identifier is per-origin by design.
 */
object SubscriptionFetcher {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val TIMEOUT_MILLIS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "HydraBox/2.0.0-alpha1"

    /**
     * [identify] says whether this address is a Hydra subscription, and therefore whether the
     * per-origin device identifier travels with the request. The caller knows: by the time the
     * URL reaches here its `#hydra-key` fragment has been stripped, so the fragment can no
     * longer answer the question.
     */
    fun fetch(context: Context, url: String, identify: Boolean = false): FetchedSubscription {
        var target = parse(url)
        // The identity is derived for one origin, so it travels only while we stay there.
        val identityOrigin = target.takeIf { identify }
            ?.let { runCatching { HydraDeviceIdentity.canonicalHttpsOrigin(originOf(it)) }.getOrNull() }
        var redirects = 0
        while (true) {
            val connection = open(context, target, identityOrigin)
            try {
                val code = status(connection)
                if (code in 300..399 && code != 304) {
                    val location = connection.getHeaderField("Location")
                        ?: throw SubscriptionException(SourceFailure.INVALID_CONTENT)
                    if (++redirects > MAX_REDIRECTS) throw SubscriptionException(SourceFailure.TOO_MANY_REDIRECTS)
                    val next = parse(URL(target, location).toString())
                    if (target.protocol == "https" && next.protocol != "https") {
                        throw SubscriptionException(SourceFailure.UNSAFE_REDIRECT)
                    }
                    target = next
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw SubscriptionException(SourceFailure.HTTP_STATUS, httpStatus = code)
                }
                val body = read(connection)
                val html = connection.contentType?.startsWith("text/html", ignoreCase = true) == true ||
                    body.trimStart().take(64).lowercase().let { it.startsWith("<!doctype html") || it.startsWith("<html") }
                if (html) throw SubscriptionException(SourceFailure.HTML_RESPONSE)
                return FetchedSubscription(
                    body = body,
                    metadata = SubscriptionMetadata.parse(
                        userInfo = connection.getHeaderField("subscription-userinfo"),
                        title = title(connection.getHeaderField("profile-title")),
                        updateInterval = connection.getHeaderField("profile-update-interval"),
                    ),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Deliberately lenient about the shape of the address and strict about its safety.
     *
     * A subscription link is copied out of a chat or an email and often carries characters
     * `java.net.URI` refuses — a space, a brace, a non-ASCII letter — while the server accepts
     * them. 1.x used the lenient parser, so rejecting those here would refuse links that used
     * to work; the two security rules are checked on the parsed URL instead.
     */
    private fun parse(url: String): URL {
        val target = runCatching { URL(url.trim()) }.getOrNull()
            ?: throw SubscriptionException(SourceFailure.INVALID_URL)
        val protocol = target.protocol?.lowercase()
        if (protocol != "http" && protocol != "https") throw SubscriptionException(SourceFailure.INVALID_URL)
        if (target.userInfo != null && protocol != "https") {
            throw SubscriptionException(SourceFailure.CREDENTIALS_REQUIRE_HTTPS)
        }
        return target
    }

    private fun originOf(url: URL) = buildString {
        append(url.protocol).append("://").append(url.host)
        if (url.port != -1) append(':').append(url.port)
    }

    private fun open(context: Context, target: URL, identityOrigin: String?): HttpURLConnection =
        (target.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty(
                "Accept",
                "${HydraSubscriptionUri.PLAINTEXT_MEDIA_TYPE}, ${HydraSubscriptionUri.ENCRYPTED_MEDIA_TYPE}, */*",
            )
            // Only while the request is still aimed at the origin the identity belongs to.
            if (identityOrigin != null && target.protocol == "https" &&
                runCatching { HydraDeviceIdentity.canonicalHttpsOrigin(originOf(target)) }.getOrNull() == identityOrigin
            ) {
                val identity = HydraDeviceIdentity.forOrigin(context, identityOrigin)
                setRequestProperty("X-Hydra-HWID", identity)
                setRequestProperty("X-HWID", identity)
            }
        }

    private fun status(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (error: SocketTimeoutException) {
        throw SubscriptionException(SourceFailure.TIMEOUT, cause = error)
    } catch (error: UnknownHostException) {
        throw SubscriptionException(SourceFailure.NO_NETWORK, cause = error)
    } catch (error: SSLException) {
        throw SubscriptionException(SourceFailure.TLS, cause = error)
    } catch (error: IOException) {
        throw SubscriptionException(SourceFailure.NO_NETWORK, cause = error)
    }

    private fun read(connection: HttpURLConnection): String {
        val stream = try {
            connection.inputStream.let {
                if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(it) else it
            }
        } catch (error: IOException) {
            throw SubscriptionException(SourceFailure.NO_NETWORK, cause = error)
        }
        val buffer = ByteArrayOutputStream()
        try {
            stream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    if (buffer.size() + read > MAX_BYTES) throw SubscriptionException(SourceFailure.TOO_LARGE)
                    buffer.write(chunk, 0, read)
                }
            }
        } catch (error: SocketTimeoutException) {
            throw SubscriptionException(SourceFailure.TIMEOUT, cause = error)
        } catch (error: IOException) {
            throw SubscriptionException(SourceFailure.NO_NETWORK, cause = error)
        }
        return buffer.toByteArray().decodeToString().trim()
            .ifEmpty { throw SubscriptionException(SourceFailure.EMPTY_RESPONSE) }
    }

    /** Providers send the profile name either as text or base64, and say which. */
    private fun title(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!value.startsWith("base64:")) return value
        return runCatching {
            android.util.Base64.decode(value.removePrefix("base64:"), android.util.Base64.DEFAULT).decodeToString()
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }
}
