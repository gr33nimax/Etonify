package io.hydrabox.platform.android

import android.content.Context
import io.hydrabox.core.config.AUTO_TAG
import io.hydrabox.core.config.RouteData
import io.hydrabox.core.config.TunnelConfigGenerator
import io.hydrabox.core.config.TunnelInput
import io.hydrabox.core.diagnostics.Secret
import io.hydrabox.core.projection.Appearance
import io.hydrabox.core.projection.AppsMode
import io.hydrabox.core.projection.Language
import io.hydrabox.core.projection.LogDetail
import io.hydrabox.core.projection.TlsFragmentation
import io.hydrabox.core.projection.TunnelStack
import io.hydrabox.core.ruleset.RuleSetStatus
import io.hydrabox.core.projection.ServerGroup
import io.hydrabox.core.projection.ServerRef
import io.hydrabox.core.projection.SettingsSummary
import io.hydrabox.core.projection.SourceProblem
import io.hydrabox.core.projection.SubscriptionSummary
import io.hydrabox.core.settings.DEFAULT_PROXY_USERNAME
import io.hydrabox.core.settings.DEFAULT_RUSSIA_DNS_DIRECT_RESOLVER
import io.hydrabox.core.settings.DEFAULT_URL_TEST_URL
import io.hydrabox.core.settings.NotificationTrafficDisplayMode
import io.hydrabox.core.settings.PerformanceMode
import io.hydrabox.core.settings.AppLanguage
import io.hydrabox.core.settings.Settings
import io.hydrabox.core.settings.SettingsStore
import io.hydrabox.core.settings.LogLevel
import io.hydrabox.core.settings.SplitRoutingMode
import io.hydrabox.core.settings.ThemeMode
import io.hydrabox.core.settings.TunStack
import io.hydrabox.core.settings.TlsFragmentationMode
import io.hydrabox.core.settings.normalizeSplitRoutingPackages
import io.hydrabox.core.storage.BackupService
import io.hydrabox.core.storage.BackupTransfer
import io.hydrabox.core.storage.SecretFieldCodec
import io.hydrabox.core.storage.StorageContext
import io.hydrabox.core.storage.StorageDatabase
import io.hydrabox.core.storage.openStorageDriver
import io.hydrabox.core.storage.platformSecretFieldCipher
import io.hydrabox.core.subscription.CatalogOutbound
import io.hydrabox.core.subscription.HydraSubscriptionUri
import io.hydrabox.core.subscription.OutboundCatalogParser
import io.hydrabox.core.subscription.SourceFailure
import io.hydrabox.core.subscription.SubscriptionException
import io.hydrabox.core.subscription.SubscriptionMetadata
import io.hydrabox.core.subscription.SubscriptionRecord
import io.hydrabox.core.subscription.SubscriptionStore

/**
 * Android-side composition of the core stores. Both processes open the same SQLite
 * database: the UI process writes subscriptions and the selection, the `:core` process
 * reads them when it builds a configuration. That is why the engine is SQLite and not a
 * document file.
 */
class AppStore(context: Context) {
    private val appContext = context.applicationContext
    private val driver = openStorageDriver(StorageContext(context.applicationContext), DATABASE_NAME)
    private val database = StorageDatabase(driver)
    private val codec = SecretFieldCodec(platformSecretFieldCipher(driver))
    private val subscriptions = SubscriptionStore(database, codec, codec)
    private val settingsStore = SettingsStore(database, codec, codec)
    private val backups = BackupService(database)
    private val transfer = BackupTransfer(codec, codec)
    private val queries = database.storageDatabaseQueries

    // --- settings -----------------------------------------------------------------

    fun settings(): Settings = runCatching { settingsStore.load() }.getOrElse { defaultSettings() }

    /** Back to the values a fresh install would have, keeping the accepted terms. */
    fun resetSettings() {
        val current = settings()
        saveSettings(
            defaultSettings().copy(
                acceptedLegalVersion = current.acceptedLegalVersion,
                acceptedLegalAtMillis = current.acceptedLegalAtMillis,
            ),
        )
    }

    /** The portable document: secrets opened with this device's key, ready to be encrypted. */
    fun exportDocument(): String = transfer.encode(backups.export())

