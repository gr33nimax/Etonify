package io.hydrabox.platform.android

import io.nekohasekai.libbox.Libbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the linked core says it can do.
 *
 * The client and the core ship from one manifest, but a debug build can be paired with an older
 * pinned AAR, and a feature the core lacks must not be offered as if it worked. The values are
 * read once: they describe the binary, which cannot change while the process lives.
 */
object CoreFeatures {
    private const val AREA = "core-features"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val features: JsonObject? by lazy {
        runCatching { json.parseToJsonElement(Libbox.hydraCoreCapabilities()).jsonObject["features"]?.jsonObject }
            .onFailure { HydraLog.warn(AREA, "the core did not report its capabilities", it) }
            .getOrNull()
    }

    private fun flag(name: String): Boolean =
        features?.get(name)?.jsonPrimitive?.booleanOrNull ?: false

    /**
     * Whether the level may be changed on a running core, including turning logging on after a
     * start that had it off. An older core accepts the call and quietly does nothing, so without
     * this flag the honest answer is that a reconnect is needed.
     */
    val runtimeLogLevel: Boolean by lazy { flag("runtime_log_level") }

    /**
     * Whether the automatic group honours `probe_timeout` and `probe_concurrency`. The core
     * rejects a configuration over fields it does not know, so without this flag the client
     * must keep them out and the automatic group runs on its built-in budget.
     */
    val urlTestProbeBudget: Boolean by lazy { flag("urltest_probe_budget") }
}
