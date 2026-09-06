package io.hydrabox.platform.android

/**
 * Presentation of the exit address the core reported.
 *
 * The address itself is asked of the running core, which dials its endpoint through the
 * named outbound ([CoreObserver.exitAddress] over the binder): the app's own request only
 * proves where the *app* comes out, which is a different thing in proxy-only mode, with the
 * app excluded from the tunnel, or under a settings change still waiting for its reconnect.
 * What is left here is the part that turns an answer into a row on a screen.
 */
object ExitAddressProbe {
    /** A country code as a flag, built from regional indicators rather than from an asset. */
    fun flagOf(countryCode: String?): String? {
        val code = countryCode?.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase() ?: return null
        val builder = StringBuilder()
        code.forEach { letter -> builder.appendCodePoint(0x1F1E6 + (letter.code - 'A'.code)) }
        return builder.toString()
    }
}
