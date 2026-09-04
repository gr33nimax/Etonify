package io.hydrabox.platform.android

import io.hydrabox.core.contract.FailureDomain
import io.hydrabox.core.contract.HydraCoreErrorCode
import io.hydrabox.core.contract.NetworkGeneration
import io.hydrabox.core.contract.RuntimeFailure
import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.contract.TransportHealthState
import io.nekohasekai.libbox.TransportFailure as CoreTransportFailure
import io.nekohasekai.libbox.TransportHealth as CoreTransportHealth

/**
 * Maps the core's typed transport event into the app's runtime contract.
 */
object TransportState {
    fun from(health: CoreTransportHealth) = TransportHealth(
        state = state(health.state),
        activeLanes = health.activeLanes,
        totalLanes = health.totalLanes,
        applicable = health.applicable,
        runtimeGeneration = RuntimeGeneration(health.runtimeGeneration),
        networkGeneration = NetworkGeneration(health.networkGeneration),
        failure = failure(health.failure),
        retryAfterMillis = health.failure?.retryAfterMillis ?: 0,
        transportTag = health.transportTag.orEmpty(),
    )

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
    private fun failure(failure: CoreTransportFailure?): RuntimeFailure? {
        failure ?: return null
        val kind = failure.kind.orEmpty()
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
        val domain = failure.domain
            ?.let { name -> FailureDomain.entries.firstOrNull { it.name == name } }
            ?: if (kind == "captcha") FailureDomain.AUTH else FailureDomain.NETWORK
        return RuntimeFailure(domain, code, retryable = !failure.terminal)
    }

    /** One line for the journal, written only when the transport changes what it says. */
    fun describe(health: TransportHealth): String = buildString {
        append("transport ").append(health.state.name.lowercase())
        if (health.totalLanes > 0) append(", lanes ").append(health.activeLanes).append(" of ").append(health.totalLanes)
        health.failure?.let { append(", ").append(it.domain.name.lowercase()).append(" / ").append(it.code.code) }
        if (health.retryAfterMillis > 0) append(", retry in ").append(health.retryAfterMillis / 1000).append("s")
    }
}
