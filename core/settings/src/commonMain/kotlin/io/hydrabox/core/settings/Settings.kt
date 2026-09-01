package io.hydrabox.core.settings

import io.hydrabox.core.diagnostics.Secret
import io.hydrabox.core.diagnostics.SecretOpener
import io.hydrabox.core.diagnostics.SecretSealer
import io.hydrabox.core.storage.StorageDatabase

const val MAX_SPLIT_ROUTING_PACKAGE_COUNT = 128
const val DEFAULT_URL_TEST_URL = "https://cp.cloudflare.com/generate_204"
const val DEFAULT_RUSSIA_DNS_DIRECT_RESOLVER = "udp://77.88.8.8"
const val DEFAULT_PROXY_USERNAME = "hydrabox"

enum class PerformanceMode { STANDARD, ECONOMY }
enum class NotificationTrafficDisplayMode { SPEED, TOTAL, BOTH }
enum class TlsFragmentationMode { DISABLED, RECORD, FRAGMENT }

/**
 * How the chosen set of applications is treated. 1.x offered the same three, under the
 * name "split routing"; the product word is "apps outside the VPN".
 */
enum class SplitRoutingMode { OFF, BYPASS_SELECTED, ONLY_SELECTED }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage { SYSTEM, RUSSIAN, ENGLISH }

data class Settings(
    val performanceMode: PerformanceMode,
    val urlTestUrl: String,
    val urlTestIntervalSeconds: Int,
    val urlTestTimeoutSeconds: Int,
    val urlTestConcurrency: Int,
    val urlTestUnavailableCheckIntervalSeconds: Int,
    val locationLookupLimit: Int,
    val locationLookupTimeoutSeconds: Int,
    val locationLookupConcurrency: Int,
    val russiaDnsDirectResolver: String,
    val dnsDirectResolver: String,
    val dnsProxyResolver: String,
    val memoryLimitEnabled: Boolean,
    val memoryLimitWarningDismissed: Boolean,
    val statusNotificationEnabled: Boolean,
    val notificationTrafficDisplayMode: NotificationTrafficDisplayMode,
    val acceptedLegalVersion: String,
    val acceptedLegalAtMillis: Long?,
    val tlsFragmentationMode: TlsFragmentationMode,
    val proxyUsername: String,
    val proxyPassword: Secret? = null,
    val proxySort: String,
    val vpnMtu: Int,
    val splitRoutingPackages: List<String> = emptyList(),
    /** Rejects the traffic that betrays the tunnel: STUN, which reveals the real address. */
    val blockLeaks: Boolean = true,
    /** Printers, routers and NAS keep working while the tunnel is up. */
    val bypassLocalNetwork: Boolean = true,
    val splitRoutingMode: SplitRoutingMode = SplitRoutingMode.BYPASS_SELECTED,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
)

class SettingsStore(private val database: StorageDatabase, private val secretSealer: SecretSealer, private val secretOpener: SecretOpener) {
    private val codec = SettingsCodec()

    fun load(): Settings {
        val rows = database.storageDatabaseQueries.selectAll().executeAsList()
        val values = rows.associate { it.setting_key to it.value_ }
        val encrypted = rows.firstOrNull { it.setting_key == PROXY_PASSWORD }?.secret_value
        return codec.decode(values, encrypted?.let { Secret.openWith(it, secretOpener) })
    }

    fun save(settings: Settings) {
        val queries = database.storageDatabaseQueries
        codec.encode(settings).forEach { (key, value) -> queries.upsertSetting(key, value, null) }
        settings.proxyPassword?.let { queries.upsertSetting(PROXY_PASSWORD, "", it.sealWith(secretSealer)) }
    }
}

