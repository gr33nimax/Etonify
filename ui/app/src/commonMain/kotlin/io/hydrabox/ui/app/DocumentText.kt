package io.hydrabox.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.hydrabox.ui.design.UiTokens

/**
 * The legal documents arrive from HydraBox 1.x as markdown, because that is how they are
 * written and reviewed. Rendering them needs three rules — heading, bullet, paragraph — and
 * that is cheaper and safer than pulling in a markdown library for two documents.
 */
@Composable
fun DocumentText(markdown: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
    ) {
        markdown.split("\n").forEach { raw ->
            val line = raw.trim().replace("**", "")
            when {
                line.isEmpty() -> Unit
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = UiTokens.spacing),
                )
                line.startsWith("# ") -> Text(
                    line.removePrefix("# "),
                    style = MaterialTheme.typography.headlineSmall,
                )
                line.startsWith("- ") -> Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        line.removePrefix("- "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}
