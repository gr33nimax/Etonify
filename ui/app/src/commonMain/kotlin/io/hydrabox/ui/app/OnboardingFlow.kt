package io.hydrabox.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.PrimaryAction
import io.hydrabox.ui.design.SecondaryAction
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.ValueRow
import io.hydrabox.ui.design.WarningStrip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Where the first run stands. The flow is blocking: it owns the window until it is done. */
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
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember(state.legalAccepted) {
        mutableStateOf(if (state.legalAccepted) OnboardingStep.SUBSCRIPTION else OnboardingStep.WELCOME)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = UiTokens.spacing * 3, vertical = UiTokens.spacing * 4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UiTokens.spacing * 2),
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(Res.drawable.hydrabox_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(if (step == OnboardingStep.WELCOME) 148.dp else 84.dp),
            )
            when (step) {
                OnboardingStep.WELCOME -> Welcome { step = OnboardingStep.LEGAL }
                OnboardingStep.LEGAL -> Legal(
                    onOpenTerms = onOpenTerms,
                    onOpenPrivacy = onOpenPrivacy,
                    onAccept = {
                        actions.onAcceptLegal()
                        step = OnboardingStep.SUBSCRIPTION
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
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(vertical = UiTokens.spacing),
    ) {
        ValueRow(stringResource(Res.string.about_terms), null, HydraIcons.Info, onOpenTerms)
        ValueRow(stringResource(Res.string.about_privacy), null, HydraIcons.Info, onOpenPrivacy)
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
    Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing), verticalAlignment = Alignment.CenterVertically) {
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