    /** Restores a document, sealing its secrets with this device's key. Overwrites everything. */
    fun importDocument(document: String) {
        val outcome = backups.import(transfer.decode(document))
        check(outcome is io.hydrabox.core.model.OperationState.Succeeded) { "unsupported_backup_version" }
    }

    fun saveSettings(settings: Settings) = settingsStore.save(settings)

    /** The compiled rule sets on this device, named for the configuration. */
    fun routeData(): RouteData = AdBlockRuleSets.paths(appContext)
        ?.let { RouteData(adBlockPath = it.block, adBlockAllowPath = it.allow) }
        ?: RouteData.None

    fun ruleSetStatus(): RuleSetStatus = AdBlockRuleSets.status(appContext)

    /** Downloads and compiles the blocking rule set. Blocking; call it off the main thread. */
    fun updateRuleSets(): RuleSetStatus = AdBlockRuleSets.update(appContext)

    /** True when the person asked for a local proxy and no system tunnel. */
    fun proxyOnly(settings: Settings = settings()) =
        settings.proxyInboundEnabled && !settings.vpnInboundEnabled

    fun settingsSummary(settings: Settings = settings()) = SettingsSummary(
        economyMode = settings.performanceMode == PerformanceMode.ECONOMY,
        proxyDnsResolver = settings.dnsProxyResolver,
        directDnsResolver = settings.dnsDirectResolver,
        vpnMtu = settings.vpnMtu,
        appsOutsideTunnel = settings.splitRoutingPackages.size,
        statusNotificationEnabled = settings.statusNotificationEnabled,
        blockLeaks = settings.blockLeaks,
        bypassLocalNetwork = settings.bypassLocalNetwork,
        adBlock = settings.adBlockEnabled,
        proxyOnly = proxyOnly(settings),
        proxyPort = settings.proxyMixedPort,
        proxyAllowLan = settings.proxyAllowLan,
        appsMode = when (settings.splitRoutingMode) {
            SplitRoutingMode.OFF -> AppsMode.OFF
            SplitRoutingMode.BYPASS_SELECTED -> AppsMode.BYPASS_SELECTED
            SplitRoutingMode.ONLY_SELECTED -> AppsMode.ONLY_SELECTED
        },
        appearance = when (settings.themeMode) {
            ThemeMode.SYSTEM -> Appearance.SYSTEM
            ThemeMode.LIGHT -> Appearance.LIGHT
            ThemeMode.DARK -> Appearance.DARK
        },
        strictRoute = settings.vpnStrictRoute,
        stack = when (settings.vpnTunStack) {
            TunStack.SYSTEM -> TunnelStack.SYSTEM
            TunStack.GVISOR -> TunnelStack.GVISOR
            TunStack.MIXED -> TunnelStack.MIXED
        },
        fragmentation = when (settings.tlsFragmentationMode) {
            TlsFragmentationMode.DISABLED -> TlsFragmentation.OFF
            TlsFragmentationMode.RECORD -> TlsFragmentation.RECORD
            TlsFragmentationMode.FRAGMENT -> TlsFragmentation.FRAGMENT
        },
        logDetail = when (settings.logLevel) {
            LogLevel.TRACE -> LogDetail.TRACE
            LogLevel.DEBUG -> LogDetail.DEBUG
            LogLevel.INFO -> LogDetail.INFO
            LogLevel.WARN -> LogDetail.WARN
            LogLevel.ERROR -> LogDetail.ERROR
        },
        languageChoice = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU,
        language = when (settings.language) {
            AppLanguage.SYSTEM -> Language.SYSTEM
            AppLanguage.RUSSIAN -> Language.RUSSIAN
            AppLanguage.ENGLISH -> Language.ENGLISH
        },
    )

    /** Launchable apps, with the ones currently kept outside the tunnel marked. */
    fun installedApps(): List<io.hydrabox.core.projection.InstalledApp> {
        val excluded = settings().splitRoutingPackages.toSet()
        val manager = appContext.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return runCatching {
            manager.queryIntentActivities(intent, 0).mapNotNull { resolved ->
                val name = resolved.activityInfo?.packageName ?: return@mapNotNull null
                if (name == appContext.packageName) return@mapNotNull null
                io.hydrabox.core.projection.InstalledApp(
                    packageName = name,
                    label = runCatching { resolved.loadLabel(manager).toString() }.getOrDefault(name),
                    excluded = name in excluded,
                )
            }.distinctBy { it.packageName }
        }.getOrDefault(emptyList())
    }

