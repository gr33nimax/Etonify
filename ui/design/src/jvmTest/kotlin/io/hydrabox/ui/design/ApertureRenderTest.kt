package io.hydrabox.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The aperture is rendered off-device, once per state, and the frames are compared.
 *
 * The failure this guards against has happened: two states drawn a shade apart, so a tunnel
 * that was up looked like one that was down. A screenshot is also the only way to see what
 * the control actually draws without a phone in hand, so the frames are kept on disk under
 * the module build directory.
 */
class ApertureRenderTest {
    @Test fun `every state draws its own frame`() {
        val frames = STATES.associate { (name, tone, state) ->
            name to render(name, tone, state)
        }
        frames.forEach { (name, bytes) -> assertTrue(bytes.size > 1_000, "$name rendered nothing") }
        val pairs = listOf("off" to "on", "off" to "connecting", "connecting" to "on", "on" to "holding")
        pairs.forEach { (left, right) ->
            assertTrue(
                frames.getValue(left).size != frames.getValue(right).size ||
                    !frames.getValue(left).contentEquals(frames.getValue(right)),
                "$left and $right draw the same frame",
            )
        }
    }

    @Test fun `related rows render as one section`() {
        val scene = ImageComposeScene(width = 720, height = 520, density = Density(2f)) {
            HydraTheme(dark = true) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                ) {
                    SectionGroup("Connection") {
                        HydraRow("VPN", "Use the system tunnel", onClick = {})
                        HydraRow("Proxy", "Use a local port", onClick = {})
                        ValueRow("Notifications", "Speed", onClick = {})
                    }
                }
            }
        }
        val bytes = try {
            scene.render().encodeToData()?.bytes ?: ByteArray(0)
        } finally {
            scene.close()
        }
        val directory = File(System.getProperty("user.dir"), "build/aperture").apply { mkdirs() }
        File(directory, "section.png").writeBytes(bytes)
        assertTrue(bytes.size > 1_000, "section rendered nothing")
    }

    private fun render(name: String, tone: ControlTone, state: ConnectionVisualState): ByteArray {
        val scene = ImageComposeScene(width = SIDE, height = SIDE, density = Density(2f)) {
            HydraTheme(dark = true) {
                Box(
                    modifier = Modifier.size(CONTROL).background(MaterialTheme.colorScheme.surface),
                ) {
                    ConnectionControl(
                        tone = tone,
                        visualState = state,
                        enabled = true,
                        contentDescription = "connect",
                        stateDescription = name,
                        onClick = {},
                        size = CONTROL,
                        signal = ControlSignal(downRate = 420_000, upRate = 40_000, quota = .62f),
                    ) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        val bytes = try {
            scene.render().encodeToData()?.bytes ?: ByteArray(0)
        } finally {
            scene.close()
        }
        val directory = File(System.getProperty("user.dir"), "build/aperture").apply { mkdirs() }
        File(directory, "$name.png").writeBytes(bytes)
        return bytes
    }

    private companion object {
        val CONTROL = 250.dp
        const val SIDE = 500
        val STATES = listOf(
            Triple("off", ControlTone.IDLE, ConnectionVisualState.DISCONNECTED),
            Triple("connecting", ControlTone.BUSY, ConnectionVisualState.CONNECTING),
            Triple("on", ControlTone.ACTIVE, ConnectionVisualState.CONNECTED),
            Triple("holding", ControlTone.BUSY, ConnectionVisualState.RECONNECTING),
            Triple("trouble", ControlTone.TROUBLE, ConnectionVisualState.DISCONNECTED),
        )
    }
}
