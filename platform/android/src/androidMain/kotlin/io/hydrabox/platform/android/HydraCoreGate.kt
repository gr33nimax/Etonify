package io.hydrabox.platform.android

import io.nekohasekai.libbox.Libbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The core is authoritative for the Hydra subscription contract: schema, references,
 * permissions and JWE. This is the gate to it — the client validates through the core and
 * only then projects the document, instead of re-implementing the rules and drifting.
 */
object HydraCoreGate {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Inspection(
        val valid: Boolean,
        val subscriptionId: String? = null,
        val displayName: String? = null,
        val notAfter: String? = null,
        val profiles: Int = 0,
        val resources: Int = 0,
        val diagnostics: List<String> = emptyList(),
    )

    /** True when the body is a JOSE envelope rather than a plaintext document. */
    fun looksEncrypted(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return false
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return false
        return root.containsKey("protected") && root.containsKey("ciphertext") ||
            root.containsKey("protected") && !root.containsKey("resources")
    }

    fun looksHydra(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return false
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return false
        return root["api_version"]?.jsonPrimitive?.contentOrNull?.startsWith("hydra.io/subscription") == true ||
            root["kind"]?.jsonPrimitive?.contentOrNull == "Subscription" ||
            looksEncrypted(trimmed)
    }

    /**
     * Opens an envelope through the core. The key never leaves this call, and the failure
     * message comes from the core rather than being invented here.
     */
    fun open(envelope: String, keyBase64Url: String): String {
        runCatching { Libbox.hydraCoreValidateSubscriptionJWE(envelope, keyBase64Url) }.getOrNull()
            ?.let(::diagnose)
            ?.takeIf { it.isNotEmpty() }
            ?.let { error("core rejected the encrypted subscription: ${it.joinToString("; ")}") }
        // Opening an envelope, unlike validating it, only the core can do.
        return runCatching { Libbox.hydraCoreOpenSubscriptionJWE(envelope, keyBase64Url) }
            .getOrElse { error("the core could not open this encrypted subscription") }
    }

    /**
     * Validates a plaintext document through the core, throwing with its own reasons.
     *
     * The core lives in another process. When its library is not loaded in this one, the
     * import must not fail: the document is validated again before it is ever run, by the
     * process that owns the core. Refusing here would mean a subscription that works cannot be
     * added, which is worse than adding one that the core will reject with its own message.
     */
    fun validate(document: String) {
        val result = runCatching { Libbox.hydraCoreValidateSubscription(document) }.getOrNull() ?: return
        diagnose(result)
            .takeIf { it.isNotEmpty() }
            ?.let { error("core rejected the subscription: ${it.joinToString("; ")}") }
    }

    fun inspect(document: String): Inspection {
        val root = runCatching { json.parseToJsonElement(Libbox.hydraCoreInspectSubscription(document)) }
            .getOrNull() as? JsonObject ?: return Inspection(valid = false, diagnostics = listOf("inspection failed"))
        val identity = root["identity"] as? JsonObject
        val validity = root["validity"] as? JsonObject
        return Inspection(
            valid = root["valid"]?.jsonPrimitive?.contentOrNull == "true",
            subscriptionId = identity?.get("id")?.jsonPrimitive?.contentOrNull,
            displayName = identity?.get("name")?.jsonPrimitive?.contentOrNull,
            notAfter = validity?.get("not_after")?.jsonPrimitive?.contentOrNull,
            profiles = (root["profiles"] as? JsonArray)?.size ?: 0,
            resources = (root["resources"] as? JsonArray)?.size ?: 0,
            diagnostics = messages(root["diagnostics"] as? JsonArray),
        )
    }

    private fun diagnose(result: String): List<String> {
        val root = runCatching { json.parseToJsonElement(result) }.getOrNull() as? JsonObject
            ?: return listOf("the core returned an unreadable validation result")
        if (root["valid"]?.jsonPrimitive?.contentOrNull == "true") return emptyList()
        return messages(root["diagnostics"] as? JsonArray).ifEmpty { listOf("validation failed without a reason") }
    }

    private fun messages(diagnostics: JsonArray?): List<String> = diagnostics.orEmpty().mapNotNull { entry ->
        val diagnostic = entry as? JsonObject ?: return@mapNotNull null
        val code = diagnostic["code"]?.jsonPrimitive?.contentOrNull
        val message = diagnostic["message"]?.jsonPrimitive?.contentOrNull
        val path = diagnostic["path"]?.jsonPrimitive?.contentOrNull
        listOfNotNull(code, message, path?.takeIf { it != "$" }?.let { "at $it" }).joinToString(": ")
            .takeIf(String::isNotEmpty)
    }
}
