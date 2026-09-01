package io.hydrabox.core.subscription

import io.hydrabox.core.diagnostics.Secret
import io.hydrabox.core.diagnostics.SecretOpener
import io.hydrabox.core.diagnostics.SecretSealer
import io.hydrabox.core.storage.StorageDatabase

data class SubscriptionRecord(val id: String, val name: String, val source: Secret, val updatedAtMillis: Long)

class SubscriptionStore(private val database: StorageDatabase, private val sealer: SecretSealer, private val opener: SecretOpener) {
    fun save(subscription: SubscriptionRecord) {
        SubscriptionId.validate(subscription.id)
        database.storageDatabaseQueries.upsertSubscription(subscription.id, subscription.name, subscription.source.sealWith(sealer), subscription.updatedAtMillis)
    }

    fun all(): List<SubscriptionRecord> = database.storageDatabaseQueries.selectSubscriptions().executeAsList().map {
        SubscriptionRecord(it.subscription_id, it.name, Secret.openWith(it.source_secret, opener), it.updated_at_millis)
    }
}

class SubscriptionUpdater(private val store: SubscriptionStore) {
    fun refresh(current: SubscriptionRecord, fetch: (SubscriptionRecord) -> SubscriptionRecord): SubscriptionRecord =
        fetch(current).also(store::save)
}
