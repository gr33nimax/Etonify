package io.hydrabox.platform.android

private const val DEFAULT_REFRESH_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
private const val MAX_REFRESH_INTERVAL_HOURS = 24L * 365

internal fun refreshAt(updatedAtMillis: Long, providerIntervalHours: String?): Long {
    val hours = providerIntervalHours?.toLongOrNull()?.takeIf { it in 1..MAX_REFRESH_INTERVAL_HOURS }
    val interval = (hours ?: DEFAULT_REFRESH_INTERVAL_MILLIS / (60 * 60 * 1000)) * 60 * 60 * 1000
    return updatedAtMillis.coerceAtLeast(0).let { updated ->
        if (updated > Long.MAX_VALUE - interval) Long.MAX_VALUE else updated + interval
    }
}

internal data class ScopedSelection(val sourceId: String, val originalTag: String)

internal fun resolveSelection(
    stored: String,
    sourceId: String?,
    originalTag: String?,
    selections: Map<String, ScopedSelection>,
): String = selections.entries.firstOrNull { (_, identity) ->
    identity.sourceId == sourceId && identity.originalTag == originalTag
}?.key ?: stored
