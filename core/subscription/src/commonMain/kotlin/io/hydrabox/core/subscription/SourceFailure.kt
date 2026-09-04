package io.hydrabox.core.subscription

/**
 * Why a source could not be used, as one value rather than a sentence.
 *
 * HydraBox 1.x had seventeen of these (`lib/data/subscription/subscription_failure.dart`)
 * and it used them: "the server answered with a web page instead of a subscription" is a
 * different problem from "the link needs HTTPS", and a person can act on both. Carrying an
 * exception message instead loses that, which is why this exists.
 */
enum class SourceFailure {
    INVALID_URL,

    /** A link with a login in it, over plain HTTP: the credential would travel in clear. */
    CREDENTIALS_REQUIRE_HTTPS,

    /** A redirect that downgrades to HTTP, or leaves the origin the credentials were for. */
    UNSAFE_REDIRECT,
    TOO_MANY_REDIRECTS,
    HTTP_STATUS,
    TIMEOUT,
    NO_NETWORK,
    TLS,
    EMPTY_RESPONSE,

    /** A login page or a captcha where a subscription was expected. */
    HTML_RESPONSE,
    TOO_LARGE,

    /** The document parsed, but held nothing that can be connected to. */
    NO_USABLE_SERVERS,
    INVALID_CONTENT,

    /** An encrypted Hydra document without the key that opens it. */
    ENCRYPTED_WITHOUT_KEY,

    /**
     * The document is valid and asks for a core this build does not have: a required
     * feature, a contract version or a core version range. Nothing about the link is wrong,
     * so telling the person to check it would send them looking in the wrong place.
     */
    CORE_TOO_OLD,
    EXPIRED,
    UNKNOWN,
}

/**
 * [detail] is for the journal and the diagnostics screen, never for a screen a person reads:
 * it carries the core's own words, which are precise and untranslated.
 */
class SubscriptionException(
    val failure: SourceFailure,
    val httpStatus: Int? = null,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(listOfNotNull(failure.name.lowercase(), detail).joinToString(": "), cause)

/**
 * What a subscription server says about the subscription itself, in the two places it is
 * allowed to say it: the `subscription-userinfo` header and the profile headers next to it.
 * Every provider sends these, and 1.x showed them; without them the app cannot tell a
 * person how much traffic is left.
 */
data class SubscriptionMetadata(
    val title: String? = null,
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
    val updateIntervalHours: Int? = null,
) {
    val empty get() = title == null && usedBytes == null && totalBytes == null &&
        expiresAtEpochSeconds == null && updateIntervalHours == null

    companion object {
        /**
         * `subscription-userinfo: upload=1024; download=2048; total=1073741824; expire=1700000000`
         * — separators, order and case all vary between providers, so parse by name.
         */
        fun parse(
            userInfo: String?,
            title: String? = null,
            updateInterval: String? = null,
        ): SubscriptionMetadata {
            val fields = userInfo.orEmpty()
                .split(';', ',')
                .mapNotNull { part ->
                    val name = part.substringBefore('=', "").trim().lowercase()
                    val value = part.substringAfter('=', "").trim()
                    if (name.isEmpty() || value.isEmpty()) null else name to value
                }
                .toMap()
            val upload = fields["upload"]?.toLongOrNull()
            val download = fields["download"]?.toLongOrNull()
            val used = when {
                upload == null && download == null -> null
                else -> (upload ?: 0) + (download ?: 0)
            }
            return SubscriptionMetadata(
                title = title?.trim()?.takeIf(String::isNotEmpty),
                usedBytes = used,
                totalBytes = fields["total"]?.toLongOrNull()?.takeIf { it > 0 },
                expiresAtEpochSeconds = fields["expire"]?.toLongOrNull()?.takeIf { it > 0 },
                updateIntervalHours = updateInterval?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
    }
}
