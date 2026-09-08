package io.hydrabox.core.projection

import io.hydrabox.core.contract.HydraCoreErrorCode
import io.hydrabox.core.contract.RuntimeFailure

/**
 * What the last measurement of a server said.
 *
 * A server with no figure and a server that was asked and did not answer are not the same
 * thing, and the difference is what a person needs to pick one: an empty column read as
 * "not measured yet" for servers the core had already given up on.
 */
enum class ProbeState { UNKNOWN, ANSWERING, SILENT }

/**
 * One server as a person picks it. [auto] is the automatic choice by latency; its
 * [resolvedName] says which server the automatic choice is actually using, because
 * "auto" alone answers none of the three questions the home screen has to answer.
 */
data class ServerRef(
    val id: String,
    val displayName: String,
    val auto: Boolean = false,
    val resolvedName: String? = null,
    val latencyMillis: Int? = null,
    val sourceId: String = "",
    /** The protocol this server speaks, as the subscription described it. */
    val type: String? = null,
    /** What the last measurement said, when there was one. */
    val probe: ProbeState = ProbeState.UNKNOWN,
    val latencyStale: Boolean = false,
    val quicRttMillis: Int? = null,
    /**
     * The figure is the round trip to the VK transport's TURN edge — one STUN Binding, not a
     * measurement of the tunnel. It says so next to the number, because it proves the edge
     * and nothing behind it.
     */
    val latencyIsEdgeRtt: Boolean = false,
)

/**
 * Why the product is not carrying traffic, in the terms a person can act on. The runtime
 * has thirty-five error codes; a person has six situations. The mapping lives in
 * [Trouble.of] and nowhere else, so no screen ever sees a code.
 */
enum class Trouble {
    /** The device itself has no working network. */
    NO_INTERNET,

    /** The network works, this server does not answer. */
    SERVER_UNREACHABLE,

    /** The source of servers can no longer be used: expired, revoked, rejected. */
    SUBSCRIPTION_UNAVAILABLE,

    /** The tunnel could not be prepared from what is stored. A bug, not a user error. */
    CONFIG_REJECTED,

    /** The system consent for a VPN is missing. */
    PERMISSION_REQUIRED,

    /** Anything the runtime could not classify. Offers diagnostics, never a code. */
    UNKNOWN,
    ;

    companion object {
        fun of(failure: RuntimeFailure?): Trouble = when (failure?.code) {
            null -> UNKNOWN
            HydraCoreErrorCode.NETWORK_NO_INTERFACE,
            HydraCoreErrorCode.NETWORK_LOST,
            HydraCoreErrorCode.NETWORK_GENERATION_STALE,
            HydraCoreErrorCode.DNS_BOOTSTRAP_TIMEOUT,
            -> NO_INTERNET

            HydraCoreErrorCode.QUIC_DIAL_FAILED,
            HydraCoreErrorCode.QUIC_NO_PATHS,
            HydraCoreErrorCode.TURN_ALLOCATE_FAILED,
            HydraCoreErrorCode.TURN_NO_CANDIDATE,
            HydraCoreErrorCode.DTLS_HANDSHAKE_FAILED,
            HydraCoreErrorCode.TRANSPORT_LANES_LOST,
            HydraCoreErrorCode.TRANSPORT_RECOVERY_TIMEOUT,
            HydraCoreErrorCode.RUNTIME_START_DEADLINE,
            HydraCoreErrorCode.DNS_UPSTREAM_TIMEOUT,
            HydraCoreErrorCode.DNS_UPSTREAM_REFUSED,
            HydraCoreErrorCode.DNS_NO_ANSWER,
            HydraCoreErrorCode.PROBE_TIMEOUT,
            -> SERVER_UNREACHABLE

            HydraCoreErrorCode.VK_CREDENTIALS_REJECTED,
            HydraCoreErrorCode.VK_CREDENTIALS_FLOOD,
            HydraCoreErrorCode.VK_AUTH_TERMINAL,
            HydraCoreErrorCode.VK_CAPTCHA_REQUIRED,
            HydraCoreErrorCode.VK_CAPTCHA_TIMEOUT,
            HydraCoreErrorCode.VK_CAPTCHA_CANCELLED,
            -> SUBSCRIPTION_UNAVAILABLE

            HydraCoreErrorCode.CONFIG_INVALID_PLAN,
            HydraCoreErrorCode.CONFIG_DIGEST_MISMATCH,
            HydraCoreErrorCode.CONFIG_QUARANTINED,
            HydraCoreErrorCode.CONFIG_STALE,
            HydraCoreErrorCode.PROBE_INVALID_PLAN,
            -> CONFIG_REJECTED

            else -> UNKNOWN
        }
    }
}

/**
 * What the product says about the tunnel. A runtime phase is not a product state:
 * `STARTING` and `RECOVERING` are both "the tunnel is not carrying traffic yet", but only
 * one of them was asked for by the person looking at the screen, so they are different
 * states here.
 */
sealed interface Connection {
    /** Nothing to connect to yet: the very first run, or every source was removed. */
    data object NeedsSubscription : Connection

    /** A source exists but holds no server. Its own problem, its own single action. */
    data object NeedsServers : Connection

    data class Idle(val server: ServerRef?) : Connection

    /** Asked for by the person: they pressed connect. */
    data class Connecting(val server: ServerRef?) : Connection

    data class Connected(
        val server: ServerRef?,
        val traffic: TrafficSummary,
    ) : Connection

    /** Not asked for: the network moved under us and the runtime is recovering. */
    data class Reconnecting(val server: ServerRef?) : Connection

    data object Disconnecting : Connection

    data class Stopped(val cause: Trouble, val server: ServerRef?, val retryable: Boolean) : Connection
}

/** The single action the connection state offers. One state, one primary action. */
enum class PrimaryAction { CONNECT, CANCEL, DISCONNECT, RETRY, ADD_SUBSCRIPTION, REFRESH_SOURCE, CHOOSE_SERVER, NONE }

val Connection.primaryAction: PrimaryAction
    get() = when (this) {
        Connection.NeedsSubscription -> PrimaryAction.ADD_SUBSCRIPTION
        Connection.NeedsServers -> PrimaryAction.REFRESH_SOURCE
        is Connection.Idle -> PrimaryAction.CONNECT
        is Connection.Connecting -> PrimaryAction.CANCEL
        is Connection.Connected -> PrimaryAction.DISCONNECT
        is Connection.Reconnecting -> PrimaryAction.CANCEL
        Connection.Disconnecting -> PrimaryAction.NONE
        is Connection.Stopped -> when (cause) {
            Trouble.SERVER_UNREACHABLE -> PrimaryAction.CHOOSE_SERVER
            Trouble.SUBSCRIPTION_UNAVAILABLE -> PrimaryAction.REFRESH_SOURCE
            else -> PrimaryAction.RETRY
        }
    }

/** The server the state is about, so the screen does not unpack the state to find it. */
val Connection.server: ServerRef?
    get() = when (this) {
        is Connection.Idle -> server
        is Connection.Connecting -> server
        is Connection.Connected -> server
        is Connection.Reconnecting -> server
        is Connection.Stopped -> server
        else -> null
    }

/** True while traffic is actually flowing through the tunnel. */
val Connection.protecting: Boolean get() = this is Connection.Connected
