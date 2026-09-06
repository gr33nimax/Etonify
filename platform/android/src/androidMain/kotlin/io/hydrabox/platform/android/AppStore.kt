package io.hydrabox.platform.android

import android.content.Context
import io.hydrabox.core.config.AUTO_TAG
import io.hydrabox.core.config.OutboundTags
import io.hydrabox.core.config.RouteData
import io.hydrabox.core.config.TunnelConfigGenerator
import io.hydrabox.core.config.TunnelInput
import io.hydrabox.core.diagnostics.Secret
import io.hydrabox.core.projection.Appearance
import io.hydrabox.core.projection.AppsMode
import io.hydrabox.core.projection.DnsMode
import io.hydrabox.core.projection.NotificationDetail
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
import io.hydrabox.core.settings.NotificationTrafficDisplayMode
import io.hydrabox.core.settings.PerformanceMode
import io.hydrabox.core.settings.AppLanguage
import io.hydrabox.core.settings.DnsStrategy
import io.hydrabox.core.settings.Settings
import io.hydrabox.core.settings.SettingsCodec
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
import io.hydrabox.core.subscription.SubscriptionId
import io.hydrabox.core.subscription.SubscriptionMetadata
import io.hydrabox.core.subscription.SubscriptionRecord
import io.hydrabox.core.subscription.SubscriptionRecords
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
    private val writeLock = Any()

    /** Whether the pre-4 journal blob has already been emptied in this process. */
    @Volatile private var legacyJournalDropped = false
    private val settingsStore = SettingsStore(database, codec, codec)
    private val backups = BackupService(database)
    private val transfer = BackupTransfer(codec, codec)
    private val queries = database.storageDatabaseQueries

    // --- settings -----------------------------------------------------------------

    fun settings(): Settings = runCatching { settingsStore.load() }
        .onFailure { HydraLog.error(AREA, "the settings could not be read; using the defaults", it) }
        .getOrElse { defaultSettings() }

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
        bootstrapDnsResolver = settings.bootstrapDnsResolver,
        vpnMtu = settings.vpnMtu,
        appsOutsideTunnel = settings.splitRoutingPackages.size,
        statusNotificationEnabled = settings.statusNotificationEnabled,
        notificationDetail = if (!settings.statusNotificationEnabled) {
            NotificationDetail.OFF
        } else {
            when (settings.notificationTrafficDisplayMode) {
                NotificationTrafficDisplayMode.SPEED -> NotificationDetail.SPEED
                NotificationTrafficDisplayMode.TOTAL -> NotificationDetail.TOTAL
                NotificationTrafficDisplayMode.BOTH -> NotificationDetail.BOTH
            }
        },
        dnsMode = when (settings.dnsStrategy) {
            DnsStrategy.AUTO -> DnsMode.AUTO
            DnsStrategy.IPV4_ONLY -> DnsMode.IPV4
            DnsStrategy.IPV6_ONLY -> DnsMode.IPV6
        },
        interruptConnections = settings.interruptExistingConnections,
        fakeIp = settings.fakeIpEnabled,
        blockLeaks = settings.blockLeaks,
        bypassLocalNetwork = settings.bypassLocalNetwork,
        adBlock = settings.adBlockEnabled,
        proxyOnly = proxyOnly(settings),
        proxyPort = settings.proxyMixedPort,
        proxyAllowLan = settings.proxyAllowLan,
        tcpFastOpen = settings.tcpFastOpen,
        tcpMultiPath = settings.tcpMultiPath,
        appsMode = when (settings.splitRoutingMode) {
            SplitRoutingMode.OFF -> AppsMode.OFF
            SplitRoutingMode.BYPASS_SELECTED -> AppsMode.BYPASS_SELECTED
            SplitRoutingMode.ONLY_SELECTED -> AppsMode.ONLY_SELECTED
        },
        dynamicColour = settings.dynamicColour,
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
            LogLevel.OFF -> LogDetail.OFF
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

    fun records(): List<SubscriptionRecord> = stored().records

    /**
     * The subscription table, with the rows that could not be opened kept separately.
     *
     * A failure to open one body is not "there are no subscriptions": the sources screen has to
     * keep showing the sources that work and say what is wrong with the one that does not.
     */
    private fun stored(): SubscriptionRecords = runCatching { subscriptions.read() }
        .onFailure { HydraLog.error(AREA, "the subscription table could not be read", it) }
        .getOrDefault(SubscriptionRecords())
        .also { read ->
            read.failures.forEach { failure ->
                HydraLog.error(AREA, "the stored body of ${failure.id} could not be opened: ${failure.reason}")
            }
        }

    /**
     * Accepts a subscription URL or an inline body. A URL is fetched now and its body is
     * stored, so the core process never needs the network to build a configuration.
     */
    fun addSubscription(name: String, source: String): String = mutate {
        val trimmed = source.trim()
        val remote = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        HydraLog.info(AREA, if (remote) "adding a remote source" else "adding an inline source, ${trimmed.length} chars")
        val opened = if (remote) retrieve(trimmed) else Opened(openInline(trimmed), null)
        val catalog = parseCatalog(opened.document)
        // The same address is the same source: adding a link twice refreshes the entry it
        // already has instead of leaving two that drift apart.
        val known = records().associateBy { it.id }
        val existing = if (remote) idOfUrl(trimmed) else null
        val id = existing ?: SubscriptionId.of(trimmed, known.keys)
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
        id
    }

    /**
     * Parses a document and says in the journal what came out of it: the format, how many
     * servers, and every line that was skipped. A subscription that yields three servers out
     * of four is a normal outcome, and the fourth has to be named somewhere.
     */
    private fun parseCatalog(document: String): io.hydrabox.core.subscription.OutboundCatalog {
        val outcome = OutboundCatalogParser.inspect(document)
        HydraLog.info(
            AREA,
            "parsed as ${outcome.catalog.format.name.lowercase()}: " +
                "${outcome.catalog.selectable.size} of ${outcome.catalog.outbounds.size} selectable",
        )
        outcome.skipped.forEach { HydraLog.warn(AREA, "skipped an entry: $it") }
        return outcome.catalog
    }

    /** Which stored source already points at this address, if any. */
    private fun idOfUrl(url: String): String? {
        val target = HydraSubscriptionUri.withoutSecretFragment(url)
        return records().firstOrNull { record ->
            queries.selectValue(urlKey(record.id)).executeAsOneOrNull()?.decodeToString() == target
        }?.id
    }

    /**
     * Two processes share this database and the interface fires operations from a background
     * thread, so a write is serialised here. 1.x carried a write lock in its own store
     * (`_withSubscriptionWriteLock`) for the same reason.
     */
    private fun <T> mutate(block: () -> T): T = synchronized(writeLock) { block() }

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
        HydraLog.debug(AREA, "retrieving a source")
        require(!HydraSubscriptionUri.hasKeyQueryParameter(url)) {
            "the Hydra key belongs in the URL fragment, not the query, or it is sent to the server"
        }
        val key = HydraSubscriptionUri.keyOf(url) ?: storedKey
        val fetched = SubscriptionFetcher.fetch(
            context = appContext,
            url = HydraSubscriptionUri.withoutSecretFragment(url),
            // A link that carries a Hydra key is a Hydra subscription, and only those receive
            // the per-origin identifier — which is what the shipped privacy policy promises.
            identify = key != null,
        )
        val body = fetched.body
        if (!HydraCoreGate.looksEncrypted(body)) {
            if (HydraCoreGate.looksHydra(body)) HydraCoreGate.validate(body)
            return Opened(body, key, fetched.metadata)
        }
        if (key == null) {
            HydraLog.error(AREA, "the body is an encrypted envelope and the link carries no key")
            throw SubscriptionException(SourceFailure.ENCRYPTED_WITHOUT_KEY)
        }
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

    /**
     * Asks the provider only how much of the plan is gone, and writes nothing else.
     *
     * A full refresh replaces the stored document, which means a new server list, a new parse
     * and a new chance to fail. Karing separates the two for good reason: wanting to know the
     * remaining traffic is not wanting a different set of servers.
     */
    fun refreshUsage(id: String) = mutate {
        val url = urlOf(id) ?: error("subscription has no source URL to refresh")
        HydraLog.info(AREA, "refreshing the usage figures only")
        val fetched = SubscriptionFetcher.fetch(appContext, url)
        rememberMetadata(id, fetched.metadata)
        rememberFailure(id, null)
    }

    fun refreshSubscription(id: String) = mutate {
        HydraLog.info(AREA, "refreshing a source")
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
        parseCatalog(opened.document)
        val current = records().firstOrNull { it.id == id } ?: error("unknown subscription")
        subscriptions.save(SubscriptionRecord(id, current.name, Secret.of(opened.document), System.currentTimeMillis()))
        if (HydraCoreGate.looksHydra(opened.document)) {
            HydraCoreGate.inspect(opened.document).notAfter
                ?.let { queries.upsertValue(validityKey(id), it.encodeToByteArray()) }
        }
    }

    /**
     * Removes a source and everything that was ever recorded about it.
     *
     * Deleted, not blanked, and by prefix rather than by a hand-written list. `SubscriptionId.of`
     * is a hash of the address, so the same link always comes back as the same id — and the two
     * keys the old list forgot were the ones that made that visible. `enabled` survived removal,
     * so re-adding a source that had been switched off produced a source with no servers and no
     * explanation; `hydra-key` survived it too, which left the material for decrypting a
     * subscription on the device after the subscription was gone.
     */
    fun removeSubscription(id: String) = mutate {
        queries.deleteSubscription(id)
        queries.deleteMetadataWithPrefix(metadataPrefix(id))
        queries.deleteSetting(keyKey(id))
    }

    fun renameSubscription(id: String, name: String) {
        val current = records().firstOrNull { it.id == id } ?: return
        subscriptions.save(SubscriptionRecord(id, name.trim().ifEmpty { current.name }, current.source, current.updatedAtMillis))
    }

    /** Whether a source contributes servers. Absent metadata means yes, as it always did. */
    fun sourceEnabled(id: String): Boolean = metadataOf(id, "enabled") != "0"

    fun setSourceEnabled(id: String, enabled: Boolean) =
        queries.upsertValue(metadataKey(id, "enabled"), (if (enabled) "1" else "0").encodeToByteArray())

    /**
     * Whether the route in use is the VK transport.
     *
     * The core publishes transport health for that transport and for nothing else, so on any
     * other server the same numbers describe something the person did not ask for. An
     * automatic choice counts as "not it": which server it lands on is decided inside the core,
     * and a health snapshot must not be allowed to speak for a route we cannot name.
     */
    fun selectedIsCallTransport(): Boolean = selectedTag()?.let(::isCallTransport) == true

    /**
     * Whether a tag names the VK transport. The configuration leaves that outbound out until it
     * is the chosen route, so this is also the answer to "does switching to it need the core
     * started again".
     */
    fun isCallTransport(tag: String): Boolean = activeCatalogs().any { (_, outbounds) ->
        outbounds.any { it.tag == tag && it.type.equals(CALL_TYPE, ignoreCase = true) }
    }

    /** Only the sources that are switched on, which is what the configuration may use. */
    private fun activeCatalogs() = catalogs().filter { (record, _) -> sourceEnabled(record.id) }

    /**
     * Every source and what it contributes, with tags already made unique across all of them.
     *
     * The uniqueness pass belongs here rather than only in the generator: these are the tags the
     * screens show, the tag a chosen server is stored under, and the tag the core is told to route
     * through, and all three have to be the same string. Two subscriptions from one provider hand
     * over the same names, and the core refuses a configuration that holds a tag twice.
     */
    private fun catalogs(): List<Pair<SubscriptionRecord, List<CatalogOutbound>>> {
        val parsed = records().map { record ->
            record to runCatching {
                record.source.use(OutboundCatalogParser::parse).outbounds.map { it.copy(scope = record.id) }
            }.getOrDefault(emptyList())
        }
        val unique = OutboundTags.normalize(parsed.flatMap { it.second }).outbounds
            .groupBy(CatalogOutbound::scope)
        return parsed.map { (record, outbounds) -> record to (unique[record.id] ?: outbounds) }
    }

    fun summaries(): List<SubscriptionSummary> = catalogs().map { (record, outbounds) ->
        SubscriptionSummary(
            id = record.id,
            name = record.name,
            serverCount = outbounds.count(CatalogOutbound::selectable),
            updatedAtMillis = record.updatedAtMillis,
            // Both validity fields end up as a plain day: a person reads 2026-10-12, not
            // 2026-10-12T00:00:00Z, and the row has one line for it.
            expiresAt = (validityOf(record.id) ?: expiryOf(record.id))?.substringBefore("T"),
            encrypted = queries.selectSecretValue(keyKey(record.id)).executeAsOneOrNull()?.secret_value != null,
            problem = problemOf(record.id, outbounds),
            // A provider that reports usage without a cap — `total=0` — still knows how much
            // has gone through, and that is worth showing on its own.
            usedTraffic = metadataOf(record.id, "used")?.toLongOrNull()?.let(::readableBytes),
            totalTraffic = metadataOf(record.id, "total")?.toLongOrNull()?.takeIf { it > 0 }?.let(::readableBytes),
            usedBytes = metadataOf(record.id, "used")?.toLongOrNull(),
            totalBytes = metadataOf(record.id, "total")?.toLongOrNull()?.takeIf { it > 0 },
            expiresInDays = daysLeft(record.id),
            updateIntervalHours = metadataOf(record.id, "interval")?.toIntOrNull()?.takeIf { it > 0 },
            updatedAt = readableDay(record.updatedAtMillis),
            protocols = outbounds.filter(CatalogOutbound::selectable)
                .groupingBy { it.type.uppercase() }.eachCount(),
            link = urlOf(record.id),
            enabled = sourceEnabled(record.id),
        )
    } + stored().failures.map { failure ->
        // A row whose body will not open still has a name and still has to be removable.
        SubscriptionSummary(
            id = failure.id,
            name = failure.name,
            serverCount = 0,
            updatedAtMillis = 0,
            problem = SourceProblem.REJECTED,
            link = urlOf(failure.id),
            enabled = sourceEnabled(failure.id),
        )
    }

    /**
     * How many days the plan still has, from whichever of the two validity fields the document
     * carried. Negative days are not shown as "minus three": an expired source is a problem
     * state, and [problemOf] already says so.
     */
    /** The day a source was last read, as a person reads a date. */
    private fun readableDay(millis: Long): String? = millis.takeIf { it > 0 }?.let {
        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
    }

    private fun daysLeft(id: String): Int? {
        val seconds = metadataOf(id, "expire")?.toLongOrNull()
            ?: validityOf(id)?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() }
            ?: return null
        val left = seconds - System.currentTimeMillis() / 1000
        return if (left <= 0) 0 else (left / 86_400).toInt()
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
                SourceFailure.CORE_TOO_OLD,
            ) -> SourceProblem.REJECTED
            outbounds.isEmpty() && parseError(id) != null -> SourceProblem.REJECTED
            outbounds.none(CatalogOutbound::selectable) -> SourceProblem.EMPTY
            else -> null
        }
    }

    /** The provider's own expiry, as a date rather than a number of seconds. */
    private fun expiryOf(id: String): String? = metadataOf(id, "expire")?.toLongOrNull()
        ?.let { java.time.Instant.ofEpochSecond(it).toString().substringBefore('T') }

    /** One implementation, in the projection, where the screens already read it from. */
    private fun readableBytes(value: Long): String = io.hydrabox.core.projection.readableBytes(value)

    /** Servers grouped by the source they came from, which is how a person recognises them. */
    fun serverGroups(): List<ServerGroup> = activeCatalogs().mapNotNull { (record, outbounds) ->
        val servers = outbounds.filter(CatalogOutbound::selectable).map { outbound ->
            ServerRef(
                id = outbound.tag,
                displayName = outbound.label ?: outbound.tag,
                sourceId = record.id,
                type = outbound.type.takeIf(String::isNotBlank),
            )
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
    fun generateConfig(selectedTag: String? = selectedTag()): String? {
        val outbounds = activeCatalogs().flatMap { it.second }
        if (outbounds.none(CatalogOutbound::selectable)) return null
        val settings = settings()
        return TunnelConfigGenerator.generate(
            TunnelInput(
                outbounds = outbounds,
                selectedTag = selectedTag,
                proxyDnsResolver = settings.dnsProxyResolver,
                directDnsResolver = settings.dnsDirectResolver,
                bootstrapDnsResolver = settings.bootstrapDnsResolver,
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
                dnsStrategy = settings.dnsStrategy.name.lowercase(),
                fakeIp = settings.fakeIpEnabled,
                routeData = routeData(),
                // Only a debug build, only loopback. pprof hands out stacks and heap contents,
                // and this is the process that holds the tunnel.
                debugListen = if (BuildConfig.DEBUG) "127.0.0.1:$DEBUG_PPROF_PORT" else "",
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

    /** The outbound type of the VK transport, as the subscription writes it. */
    private val CALL_TYPE = "call"

    private fun metadataKey(id: String, field: String) = "subscription.$id.$field"

    /**
     * Every metadata key belonging to one source, as a `LIKE` pattern.
     *
     * The trailing dot is what keeps `sub-abcd1234` from matching `sub-abcd1234-2`, and the
     * wildcards an id could contain are escaped so an id can never widen the pattern.
     */
    private fun metadataPrefix(id: String) =
        "subscription." + id.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%"

    /** What the provider said, kept so the row can show it without another request. */
    fun rememberMetadata(id: String, metadata: SubscriptionMetadata) {
        if (metadata.empty) return
        listOf(
            "used" to metadata.usedBytes?.toString(),
            "total" to metadata.totalBytes?.toString(),
            "expire" to metadata.expiresAtEpochSeconds?.toString(),
            "interval" to metadata.updateIntervalHours?.toString(),
            "title" to metadata.title,
        ).forEach { (field, value) ->
            queries.upsertValue(metadataKey(id, field), (value ?: "").encodeToByteArray())
        }
    }

    private fun metadataOf(id: String, field: String): String? =
        queries.selectValue(metadataKey(id, field)).executeAsOneOrNull()
            ?.decodeToString()?.takeIf(String::isNotEmpty)

    /**
     * Why the last import failed, kept for the diagnostics screen. A person who cannot add a
     * subscription has to be able to say what happened without a cable and a log viewer.
     */
    fun rememberImportFailure(failure: Throwable?) {
        val text = failure?.let { error ->
            val typed = (error as? SubscriptionException)
            buildString {
                append(typed?.failure?.name ?: error::class.java.simpleName)
                typed?.httpStatus?.let { append(" http=").append(it) }
                error.message?.takeIf { it.isNotEmpty() && typed == null }?.let { append(": ").append(it.take(120)) }
            }
        }
        queries.upsertValue(IMPORT_FAILURE_KEY, (text ?: "").encodeToByteArray())
    }

    fun importFailure(): String? = queries.selectValue(IMPORT_FAILURE_KEY).executeAsOneOrNull()
        ?.decodeToString()?.takeIf(String::isNotEmpty)

    /**
     * Lines the core process wants the diagnostics screen to show. The two processes share
     * nothing but this database, and a person reading the journal in the interface has to see
     * what happened in the core — otherwise the only copy is in `logcat`, which needs a cable.
     *
     * They arrive in batches on purpose: at debug level the core emits hundreds of lines a
     * second, and one transaction per line is a write storm on the main database.
     *
     * Appended, never rewritten. The previous version read the whole blob out of
     * `storage_metadata`, decoded it, re-joined it with the new lines and wrote all of it back —
     * one transaction carrying the entire journal, fifty times a minute for as long as a tunnel
     * was up, which measured out as one second of retained history for the price of a full write
     * and an fsync every 1.2 s. Each kind is trimmed to its own budget, so tracing cannot evict
     * the operational entry that explains a failure.
     */
    fun recordJournal(entries: List<HydraLog.Entry>) = synchronized(writeLock) {
        if (entries.isEmpty()) return@synchronized
        database.transaction {
            entries.forEach { entry ->
                queries.appendJournal(
                    kind = if (entry.area == CORE_AREA) KIND_TRACE else KIND_EVENT,
                    at_millis = entry.atMillis,
                    level = entry.level.name.lowercase(),
                    area = entry.area,
                    message = entry.message.replace('\n', ' ').replace('\r', ' '),
                )
            }
            queries.trimJournal(KIND_TRACE, TRACE_LIMIT)
            queries.trimJournal(KIND_EVENT, EVENT_LIMIT)
        }
        // The blob this replaced is 24 KB of dead weight on every device that ran the old build.
        if (!legacyJournalDropped) {
            legacyJournalDropped = true
            runCatching { queries.upsertValue(LEGACY_EVENTS_KEY, ByteArray(0)) }
        }
    }

    fun clearCoreEvents() = synchronized(writeLock) { queries.clearJournal() }

    /** One journal line as it crossed the process boundary. */
    data class CoreEvent(val atMillis: Long, val level: String, val area: String, val message: String)

    fun coreEvents(): List<CoreEvent> = runCatching {
        queries.selectJournal { atMillis, level, area, message -> CoreEvent(atMillis, level, area, message) }
            .executeAsList()
    }.getOrDefault(emptyList())

    /** The last reason this source could not be read, in the words the product uses. */
    fun rememberFailure(id: String, failure: SourceFailure?) =
        queries.upsertValue(metadataKey(id, "failure"), (failure?.name ?: "").encodeToByteArray())

    private fun failureOf(id: String): SourceFailure? = metadataOf(id, "failure")
        ?.let { name -> runCatching { SourceFailure.valueOf(name) }.getOrNull() }

    private fun urlKey(id: String) = "subscription.$id.url"

    /** The address a remote source is fetched from, without its secret fragment. */
    fun urlOf(id: String): String? = queries.selectValue(urlKey(id)).executeAsOneOrNull()
        ?.decodeToString()?.takeIf(String::isNotEmpty)
    private fun keyKey(id: String) = "subscription.$id.hydra-key"
    private fun validityKey(id: String) = "subscription.$id.not-after"

    fun validityOf(id: String): String? = queries.selectValue(validityKey(id)).executeAsOneOrNull()
        ?.decodeToString()?.takeIf(String::isNotEmpty)

    /**
     * What a fresh install has, which is exactly what the codec makes of an empty table.
     *
     * There used to be a second set of defaults written out by hand here, and it was the one a
     * reset and every failed read used: it turned the memory limit off, halved the probe interval
     * and changed the location-lookup budget, so "reset" produced a device that had never been
     * possible and a database error quietly changed the routing policy.
     */
    private fun defaultSettings() = SettingsCodec().decode(emptyMap())

    private companion object {
        const val AREA = "sources"
        const val DATABASE_NAME = "hydrabox.db"
        const val SELECTED_KEY = "runtime.selected.outbound"
        const val START_FAILURE_KEY = "runtime.last.start.failure"
        const val IMPORT_FAILURE_KEY = "subscription.last.import.failure"
        /** The blob the journal used to live in, kept only so it can be emptied once. */
        const val LEGACY_EVENTS_KEY = "diagnostics.core.events.v2"

        /**
         * Where a debug build serves the core's profiler. Reach it with
         * `adb forward tcp:9091 tcp:9091`, then `go tool pprof http://127.0.0.1:9091/debug/pprof/profile`.
         */
        const val DEBUG_PPROF_PORT = 9091
        const val CORE_AREA = "core"
        const val KIND_TRACE = "trace"
        const val KIND_EVENT = "event"

        /**
         * Two budgets, because the two kinds answer different questions. Operational entries are
         * few and each one matters; core tracing is hundreds of lines a second at debug level and
         * is only ever read as a recent window. One shared budget of 300 meant the tracing
         * evicted every operational line, which is how the journal came to hold one second of
         * history and nothing that explained anything.
         */
        const val EVENT_LIMIT = 500L
        const val TRACE_LIMIT = 2_000L
    }
}
