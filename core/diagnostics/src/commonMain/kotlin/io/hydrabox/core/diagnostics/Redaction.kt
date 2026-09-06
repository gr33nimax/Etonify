package io.hydrabox.core.diagnostics

/**
 * Takes the secrets out of a line before it is written down.
 *
 * The journal is read on the diagnostics screen, copied into a support conversation and exported
 * as plain text through the share sheet, and it is written by call sites that pass a URL or a
 * failure message straight through. Neither of those is careful on our behalf: a provider is free
 * to put the subscription token in the path of the address, and a failure crossing the language
 * boundary carries whatever text the other side put in it. Two shapes are removed here — the
 * inside of an http address, and a named credential in a query or a key-value pair — because
 * those are the two that have been observed carrying one.
 *
 * This is a floor, not a licence: a call site that knows it holds a secret must still not pass it.
 */
fun redactSecrets(text: String): String {
    if (text.isEmpty()) return text
    var redacted = text
    if (URL.containsMatchIn(redacted)) {
        redacted = URL.replace(redacted) { match ->
            // The origin identifies the provider and is worth keeping; everything after it is
            // theirs to shape, and some of them shape it out of a credential.
            match.groupValues[1] + "/…"
        }
    }
    if (CREDENTIAL.containsMatchIn(redacted)) {
        redacted = CREDENTIAL.replace(redacted) { match -> match.groupValues[1] + "=***" }
    }
    return redacted
}

/** An http address with something after its authority. The authority itself stays. */
private val URL = Regex("""(https?://[^\s/?#]+)[/?][^\s"']*""", RegexOption.IGNORE_CASE)

/**
 * A credential named in place. The names are the ones that appear in subscription links and in
 * the core's own messages; the value ends at whatever separates it from the next word.
 */
private val CREDENTIAL = Regex(
    """\b(token|access_token|session_key|key|hydra-key|password|passwd|secret|credential|auth|hwid)\b\s*[=:]\s*[^\s,;)\]}"']+""",
    RegexOption.IGNORE_CASE,
)
