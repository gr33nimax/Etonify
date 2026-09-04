package io.hydrabox.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.ActionRow
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.PrimaryAction
import io.hydrabox.ui.design.SecondaryAction
import io.hydrabox.ui.design.SectionGroup
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.ValueRow
import io.hydrabox.ui.design.WarningStrip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Where the first run stands.
 *
 * The step is owned by the caller on purpose. Reading the terms opens a detail screen, and
 * while that screen is up the flow is not composed; a step remembered inside the flow is
 * lost there, and coming back from the document restarted the first run from its first
 * screen — which is what happened on the device.
 */
enum class OnboardingStep { WELCOME, LEGAL, SUBSCRIPTION }

/**
 * The first run, as a flow with a beginning and an end, instead of a consent banner stuck
 * above every screen. Terms are agreed to once, here, and then never shown again outside
 * "about".
 */
@Composable
fun OnboardingFlow(
    state: ScreenState,
    actions: AppActions,
    step: OnboardingStep,
    onStep: (OnboardingStep) -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onFinish: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize()
                // The flow is the whole window — there is no scaffold above it to keep the
                // status bar and the gesture area clear of its content.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = UiTokens.spacing * 3, vertical = UiTokens.spacing * 4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UiTokens.spacing * 2),
        ) {
            // The mark is a silhouette, and it is tinted rather than drawn as it is: the
            // asset shipped in the alpha carried a baked-in white background, which on a
            // dark theme is a white square with a logo somewhere inside it.
            androidx.compose.foundation.Image(
                painter = painterResource(Res.drawable.hydrabox_logo),
                contentDescription = stringResource(Res.string.app_name),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(if (step == OnboardingStep.WELCOME) 148.dp else 84.dp),
            )
            when (step) {
                OnboardingStep.WELCOME -> Welcome { onStep(OnboardingStep.LEGAL) }
                OnboardingStep.LEGAL -> Legal(
                    onOpenTerms = onOpenTerms,
                    onOpenPrivacy = onOpenPrivacy,
                    onAccept = {
                        actions.onAcceptLegal()
                        onStep(OnboardingStep.SUBSCRIPTION)
                    },
                )
                OnboardingStep.SUBSCRIPTION -> FirstSubscription(state, actions, onFinish)
            }
        }
    }
}

@Composable
private fun Welcome(onContinue: () -> Unit) {
    Text(
        stringResource(Res.string.onboarding_welcome_title),
        style = MaterialTheme.typography.displaySmall,
    )
    Text(
        stringResource(Res.string.onboarding_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    PrimaryAction(
        label = stringResource(Res.string.onboarding_welcome_action),
        onClick = onContinue,
        modifier = Modifier.padding(top = UiTokens.spacing * 2),
    )
}

@Composable
private fun Legal(onOpenTerms: () -> Unit, onOpenPrivacy: () -> Unit, onAccept: () -> Unit) {
    Text(stringResource(Res.string.onboarding_legal_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(Res.string.onboarding_legal_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    SectionGroup(modifier = Modifier.padding(vertical = UiTokens.spacing)) {
        ValueRow(stringResource(Res.string.about_terms), null, HydraIcons.Document, onOpenTerms)
        ValueRow(stringResource(Res.string.about_privacy), null, HydraIcons.Lock, onOpenPrivacy)
    }
    PrimaryAction(label = stringResource(Res.string.action_accept), onClick = onAccept)
}

@Composable
private fun FirstSubscription(state: ScreenState, actions: AppActions, onFinish: () -> Unit) {
    var link by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Text(stringResource(Res.string.onboarding_source_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(Res.string.onboarding_source_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    HydraField(
        value = link,
        onValueChange = { link = it },
        label = stringResource(Res.string.sources_add_field),
        supporting = stringResource(Res.string.sources_add_hint),
        singleLine = false,
        minLines = 2,
        modifier = Modifier.padding(vertical = UiTokens.spacing),
    )
    state.notice?.takeIf { it.failure }?.let { notice ->
        WarningStrip(text = noticeText(notice), actionLabel = null, onAction = null)
    }
    ActionRow {
        PrimaryAction(
            label = stringResource(Res.string.action_add),
            enabled = link.isNotBlank() && !state.busy.source,
            onClick = { actions.onAddSource("", link.trim()) },
        )
        SecondaryAction(
            label = stringResource(Res.string.action_paste),
            onClick = { clipboard.getText()?.text?.let { link = it.trim() } },
        )
        SecondaryAction(
            label = stringResource(Res.string.sources_add_file),
            onClick = actions.onAddSourceFromFile,
        )
    }
    Spacer(Modifier.size(UiTokens.spacing))
    SecondaryAction(label = stringResource(Res.string.onboarding_later), onClick = onFinish)
}
