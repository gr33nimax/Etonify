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
import java.util.concurrent.CancellationException
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
    private const val AREA = "fetch"
    private const val MAX_BYTES = 16 * 1024 * 1024

    /**
     * How much of a refusal is read. The body of an error is a sentence for the journal, and
     * `readBytes` on it was unbounded: a server answering a hundred megabytes of HTML with a 500
     * had all of it in memory before the message was cut to two hundred characters.
     */
    private const val MAX_ERROR_BYTES = 8 * 1024
    private const val TIMEOUT_MILLIS = 20_000

    /**
     * How long the whole fetch may take, redirects included. The per-socket timeouts bound one
     * read, not the chain: five redirects that each answer just inside twenty seconds, or a body
     * that arrives a byte at a time, held the caller for as long as the server liked.
     */
    private const val MAX_TOTAL_MILLIS = 60_000L
    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "HydraBox/2.0.0-alpha1"

    /**
     * [identify] says whether this address is a Hydra subscription, and therefore whether the
     * per-origin device identifier travels with the request. The caller knows: by the time the
     * URL reaches here its `#hydra-key` fragment has been stripped, so the fragment can no
     * longer answer the question.
     */
    class Cancellation {
        private val lock = Any()
        private var connection: HttpURLConnection? = null
        private var cancelled = false

        fun cancel() {
            synchronized(lock) {
                cancelled = true
                connection?.disconnect()
                connection = null
            }
        }

        fun attach(value: HttpURLConnection) = synchronized(lock) {
            if (cancelled) {
                value.disconnect()
                throw CancellationException("subscription refresh cancelled")
            }
            connection = value
        }

        fun detach(value: HttpURLConnection) = synchronized(lock) {
            if (connection === value) connection = null
        }

        fun check() {
            if (isCancelled()) throw CancellationException("subscription refresh cancelled")
        }

        fun isCancelled(): Boolean = synchronized(lock) { cancelled }
    }

    fun fetch(
        context: Context,
        url: String,
        identify: Boolean = false,
        metadataOnly: Boolean = false,
        cancellation: Cancellation? = null,
    ): FetchedSubscription {
        var target = parse(url)
        // The identity is derived for one origin, so it travels only while we stay there.
        var identityOrigin = target.takeIf { identify }
            ?.let { runCatching { HydraDeviceIdentity.canonicalHttpsOrigin(originOf(it)) }.getOrNull() }
        var redirects = 0
        var offeredIdentity = false
        val deadline = System.nanoTime() + MAX_TOTAL_MILLIS * 1_000_000
        // The path is not written down. A Hydra link carries its key in the fragment, which never
        // travels, but plenty of providers put the subscription token in the path itself — and this
        // line ends up in the journal, in `logcat` and in an exported diagnostics report. The
        // fingerprint is enough to tell two endpoints apart in a support conversation.
        HydraLog.info(AREA, "GET ${target.host} path#${fingerprint(target.path)} identify=$identify")
        while (true) {
            val remaining = remainingMillis(deadline)
            val connection = open(context, target, identityOrigin, hydra = identify, metadataOnly = metadataOnly, budgetMillis = remaining)
            try {
                cancellation?.attach(connection)
                val code = status(connection)
                cancellation?.check()
                val success = code == HttpURLConnection.HTTP_OK ||
                    metadataOnly && code == HttpURLConnection.HTTP_PARTIAL
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
                // Some providers require the device identifier on every endpoint, not only on
                // the encrypted one, and say so in the body of a 400. The identifier is not
                // handed out by default — the shipped privacy policy says so — but refusing to
                // send it to a server that has just asked for it means the subscription simply
                // cannot be used. So it is offered once, to that origin, and only then.
                // The body of a refusal is read once: it is both the retry signal and the
                // explanation that reaches the journal.
                val complaint = if (success) "" else {
                    runCatching {
                        connection.errorStream?.use { stream ->
                            val head = ByteArray(MAX_ERROR_BYTES)
                            var filled = 0
                            while (filled < head.size) {
                                val read = stream.read(head, filled, head.size - filled)
                                if (read <= 0) break
                                filled += read
                            }
                            head.decodeToString(0, filled)
                        }?.trim().orEmpty()
                    }.getOrDefault("")
                }
                if (code in setOf(400, 401, 403) && identityOrigin == null && !offeredIdentity &&
                    target.protocol == "https"
                ) {
                    if (complaint.contains("hwid", ignoreCase = true)) {
                        offeredIdentity = true
                        identityOrigin = runCatching {
                            HydraDeviceIdentity.canonicalHttpsOrigin(originOf(target))
                        }.getOrNull()
                        if (identityOrigin != null) {
                            HydraLog.info(AREA, "${target.host} asked for a device identifier; offering it once")
                            continue
                        }
                    }
                }
                if (!success) {
                    // A provider says in the body what it wanted — "HydraBox HWID header is
                    // required" arrives as a 400 — so it goes to the journal rather than being
                    // discarded with the connection.
                    val explanation = complaint.take(200).takeIf(String::isNotEmpty)
                    HydraLog.error(AREA, "server answered $code${explanation?.let { ": $it" }.orEmpty()}")
                    throw SubscriptionException(
                        SourceFailure.HTTP_STATUS,
                        httpStatus = code,
                        detail = explanation,
                    )
                }
                if (metadataOnly) {
                    return FetchedSubscription("", metadata(connection))
                }
                val body = read(connection, deadline, cancellation)
                val html = connection.contentType?.startsWith("text/html", ignoreCase = true) == true ||
                    body.trimStart().take(64).lowercase().let { it.startsWith("<!doctype html") || it.startsWith("<html") }
                if (html) throw SubscriptionException(SourceFailure.HTML_RESPONSE)
                HydraLog.info(
                    AREA,
                    "200 ${connection.contentType ?: "unknown type"}, ${body.length} chars",
                )
                return FetchedSubscription(
                    body = body,
                    metadata = metadata(connection),
                )
            } finally {
                cancellation?.detach(connection)
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

    /** What is left of the overall budget, or a refusal when there is nothing left. */
    private fun remainingMillis(deadline: Long): Int {
        val left = (deadline - System.nanoTime()) / 1_000_000
        if (left <= 0) throw SubscriptionException(SourceFailure.TIMEOUT)
        return left.coerceAtMost(TIMEOUT_MILLIS.toLong()).toInt()
    }

    /**
     * A path as something that can be compared but not read back. Not a secret store: it exists
     * so a journal line can say "the same endpoint as before" without carrying the token in it.
     */
    private fun fingerprint(path: String?): String =
        path.orEmpty().hashCode().toUInt().toString(16).padStart(8, '0')

    private fun open(
        context: Context,
        target: URL,
        identityOrigin: String?,
        hydra: Boolean,
        metadataOnly: Boolean = false,
        budgetMillis: Int = TIMEOUT_MILLIS,
    ): HttpURLConnection =
        (target.openConnection() as HttpURLConnection).apply {
            connectTimeout = budgetMillis
            readTimeout = budgetMillis
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "gzip")
            if (metadataOnly) setRequestProperty("Range", "bytes=0-0")
            // Only a link that carries a Hydra key asks for the Hydra media types. A provider
            // that serves both shapes from one address answers this header: asking for the
            // encrypted document on a link with no key gets an envelope nothing can open,
            // which is what happened to the plain subscription. 1.x draws the same line in
            // `_hydraRequestHeaders`.
            setRequestProperty(
                "Accept",
                if (hydra) {
                    "${HydraSubscriptionUri.ENCRYPTED_MEDIA_TYPE}, ${HydraSubscriptionUri.PLAINTEXT_MEDIA_TYPE}, */*"
                } else {
                    "*/*"
                },
            )
            // Only while the request is still aimed at the origin the identity belongs to.
            if (identityOrigin != null && target.protocol == "https" &&
                runCatching { HydraDeviceIdentity.canonicalHttpsOrigin(originOf(target)) }.getOrNull() == identityOrigin
            ) {
                val identity = HydraDeviceIdentity.forOrigin(context, identityOrigin)
                // One header, not two. 1.x sends the `hbx1_` value as `X-Hydra-HWID` only,
                // with the reason written down in `subscription_fetcher.dart`: the server
                // fingerprints on the header it arrives in, and sending the same value as
                // `X-HWID` as well spends a second device slot on the same phone.
                setRequestProperty("X-Hydra-HWID", identity)
                HydraLog.debug(AREA, "device identity sent to ${target.host}")
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

    private fun read(connection: HttpURLConnection, deadline: Long, cancellation: Cancellation?): String {
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
                    cancellation?.check()
                    val read = input.read(chunk)
                    if (read <= 0) break
                    if (buffer.size() + read > MAX_BYTES) throw SubscriptionException(SourceFailure.TOO_LARGE)
                    // A body that arrives slowly enough never trips the socket's read timeout.
                    if (System.nanoTime() > deadline) throw SubscriptionException(SourceFailure.TIMEOUT)
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

    private fun metadata(connection: HttpURLConnection) = SubscriptionMetadata.parse(
        userInfo = connection.getHeaderField("subscription-userinfo"),
        title = title(connection.getHeaderField("profile-title")),
        updateInterval = connection.getHeaderField("profile-update-interval"),
    )

    /** Providers send the profile name either as text or base64, and say which. */
    private fun title(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!value.startsWith("base64:")) return value
        return runCatching {
            android.util.Base64.decode(value.removePrefix("base64:"), android.util.Base64.DEFAULT).decodeToString()
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }
}
