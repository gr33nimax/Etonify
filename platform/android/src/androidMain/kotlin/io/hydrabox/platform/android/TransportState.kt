package io.hydrabox.platform.android

import io.hydrabox.core.contract.FailureDomain
import io.hydrabox.core.contract.HydraCoreErrorCode
import io.hydrabox.core.contract.NetworkGeneration
import io.hydrabox.core.contract.RuntimeFailure
import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.contract.TransportHealthState
import io.nekohasekai.libbox.Libbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What the core says about the transport it is running.
 *
 * The core has published this all along — stage, how many lanes of how many are alive, and a
 * typed failure with the retry the provider asked for — and nothing read it: the health in the
 * snapshot was invented next to the first status message. That is why a tunnel that was up and
 * carrying nothing looked exactly like a healthy one.
 */
object TransportState {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the current snapshot. Returns null when the core is not callable in this process or
     * says nothing, so the caller can keep whatever it already believed.
     */
    fun read(generation: Long): TransportHealth? {
        val payload = runCatching { Libbox.hydraCoreTransportState() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val health = root["health"]?.jsonObject ?: return null
        // The core says itself whether this health describes anything: it is published by the
        // transport supervisor and by nothing else. Before it does, there is nothing to report.
        if (health.text("applicable") != "true") return null
        val failure = health["failure"] as? JsonObject
        return TransportHealth(
            state = state(health.text("state")),
            activeLanes = health.number("active_lanes")?.toInt() ?: 0,
            totalLanes = health.number("total_lanes")?.toInt() ?: 0,
            applicable = true,
            // The generation stays ours: the reducer matches a health against the command it
            // started, and the core counts its own.
            runtimeGeneration = RuntimeGeneration(generation),
            networkGeneration = NetworkGeneration(health.number("network_generation") ?: 0),
            failure = failure(failure),
            retryAfterMillis = failure?.number("retry_after_ms") ?: 0,
        )
    }

    private fun state(value: String?) = when (value) {
        "healthy" -> TransportHealthState.HEALTHY
        "degraded" -> TransportHealthState.DEGRADED
        "recovering" -> TransportHealthState.RECOVERING
        "waiting_user" -> TransportHealthState.WAITING_USER
        "failed" -> TransportHealthState.FAILED
        else -> TransportHealthState.STARTING
    }

    /**
     * The core's typed failure in the product's own vocabulary.
     *
     * Both fields it carries are used rather than guessed at: the domain is the one the
     * transport supervisor assigned, and `terminal` is its own verdict on whether trying again
     * can help. The numeric code is deliberately ignored — VK reuses numbers across endpoints,
     * and `kind` is the part that decides what a person can do about it.
     */
    private fun failure(failure: JsonObject?): RuntimeFailure? {
        failure ?: return null
        val kind = failure.text("kind").orEmpty()
        val code = when (kind) {
            "captcha" -> HydraCoreErrorCode.VK_CAPTCHA_REQUIRED
            "rate_limit" -> HydraCoreErrorCode.VK_CREDENTIALS_FLOOD
            "credentials" -> HydraCoreErrorCode.VK_CREDENTIALS_REJECTED
            "no_progress" -> HydraCoreErrorCode.TRANSPORT_RECOVERY_TIMEOUT
            "turn" -> HydraCoreErrorCode.TURN_ALLOCATE_FAILED
            "dtls" -> HydraCoreErrorCode.DTLS_HANDSHAKE_FAILED
            "quic" -> HydraCoreErrorCode.QUIC_DIAL_FAILED
            "network" -> HydraCoreErrorCode.TRANSPORT_LANES_LOST
            else -> HydraCoreErrorCode.TRANSPORT_LANES_LOST
        }
        val domain = failure.text("domain")
            ?.let { name -> FailureDomain.entries.firstOrNull { it.name == name } }
            ?: if (kind == "captcha") FailureDomain.AUTH else FailureDomain.NETWORK
        return RuntimeFailure(domain, code, retryable = failure.text("terminal") != "true")
    }

    /** One line for the journal, written only when the transport changes what it says. */
    fun describe(health: TransportHealth): String = buildString {
        append("transport ").append(health.state.name.lowercase())
        if (health.totalLanes > 0) append(", lanes ").append(health.activeLanes).append(" of ").append(health.totalLanes)
        health.failure?.let { append(", ").append(it.domain.name.lowercase()).append(" / ").append(it.code.code) }
        if (health.retryAfterMillis > 0) append(", retry in ").append(health.retryAfterMillis / 1000).append("s")
    }

    private fun JsonObject.text(field: String): String? =
        (this[field] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(field: String): Long? = this[field]?.jsonPrimitive?.let {
        it.longOrNull ?: it.intOrNull?.toLong()
    }
}
