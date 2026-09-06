package io.hydrabox.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    @Test fun `the inside of an address is not written down`() {
        assertEquals(
            "GET https://provider.example/… failed",
            redactSecrets("GET https://provider.example/sub/9f3c1a55d0/config failed"),
        )
    }

    @Test fun `the origin survives so two providers stay distinguishable`() {
        assertTrue(redactSecrets("https://a.example/x").contains("a.example"))
        assertEquals("https://a.example:8443/…", redactSecrets("https://a.example:8443/x?y=1"))
    }

    @Test fun `an address with nothing after it is left alone`() {
        assertEquals("reached https://provider.example", redactSecrets("reached https://provider.example"))
    }

    @Test fun `a named credential loses its value`() {
        assertEquals("token=***", redactSecrets("token=abc123"))
        assertEquals("[auth] joining, token=***", redactSecrets("[auth] joining, token=x9YqZ"))
        assertEquals("session_key=*** and hwid=***", redactSecrets("session_key=aaa and hwid=hbx1_bbb"))
        assertFalse(redactSecrets("password: hunter2").contains("hunter2"))
    }

    @Test fun `a word that merely ends in a name is not a credential`() {
        assertEquals("keystore=/data/x", redactSecrets("keystore=/data/x"))
    }

    @Test fun `an ordinary line is unchanged`() {
        val line = "the core accepted the configuration"
        assertEquals(line, redactSecrets(line))
        assertEquals("", redactSecrets(""))
    }
}
