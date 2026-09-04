package io.hydrabox.ui.design

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun uiCapabilities(): UiCapabilities {
    val resolver = LocalContext.current.contentResolver
    val reducedMotion = remember(resolver) {
        isReducedMotion(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
    return UiCapabilities(ComponentLevel.EXPRESSIVE, MotionScheme.SPRING, reducedMotion)
}