class SettingsCodec {
    fun decode(values: Map<String, String>, proxyPassword: Secret? = null): Settings {
        fun bool(key: String, default: Boolean) = values[key]?.let { it == "1" } ?: default
        fun number(key: String) = values[key]?.toIntOrNull()
        val performance = if (values[PERFORMANCE_MODE] == "economy") PerformanceMode.ECONOMY else PerformanceMode.STANDARD
        val economy = performance == PerformanceMode.ECONOMY
        val defaultConcurrency = if (economy) 4 else 8
        val defaultUnavailable = if (economy) 300 else 120
        val migratedMtu = values[VPN_MTU_MIGRATED] == "1"
        val rawMtu = number(VPN_MTU)
        val mtu = if (!migratedMtu && (rawMtu == null || rawMtu == 1500 || rawMtu == 3400)) 9000 else rawMtu?.takeUnless { it == 3400 } ?: 9000
        return Settings(
            performanceMode = performance,
            urlTestUrl = if (values[URL_TEST_URL] == "https://www.gstatic.com/generate_204") DEFAULT_URL_TEST_URL else values[URL_TEST_URL] ?: DEFAULT_URL_TEST_URL,
            urlTestIntervalSeconds = if (number(URL_TEST_INTERVAL) in setOf(null, 120, 180, 300, 900) || economy && number(URL_TEST_INTERVAL) == 1800) if (economy) 3600 else 1800 else number(URL_TEST_INTERVAL)!!,
            urlTestTimeoutSeconds = if (number(URL_TEST_TIMEOUT) in setOf(null, 4, 5, 10, 15)) 15 else number(URL_TEST_TIMEOUT)!!,
            urlTestConcurrency = (number(URL_TEST_CONCURRENCY) ?: defaultConcurrency).coerceIn(1, defaultConcurrency),
            urlTestUnavailableCheckIntervalSeconds = if (number(URL_TEST_UNAVAILABLE) in setOf(null, 5, 15, 60, 120, 300) || economy && number(URL_TEST_UNAVAILABLE) == 10) defaultUnavailable else number(URL_TEST_UNAVAILABLE)!!.coerceIn(defaultUnavailable, 3600),
            locationLookupLimit = when (number(LOCATION_LOOKUP_LIMIT)) { null -> if (economy) 0 else 1; 2 -> if (economy) 2 else 1; else -> number(LOCATION_LOOKUP_LIMIT)!! },
            locationLookupTimeoutSeconds = if (number(LOCATION_LOOKUP_TIMEOUT) in setOf(null, 5)) 3 else number(LOCATION_LOOKUP_TIMEOUT)!!,
            locationLookupConcurrency = if (number(LOCATION_LOOKUP_CONCURRENCY) == null || !economy && number(LOCATION_LOOKUP_CONCURRENCY) in setOf(2, 3)) 1 else number(LOCATION_LOOKUP_CONCURRENCY)!!,
            russiaDnsDirectResolver = resolver(values[RUSSIA_DNS_DIRECT_RESOLVER], DEFAULT_RUSSIA_DNS_DIRECT_RESOLVER),
            dnsDirectResolver = resolver(values[DNS_DIRECT_RESOLVER], "udp://1.1.1.1"),
            dnsProxyResolver = resolver(values[DNS_PROXY_RESOLVER], "https://dns.cloudflare.com/dns-query"),
            memoryLimitEnabled = bool(MEMORY_LIMIT_ENABLED, true),
            memoryLimitWarningDismissed = bool(MEMORY_LIMIT_WARNING_DISMISSED, false),
            statusNotificationEnabled = bool(STATUS_NOTIFICATION_ENABLED, true),
            notificationTrafficDisplayMode = when (values[NOTIFICATION_TRAFFIC_DISPLAY_MODE]) { "total" -> NotificationTrafficDisplayMode.TOTAL; "both" -> NotificationTrafficDisplayMode.BOTH; else -> NotificationTrafficDisplayMode.SPEED },
            acceptedLegalVersion = values[ACCEPTED_LEGAL_VERSION].orEmpty().trim(),
            acceptedLegalAtMillis = values[ACCEPTED_LEGAL_AT_MILLIS]?.toLongOrNull(),
            tlsFragmentationMode = when (values[TLS_FRAGMENTATION_MODE]) { "record" -> TlsFragmentationMode.RECORD; "fragment" -> TlsFragmentationMode.FRAGMENT; else -> TlsFragmentationMode.DISABLED },
            proxyUsername = normalizeProxyUsername(values[PROXY_USERNAME].orEmpty()),
            proxyPassword = proxyPassword,
            proxySort = values[PROXY_SORT].takeIf { it in setOf("latency", "working", "name", "country") } ?: "source",
            vpnMtu = mtu,
            splitRoutingPackages = normalizeSplitRoutingPackages(values[SPLIT_ROUTING_PACKAGES].orEmpty().split(Regex("[\\n,;]"))),
        )
    }

