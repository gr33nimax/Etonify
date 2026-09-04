package io.hydrabox.ui.design

import androidx.compose.ui.graphics.ImageBitmap

/** The desktop build has no QR encoder yet; the dialog falls back to the link as text. */
actual fun encodeQr(text: String, size: Int): ImageBitmap? = null