    fun toggleExcludedApp(packageName: String) {
        val current = settings()
        val updated = if (packageName in current.splitRoutingPackages) {
            current.splitRoutingPackages - packageName
        } else {
            current.splitRoutingPackages + packageName
        }
        saveSettings(current.copy(splitRoutingPackages = normalizeSplitRoutingPackages(updated)))
    }

    fun setSplitRoutingPackages(raw: String) = saveSettings(
        settings().copy(
            splitRoutingPackages = normalizeSplitRoutingPackages(raw.split(',', '\n', ' ')),
        ),
    )

    // --- subscriptions ------------------------------------------------------------

    fun records(): List<SubscriptionRecord> = runCatching { subscriptions.all() }.getOrDefault(emptyList())

    /**
     * Accepts a subscription URL or an inline body. A URL is fetched now and its body is
     * stored, so the core process never needs the network to build a configuration.
     */
    fun addSubscription(name: String, source: String): String {
        val trimmed = source.trim()
        val remote = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        val opened = if (remote) retrieve(trimmed) else Opened(openInline(trimmed), null)
        val catalog = OutboundCatalogParser.parse(opened.document)
        val id = "sub-" + (records().size + 1) + "-" + trimmed.hashCode().toUInt().toString(16)
        val inspection = if (HydraCoreGate.looksHydra(opened.document)) HydraCoreGate.inspect(opened.document) else null
        val label = name.trim().takeIf(String::isNotEmpty)
            ?: inspection?.displayName?.takeIf(String::isNotEmpty)
            ?: catalog.selectable.firstOrNull()?.tag?.takeIf { catalog.selectable.size == 1 }
            ?: "Subscription ${records().size + 1}"
        subscriptions.save(SubscriptionRecord(id, label, Secret.of(opened.document), System.currentTimeMillis()))
        if (remote) queries.upsertValue(urlKey(id), HydraSubscriptionUri.withoutSecretFragment(trimmed).encodeToByteArray())
        rememberMetadata(id, opened.metadata)
        rememberFailure(id, null)
        // The key is a secret: it goes into the encrypted field, never beside the URL.
        opened.key?.let { key -> queries.upsertSetting(keyKey(id), "", codec.seal(key)) }
        inspection?.notAfter?.let { queries.upsertValue(validityKey(id), it.encodeToByteArray()) }
        if (selectedTag() == null) catalog.defaultTag?.let(::select)
            ?: catalog.selectable.firstOrNull()?.let { select(it.tag) }
        return id
    }

    /** A body, the key that opened it, and what the server said about the subscription. */
    private data class Opened(
        val document: String,
        val key: String?,
        val metadata: SubscriptionMetadata = SubscriptionMetadata(),
    )

    /**
     * Fetches and, when the source is an encrypted Hydra envelope, has the core open it.
     * The key comes from the URL fragment and is stripped before the request goes out.
     */
    private fun retrieve(url: String, storedKey: String? = null): Opened {
        require(!HydraSubscriptionUri.hasKeyQueryParameter(url)) {
            "the Hydra key belongs in the URL fragment, not the query, or it is sent to the server"
        }
        val key = HydraSubscriptionUri.keyOf(url) ?: storedKey
        val fetched = SubscriptionFetcher.fetch(appContext, HydraSubscriptionUri.withoutSecretFragment(url))
        val body = fetched.body
        if (!HydraCoreGate.looksEncrypted(body)) {
            if (HydraCoreGate.looksHydra(body)) HydraCoreGate.validate(body)
            return Opened(body, key, fetched.metadata)
        }
        if (key == null) throw SubscriptionException(SourceFailure.ENCRYPTED_WITHOUT_KEY)
        return Opened(HydraCoreGate.open(body, key), key, fetched.metadata)
    }

    /** An inline body: still validated by the core when it claims to be a Hydra document. */
    private fun openInline(body: String): String {
        check(!HydraCoreGate.looksEncrypted(body)) {
            "paste the subscription URL including its #hydra-key fragment, not the encrypted body"
        }
        if (HydraCoreGate.looksHydra(body)) HydraCoreGate.validate(body)
        return body
    }

