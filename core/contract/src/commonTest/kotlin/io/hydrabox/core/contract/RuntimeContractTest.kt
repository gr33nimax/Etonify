package io.hydrabox.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeContractTest {
    @Test
    fun errorCodesMatchHydraCoreDictionary() {
        assertEquals(
            setOf(
                "config.invalid_plan", "config.digest_mismatch", "config.quarantined", "config.stale",
                "runtime.cancelled", "runtime.superseded", "runtime.start.deadline", "runtime.stop.unconfirmed",
                "runtime.core_died", "runtime.ipc.lost", "runtime.ipc.bind_failed", "runtime.reload_unsupported",
                "network.no_interface", "network.lost", "network.generation_stale",
                "dns.bootstrap.timeout", "dns.upstream.timeout", "dns.upstream.refused", "dns.no_answer",
                "vk.captcha.required", "vk.captcha.timeout", "vk.captcha.cancelled", "vk.credentials.flood",
                "vk.credentials.rejected", "vk.auth.terminal", "turn.allocate_failed", "turn.no_candidate",
                "dtls.handshake_failed", "quic.dial_failed", "quic.no_paths", "transport.lanes_lost",
                "transport.recovery.timeout", "probe.invalid_plan", "probe.requires_stopped_runtime", "probe.timeout",
                "probe.cancelled",
            ),
            HydraCoreErrorCode.entries.mapTo(mutableSetOf()) { it.code },
        )
    }

    @Test
    fun readinessHasOneThreshold() {
        assertTrue(TransportHealth(applicable = false).isReady)
        assertTrue(TransportHealth(TransportHealthState.HEALTHY, activeLanes = 1).isReady)
        assertTrue(TransportHealth(TransportHealthState.DEGRADED, activeLanes = 1).isReady)
        assertFalse(TransportHealth(TransportHealthState.HEALTHY).isReady)
        assertFalse(TransportHealth(TransportHealthState.STARTING, activeLanes = 1).isReady)
    }
}
