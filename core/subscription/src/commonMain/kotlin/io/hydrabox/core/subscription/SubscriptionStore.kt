package io.hydrabox.core.subscription

import io.hydrabox.core.diagnostics.Secret
import io.hydrabox.core.diagnostics.SecretOpener
import io.hydrabox.core.diagnostics.SecretSealer
import io.hydrabox.core.storage.StorageDatabase

data class SubscriptionRecord(val id: String, val name: String, val source: Secret, val updatedAtMillis: Long)

/** A row whose body could not be opened with this device's key, and what was said about it. */
data class SubscriptionReadFailure(val id: String, val name: String, val reason: String)

/** What the table holds: the rows that opened, and the rows that did not. */
data class SubscriptionRecords(
    val records: List<SubscriptionRecord> = emptyList(),
    val failures: List<SubscriptionReadFailure> = emptyList(),
)

class SubscriptionStore(private val database: StorageDatabase, private val sealer: SecretSealer, private val opener: SecretOpener) {
    fun save(subscription: SubscriptionRecord) {
        SubscriptionId.validate(subscription.id)
        database.storageDatabaseQueries.upsertSubscription(subscription.id, subscription.name, subscription.source.sealWith(sealer), subscription.updatedAtMillis)
    }

    /**
     * Every row, opened one at a time.
     *
     * The whole list used to be built inside one `map`, so a single body this device's key could
     * no longer open — a restored backup, a rotated key — threw out of the middle of it and the
     * caller, which caught the failure around the whole call, showed an application with no
     * subscriptions at all. The rows that open are worth keeping and the one that does not is
     * worth naming.
     */
    fun read(): SubscriptionRecords {
        val records = mutableListOf<SubscriptionRecord>()
        val failures = mutableListOf<SubscriptionReadFailure>()
        database.storageDatabaseQueries.selectSubscriptions().executeAsList().forEach { row ->
            runCatching { Secret.openWith(row.source_secret, opener) }.fold(
                onSuccess = { source ->
                    records += SubscriptionRecord(row.subscription_id, row.name, source, row.updated_at_millis)
                },
                onFailure = { failure ->
                    failures += SubscriptionReadFailure(
                        id = row.subscription_id,
                        name = row.name,
                        reason = failure::class.simpleName ?: "unreadable",
                    )
                },
            )
        }
        return SubscriptionRecords(records, failures)
    }

    fun all(): List<SubscriptionRecord> = read().records
}

class SubscriptionUpdater(private val store: SubscriptionStore) {
    fun refresh(current: SubscriptionRecord, fetch: (SubscriptionRecord) -> SubscriptionRecord): SubscriptionRecord =
        fetch(current).also(store::save)
}