    fun refreshSubscription(id: String) {
        val url = queries.selectValue(urlKey(id)).executeAsOneOrNull()?.decodeToString()
        checkNotNull(url) { "subscription has no source URL to refresh" }
        val stored: String? = queries.selectSecretValue(keyKey(id)).executeAsOneOrNull()
            ?.secret_value?.let(codec::open)
        // A failed refresh is remembered on the source itself, so its row can say what is
        // wrong long after the message about it has gone.
        val opened = try {
            retrieve(url, stored)
        } catch (failure: SubscriptionException) {
            rememberFailure(id, failure.failure)
            throw failure
        }
        rememberMetadata(id, opened.metadata)
        rememberFailure(id, null)
        OutboundCatalogParser.parse(opened.document)
        val current = records().firstOrNull { it.id == id } ?: error("unknown subscription")
        subscriptions.save(SubscriptionRecord(id, current.name, Secret.of(opened.document), System.currentTimeMillis()))
        if (HydraCoreGate.looksHydra(opened.document)) {
            HydraCoreGate.inspect(opened.document).notAfter
                ?.let { queries.upsertValue(validityKey(id), it.encodeToByteArray()) }
        }
    }

    fun removeSubscription(id: String) {
        queries.deleteSubscription(id)
        queries.upsertValue(urlKey(id), ByteArray(0))
    }

    fun renameSubscription(id: String, name: String) {
        val current = records().firstOrNull { it.id == id } ?: return
        subscriptions.save(SubscriptionRecord(id, name.trim().ifEmpty { current.name }, current.source, current.updatedAtMillis))
    }

    private fun catalogs(): List<Pair<SubscriptionRecord, List<CatalogOutbound>>> = records().map { record ->
        record to runCatching {
            record.source.use(OutboundCatalogParser::parse).outbounds.map { it.copy(scope = record.id) }
        }.getOrDefault(emptyList())
    }

    fun summaries(): List<SubscriptionSummary> = catalogs().map { (record, outbounds) ->
        SubscriptionSummary(
            id = record.id,
            name = record.name,
            serverCount = outbounds.count(CatalogOutbound::selectable),
            updatedAtMillis = record.updatedAtMillis,
            expiresAt = validityOf(record.id) ?: expiryOf(record.id),
            encrypted = queries.selectSecretValue(keyKey(record.id)).executeAsOneOrNull()?.secret_value != null,
            problem = problemOf(record.id, outbounds),
            usedTraffic = metadataOf(record.id, "used")?.toLongOrNull()?.let(::readableBytes),
            totalTraffic = metadataOf(record.id, "total")?.toLongOrNull()?.let(::readableBytes),
        )
    }

    /**
     * What is wrong with a source, as one of four situations. The parser's own message is a
     * developer sentence; it goes to diagnostics, not to the person.
     */
    private fun problemOf(id: String, outbounds: List<CatalogOutbound>): SourceProblem? {
        val expired = validityOf(id)?.let { it < java.time.Instant.now().toString() } == true ||
            metadataOf(id, "expire")?.toLongOrNull()?.let { it < System.currentTimeMillis() / 1000 } == true
        val failure = failureOf(id)
        return when {
            expired || failure == SourceFailure.EXPIRED -> SourceProblem.EXPIRED
            failure in setOf(SourceFailure.TIMEOUT, SourceFailure.NO_NETWORK, SourceFailure.TLS) ->
                SourceProblem.UNREACHABLE
            failure in setOf(
                SourceFailure.HTTP_STATUS,
                SourceFailure.HTML_RESPONSE,
                SourceFailure.INVALID_CONTENT,
                SourceFailure.INVALID_URL,
                SourceFailure.CREDENTIALS_REQUIRE_HTTPS,
                SourceFailure.UNSAFE_REDIRECT,
                SourceFailure.TOO_MANY_REDIRECTS,
                SourceFailure.TOO_LARGE,
                SourceFailure.ENCRYPTED_WITHOUT_KEY,
            ) -> SourceProblem.REJECTED
            outbounds.isEmpty() && parseError(id) != null -> SourceProblem.REJECTED
            outbounds.none(CatalogOutbound::selectable) -> SourceProblem.EMPTY
            else -> null
        }
    }

