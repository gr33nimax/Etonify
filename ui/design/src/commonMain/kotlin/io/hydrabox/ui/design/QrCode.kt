package io.hydrabox.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A subscription as a square.
 *
 * This is how a link actually travels between two people in the same room, and it is how every
 * client in this family lets one leave: a screenshot of a QR is a subscription, a pasted URL in
 * a chat is a subscription that also stays in the chat.
 */
expect fun encodeQr(text: String, size: Int): ImageBitmap?

@Composable
fun QrDialog(title: String, text: String, dismissLabel: String, onDismiss: () -> Unit) {
    val image = remember(text) { encodeQr(text, QR_PIXELS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(UiTokens.spacing * 2),
            ) {
                if (image == null) {
                    Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                } else {
                    // The code is drawn on white on purpose: a scanner reads contrast, not the
                    // app's theme, and an inverted code is one no camera will take.
                    Surface(color = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(UiTokens.spacing * 1.5f).size(240.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

private const val QR_PIXELS = 512
