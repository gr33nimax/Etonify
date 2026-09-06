package io.hydrabox.core.contract

@JvmInline value class ProcessEpoch(val value: String)
@JvmInline value class CommandGeneration(val value: Long)
@JvmInline value class RuntimeGeneration(val value: Long)
@JvmInline value class NetworkGeneration(val value: Long)
@JvmInline value class EventSequence(val value: Long)

enum class RuntimeState { STOPPED, STARTING, RUNNING, RECOVERING, STOPPING, FAILED }
enum class RuntimeMode { VPN, PROXY }
enum class FailureDomain { DNS, CREDENTIALS, AUTH, TURN, DTLS, QUIC, NETWORK, INTERNAL }
enum class TransportHealthState { STARTING, WAITING_USER, HEALTHY, DEGRADED, RECOVERING, FAILED }

enum class HydraCoreErrorCode(val code: String) {
    CONFIG_INVALID_PLAN("config.invalid_plan"), CONFIG_DIGEST_MISMATCH("config.digest_mismatch"),
    CONFIG_QUARANTINED("config.quarantined"), CONFIG_STALE("config.stale"),
    RUNTIME_CANCELLED("runtime.cancelled"), RUNTIME_SUPERSEDED("runtime.superseded"),
    RUNTIME_START_DEADLINE("runtime.start.deadline"), RUNTIME_STOP_UNCONFIRMED("runtime.stop.unconfirmed"),
    RUNTIME_CORE_DIED("runtime.core_died"), RUNTIME_IPC_LOST("runtime.ipc.lost"), RUNTIME_IPC_BIND_FAILED("runtime.ipc.bind_failed"),
    NETWORK_NO_INTERFACE("network.no_interface"), NETWORK_LOST("network.lost"), NETWORK_GENERATION_STALE("network.generation_stale"),
    DNS_BOOTSTRAP_TIMEOUT("dns.bootstrap.timeout"), DNS_UPSTREAM_TIMEOUT("dns.upstream.timeout"),
    DNS_UPSTREAM_REFUSED("dns.upstream.refused"), DNS_NO_ANSWER("dns.no_answer"),
    VK_CAPTCHA_REQUIRED("vk.captcha.required"), VK_CAPTCHA_TIMEOUT("vk.captcha.timeout"),
    VK_CAPTCHA_CANCELLED("vk.captcha.cancelled"), VK_CREDENTIALS_FLOOD("vk.credentials.flood"),
    VK_CREDENTIALS_REJECTED("vk.credentials.rejected"), VK_AUTH_TERMINAL("vk.auth.terminal"),
    TURN_ALLOCATE_FAILED("turn.allocate_failed"), TURN_NO_CANDIDATE("turn.no_candidate"),
    DTLS_HANDSHAKE_FAILED("dtls.handshake_failed"), QUIC_DIAL_FAILED("quic.dial_failed"),
    QUIC_NO_PATHS("quic.no_paths"), TRANSPORT_LANES_LOST("transport.lanes_lost"),
    TRANSPORT_RECOVERY_TIMEOUT("transport.recovery.timeout"), PROBE_INVALID_PLAN("probe.invalid_plan"),
    PROBE_REQUIRES_STOPPED_RUNTIME("probe.requires_stopped_runtime"), PROBE_TIMEOUT("probe.timeout"), PROBE_CANCELLED("probe.cancelled"),
}

data class RuntimeFailure(val domain: FailureDomain, val code: HydraCoreErrorCode, val retryable: Boolean)
data class OutboundSelection(val groupId: String, val outboundId: String)
data class TransportHealth(
    val state: TransportHealthState = TransportHealthState.STARTING,
    val activeLanes: Int = 0,
    /**
     * How many lanes the transport is supposed to have. Without it "3 lanes" says nothing:
     * three of four is a working tunnel, three of sixteen is one that is falling apart.
     */
    val totalLanes: Int = 0,
    val applicable: Boolean = true,
    val runtimeGeneration: RuntimeGeneration = RuntimeGeneration(0),
    val networkGeneration: NetworkGeneration = NetworkGeneration(0),
    val failure: RuntimeFailure? = null,
    /** How long the provider asked us to wait, when it said so. Zero when it did not. */
    val retryAfterMillis: Long = 0,
    /** Identity of the core outbound this health belongs to; empty for generic transports. */
    val transportTag: String = "",
    /** Smoothed RTT across active QUIC paths, zero until QUIC has a sample. */
    val quicRttMillis: Long = 0,
) {
    val isReady get() = !applicable || (state in setOf(TransportHealthState.HEALTHY, TransportHealthState.DEGRADED) && activeLanes >= 1)
}

/** Counters as the core reports them; [available] is false until it starts publishing. */
data class TrafficCounters(
    val available: Boolean = false,
    val uplink: Long = 0,
    val downlink: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
    val connectionsOut: Int = 0,
)

/** One measured outbound, as the core's own latency group reports it. */
data class OutboundLatency(
    val tag: String,
    val delayMillis: Int,
    val status: String,
    /** Epoch time when the core obtained this result; zero for older cores. */
    val observedAtMillis: Long = 0,
    /** Age at which this result becomes stale; zero when the source has no threshold. */
    val staleAfterMillis: Long = 0,
    val ageSeconds: Long = 0,
    val stale: Boolean = false,
)

data class RuntimeSnapshot(
    val processEpoch: ProcessEpoch,
    val commandGeneration: CommandGeneration,
    val runtimeGeneration: RuntimeGeneration,
    val networkGeneration: NetworkGeneration,
    val lastEventSequence: EventSequence,
    val state: RuntimeState,
    val mode: RuntimeMode?,
    val selectedOutbounds: List<OutboundSelection> = emptyList(),
    /**
     * What the core reports it is routing through, per group, which is not the same question as
     * [selectedOutbounds] — that one holds what was asked for. The core restores its own choice
     * from the cache file ahead of the configuration's default, and an automatic group decides
     * inside itself, so only this answers "where is the traffic actually going".
     */
    val observedOutbounds: List<OutboundSelection> = emptyList(),
    val transportHealth: TransportHealth = TransportHealth(),
    val lastFailure: RuntimeFailure? = null,
    val traffic: TrafficCounters = TrafficCounters(),
    val latencies: List<OutboundLatency> = emptyList(),
    val connectedAtElapsedRealtimeMillis: Long? = null,
)

sealed interface RuntimeCommand {
    data class Start(val mode: RuntimeMode) : RuntimeCommand
    data object Stop : RuntimeCommand
    data object Reload : RuntimeCommand
    data class SelectOutbound(val groupId: String, val outboundId: String) : RuntimeCommand
    data class NetworkChanged(val generation: NetworkGeneration) : RuntimeCommand
}

sealed interface RuntimeEvent {
    val sequence: EventSequence
    data class Snapshot(override val sequence: EventSequence, val snapshot: RuntimeSnapshot) : RuntimeEvent
    data class CommandResult(override val sequence: EventSequence, val state: RuntimeState, val failure: RuntimeFailure? = null) : RuntimeEvent
}