    /** The provider's own expiry, as a date rather than a number of seconds. */
    private fun expiryOf(id: String): String? = metadataOf(id, "expire")?.toLongOrNull()
        ?.let { java.time.Instant.ofEpochSecond(it).toString().substringBefore('T') }

    private fun readableBytes(value: Long): String {
        val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit += 1
        }
        val scaled = (amount * 10).toLong()
        return if (unit == 0) "${'$'}{value} B" else "${'$'}{scaled / 10}.${'$'}{scaled % 10} ${'$'}{units[unit]}"
    }

    /** Servers grouped by the source they came from, which is how a person recognises them. */
    fun serverGroups(): List<ServerGroup> = catalogs().mapNotNull { (record, outbounds) ->
        val servers = outbounds.filter(CatalogOutbound::selectable).map { outbound ->
            ServerRef(id = outbound.tag, displayName = outbound.tag, sourceId = record.id)
        }
        if (servers.isEmpty()) null else ServerGroup(record.id, record.name, servers)
    }

    /**
     * The automatic choice, offered only when there is more than nothing to choose from.
     * It is a real outbound in the generated configuration, which is why the runtime can
     * report which server it landed on.
     */
    fun autoServer(): ServerRef? = if (serverGroups().isEmpty()) null else {
        ServerRef(id = AUTO_TAG, displayName = AUTO_TAG, auto = true)
    }

    fun parseError(id: String): String? = records().firstOrNull { it.id == id }?.let { record ->
        runCatching { record.source.use(OutboundCatalogParser::parse) }.exceptionOrNull()?.message
    }

    // --- selection and configuration ----------------------------------------------

    fun selectedTag(): String? = queries.selectValue(SELECTED_KEY).executeAsOneOrNull()
        ?.decodeToString()?.takeIf(String::isNotEmpty)

    fun select(tag: String) = queries.upsertValue(SELECTED_KEY, tag.encodeToByteArray())

    /** Builds the configuration the core will run. Returns null when nothing is usable. */
    fun generateConfig(): String? {
        val outbounds = catalogs().flatMap { it.second }
        if (outbounds.none(CatalogOutbound::selectable)) return null
        val settings = settings()
        return TunnelConfigGenerator.generate(
            TunnelInput(
                outbounds = outbounds,
                selectedTag = selectedTag(),
                proxyDnsResolver = settings.dnsProxyResolver,
                directDnsResolver = settings.dnsDirectResolver,
                mtu = settings.vpnMtu,
                // One list, two meanings: the mode decides whether the chosen apps are the
                // ones that skip the tunnel or the only ones allowed into it.
                excludePackages = if (settings.splitRoutingMode == SplitRoutingMode.BYPASS_SELECTED) {
                    settings.splitRoutingPackages
                } else {
                    emptyList()
                },
                includePackages = if (settings.splitRoutingMode == SplitRoutingMode.ONLY_SELECTED) {
                    settings.splitRoutingPackages
                } else {
                    emptyList()
                },
                urlTestUrl = settings.urlTestUrl,
                urlTestIntervalSeconds = settings.urlTestIntervalSeconds,
                blockLeaks = settings.blockLeaks,
                bypassLocalNetwork = settings.bypassLocalNetwork,
                strictRoute = settings.vpnStrictRoute,
                tunStack = settings.vpnTunStack.name.lowercase(),
                tcpFastOpen = settings.tcpFastOpen,
                tcpMultiPath = settings.tcpMultiPath,
                tlsFragmentation = settings.tlsFragmentationMode.name.lowercase(),
                urlTestToleranceMillis = if (settings.urlTestStrictTolerance) 1 else 50,
                interruptExistingConnections = settings.interruptExistingConnections,
                logLevel = settings.logLevel.name.lowercase(),
                // At least one inbound has to exist, or the core carries nothing: turning
                // both off is not a state the product allows.
                vpnInbound = settings.vpnInboundEnabled || !settings.proxyInboundEnabled,
                proxyInbound = settings.proxyInboundEnabled,
                proxyListen = if (settings.proxyAllowLan) "0.0.0.0" else "127.0.0.1",
                proxyPort = settings.proxyMixedPort,
                proxyUsername = settings.proxyUsername,
                proxyPassword = settings.proxyPassword?.use { it },
                adBlock = settings.adBlockEnabled,
                routeData = routeData(),
            ),
        )
    }

    // --- start diagnostics --------------------------------------------------------

    /**
     * The core process records why a start failed; the UI process reads it back. Without
     * this the only symptom is a tunnel that never comes up and no reason anywhere.
     */
    fun recordStartFailure(reason: String) = queries.upsertValue(START_FAILURE_KEY, reason.encodeToByteArray())

    fun clearStartFailure() = queries.upsertValue(START_FAILURE_KEY, ByteArray(0))

    fun startFailure(): String? = queries.selectValue(START_FAILURE_KEY).executeAsOneOrNull()
        ?.decodeToString()?.takeIf(String::isNotEmpty)

    /** The configuration as the core will see it, for the diagnostics screen. */
    fun configPreview(): String? = runCatching { generateConfig() }.getOrElse { "generation failed: ${it.message}" }

    private fun metadataKey(id: String, field: String) = "subscription.$id.$field"

    /** What the provider said, kept so the row can show it without another request. */
    fun rememberMetadata(id: String, metadata: SubscriptionMetadata) {
        if (metadata.empty) return
        listOf(
            "used" to metadata.usedBytes?.toString(),
            "total" to metadata.totalBytes?.toString(),
            "expire" to metadata.expiresAtEpochSeconds?.toString(),
            "title" to metadata.title,
        ).forEach { (field, value) ->
            queries.upsertValue(metadataKey(id, field), (value ?: "").encodeToByteArray())
        }
    }

    private fun metadataOf(id: String, field: String): String? =
        queries.selectValue(metadataKey(id, field)).executeAsOneOrNull()
            ?.decodeToString()?.takeIf(String::isNotEmpty)

    /** The last reason this source could not be read, in the words the product uses. */
    fun rememberFailure(id: String, failure: SourceFailure?) =
        queries.upsertValue(metadataKey(id, "failure"), (failure?.name ?: "").encodeToByteArray())

    private fun failureOf(id: String): SourceFailure? = metadataOf(id, "failure")
        ?.let { name -> runCatching { SourceFailure.valueOf(name) }.getOrNull() }

    private fun urlKey(id: String) = "subscription.$id.url"
    private fun keyKey(id: String) = "subscription.$id.hydra-key"
    private fun validityKey(id: String) = "subscription.$id.not-after"

    fun validityOf(id: String): String? = queries.selectValue(validityKey(id)).executeAsOneOrNull()
        ?.decodeToString()?.takeIf(String::isNotEmpty)

    private fun defaultSettings() = Settings(
        performanceMode = PerformanceMode.STANDARD,
        urlTestUrl = DEFAULT_URL_TEST_URL,
        urlTestIntervalSeconds = 600,
        urlTestTimeoutSeconds = 5,
        urlTestConcurrency = 4,
        urlTestUnavailableCheckIntervalSeconds = 60,
        locationLookupLimit = 16,
        locationLookupTimeoutSeconds = 5,
        locationLookupConcurrency = 4,
        russiaDnsDirectResolver = DEFAULT_RUSSIA_DNS_DIRECT_RESOLVER,
        dnsDirectResolver = "1.1.1.1",
        dnsProxyResolver = "https://dns.cloudflare.com/dns-query",
        memoryLimitEnabled = false,
        memoryLimitWarningDismissed = false,
        statusNotificationEnabled = true,
        notificationTrafficDisplayMode = NotificationTrafficDisplayMode.BOTH,
        acceptedLegalVersion = "",
        acceptedLegalAtMillis = null,
        tlsFragmentationMode = TlsFragmentationMode.DISABLED,
        proxyUsername = DEFAULT_PROXY_USERNAME,
        proxyPassword = null,
        proxySort = "name",
        vpnMtu = 9000,
        splitRoutingPackages = emptyList(),
    )

    private companion object {
        const val DATABASE_NAME = "hydrabox.db"
        const val SELECTED_KEY = "runtime.selected.outbound"
        const val START_FAILURE_KEY = "runtime.last.start.failure"
    }
}