    fun encode(settings: Settings): Map<String, String> = mapOf(
        PERFORMANCE_MODE to if (settings.performanceMode == PerformanceMode.ECONOMY) "economy" else "standard",
        URL_TEST_URL to settings.urlTestUrl, URL_TEST_INTERVAL to settings.urlTestIntervalSeconds.toString(), URL_TEST_TIMEOUT to settings.urlTestTimeoutSeconds.toString(), URL_TEST_CONCURRENCY to settings.urlTestConcurrency.toString(), URL_TEST_UNAVAILABLE to settings.urlTestUnavailableCheckIntervalSeconds.toString(),
        LOCATION_LOOKUP_LIMIT to settings.locationLookupLimit.toString(), LOCATION_LOOKUP_TIMEOUT to settings.locationLookupTimeoutSeconds.toString(), LOCATION_LOOKUP_CONCURRENCY to settings.locationLookupConcurrency.toString(),
        RUSSIA_DNS_DIRECT_RESOLVER to settings.russiaDnsDirectResolver, DNS_DIRECT_RESOLVER to settings.dnsDirectResolver, DNS_PROXY_RESOLVER to settings.dnsProxyResolver,
        MEMORY_LIMIT_ENABLED to flag(settings.memoryLimitEnabled), MEMORY_LIMIT_WARNING_DISMISSED to flag(settings.memoryLimitWarningDismissed), STATUS_NOTIFICATION_ENABLED to flag(settings.statusNotificationEnabled), NOTIFICATION_TRAFFIC_DISPLAY_MODE to settings.notificationTrafficDisplayMode.name.lowercase(),
        ACCEPTED_LEGAL_VERSION to settings.acceptedLegalVersion, ACCEPTED_LEGAL_AT_MILLIS to settings.acceptedLegalAtMillis?.toString().orEmpty(), TLS_FRAGMENTATION_MODE to settings.tlsFragmentationMode.name.lowercase(), PROXY_USERNAME to normalizeProxyUsername(settings.proxyUsername), PROXY_SORT to settings.proxySort, VPN_MTU to settings.vpnMtu.toString(), VPN_MTU_MIGRATED to "1", SPLIT_ROUTING_PACKAGES to normalizeSplitRoutingPackages(settings.splitRoutingPackages).joinToString("\n"),
    )

    fun safeExport(settings: Settings) = encode(settings)
}

fun normalizeProxyUsername(value: String): String {
    val normalized = value.trim()
    return if (normalized.isNotEmpty() && normalized.length <= 64 && normalized.none { it.isWhitespace() || it == ':' || it.code < 32 || it.code == 127 }) normalized else DEFAULT_PROXY_USERNAME
}

fun normalizeSplitRoutingPackages(values: Iterable<String>): List<String> = values.asSequence().map(String::trim).filter { it != "io.hydrabox.client" && it.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")) && it.length <= 255 }.distinct().take(MAX_SPLIT_ROUTING_PACKAGE_COUNT).toList()

private fun resolver(value: String?, fallback: String): String {
    val normalized = value?.trim().orEmpty()
    if (normalized.isEmpty()) return fallback
    val lower = normalized.lowercase()
    if (lower.startsWith("udp://") || lower.startsWith("tcp://") || lower.startsWith("tls://") || lower.startsWith("https://") || lower == "device://network") return normalized
    if (normalized.any(Char::isWhitespace) || normalized.any { it in "/?#@" }) return fallback
    if (normalized.count { it == ':' } > 1) return "udp://[$normalized]"
    return if (normalized.matches(Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$"))) "udp://$normalized" else fallback
}

private fun flag(value: Boolean) = if (value) "1" else "0"

private const val PERFORMANCE_MODE = "performance_mode"
private const val URL_TEST_URL = "urltest_url"
private const val URL_TEST_INTERVAL = "urltest_interval_seconds"
private const val URL_TEST_TIMEOUT = "urltest_timeout_seconds"
private const val URL_TEST_CONCURRENCY = "urltest_concurrency"
private const val URL_TEST_UNAVAILABLE = "urltest_unavailable_check_interval_seconds"
private const val LOCATION_LOOKUP_LIMIT = "location_lookup_limit"
private const val LOCATION_LOOKUP_TIMEOUT = "location_lookup_timeout_seconds"
private const val LOCATION_LOOKUP_CONCURRENCY = "location_lookup_concurrency"
private const val RUSSIA_DNS_DIRECT_RESOLVER = "russia_dns_direct_resolver"
private const val DNS_DIRECT_RESOLVER = "dns_direct_resolver"
private const val DNS_PROXY_RESOLVER = "dns_proxy_resolver"
private const val MEMORY_LIMIT_ENABLED = "memory_limit_enabled"
private const val MEMORY_LIMIT_WARNING_DISMISSED = "memory_limit_warning_dismissed"
private const val STATUS_NOTIFICATION_ENABLED = "status_notification_enabled"
private const val NOTIFICATION_TRAFFIC_DISPLAY_MODE = "notification_traffic_display_mode"
private const val ACCEPTED_LEGAL_VERSION = "accepted_legal_version"
private const val ACCEPTED_LEGAL_AT_MILLIS = "accepted_legal_at_millis"
private const val TLS_FRAGMENTATION_MODE = "tls_fragmentation_mode"
private const val PROXY_USERNAME = "proxy_username"
private const val PROXY_PASSWORD = "proxy_password"
private const val PROXY_SORT = "proxy_sort"
private const val VPN_MTU = "vpn_mtu"
private const val VPN_MTU_MIGRATED = "vpn_mtu_migrated_to_9000"
private const val SPLIT_ROUTING_PACKAGES = "split_routing_packages"
private const val BLOCK_LEAKS = "block_leaks"
private const val BYPASS_LOCAL_NETWORK = "bypass_local_network"
private const val SPLIT_ROUTING_MODE = "split_routing_mode"
private const val THEME_MODE = "theme_mode"
private const val LANGUAGE = "app_language"
