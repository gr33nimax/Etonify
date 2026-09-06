package io.hydrabox.core.settings

import io.hydrabox.core.diagnostics.Secret
import io.hydrabox.core.diagnostics.SecretOpener
import io.hydrabox.core.diagnostics.SecretSealer
import io.hydrabox.core.storage.StorageDatabase

const val MAX_SPLIT_ROUTING_PACKAGE_COUNT = 128
const val DEFAULT_URL_TEST_URL = "https://cp.cloudflare.com/generate_204"
/**
 * The resolver everything else is reached through before the tunnel exists.
 *
 * Yandex's is the default because it is the one that answers where the others do not: on a
 * mobile network out of quota, or behind an operator white list, 1.1.1.1 is unreachable while
 * this one is not. Getting it wrong looks like "nothing works" rather than like a DNS problem,
 * which is exactly what happened on the device.
 */
const val DEFAULT_BOOTSTRAP_DNS_RESOLVER = "udp://77.88.8.8"

/** The platform's own resolver, as a value a person can choose. 1.x used the same marker. */
const val PLATFORM_DNS_RESOLVER = "device://network"
const val DEFAULT_PROXY_USERNAME = "hydrabox"
const val DEFAULT_PROXY_PORT = 2080

enum class PerformanceMode { STANDARD, ECONOMY }
enum class NotificationTrafficDisplayMode { SPEED, TOTAL, BOTH }
enum class TlsFragmentationMode { DISABLED, RECORD, FRAGMENT }

/**
 * Which address family the tunnel's resolver is allowed to answer with.
 *
 * `IPV4_ONLY` is the default because it is what 1.x generated and what the alpha left unset:
 * a AAAA answer on a network without IPv6 transit costs a connection attempt per name. AUTO
 * omits the field and lets the core answer with both.
 */
enum class DnsStrategy { AUTO, IPV4_ONLY, IPV6_ONLY }

/**
 * How the chosen set of applications is treated. 1.x offered the same three, under the
 * name "split routing"; the product word is "apps outside the VPN".
 */
enum class SplitRoutingMode { OFF, BYPASS_SELECTED, ONLY_SELECTED }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the tunnel device is implemented. 1.x called it `vpn_tun_implementation`. */
enum class TunStack { SYSTEM, GVISOR, MIXED }

/**
 * How much the core writes. `OFF` is not "only fatal errors": it disables the core's log
 * factory outright, which is the one setting that removes the cost of it. The core formats
 * every line whatever the level says — the level gates the file it writes to, not the stream
 * the app reads — so on a busy tunnel that work is paid even at `ERROR`.
 */
enum class LogLevel { OFF, TRACE, DEBUG, INFO, WARN, ERROR }

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
    val bootstrapDnsResolver: String,
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
    /** Rejects packets that would leave the tunnel behind its back. Stricter, and noisier. */
    val vpnStrictRoute: Boolean = false,
    val vpnTunStack: TunStack = TunStack.MIXED,
    val tcpFastOpen: Boolean = false,
    val tcpMultiPath: Boolean = false,
    /** 1 ms instead of 50 ms: switches servers on the smallest advantage. */
    val urlTestStrictTolerance: Boolean = false,
    /** Whether a server change tears down the connections that are already open. */
    val interruptExistingConnections: Boolean = false,
    val logLevel: LogLevel = LogLevel.WARN,
    /** The system tunnel. Off plus [proxyInboundEnabled] on is 1.x's proxy-only mode. */
    val vpnInboundEnabled: Boolean = true,
    val proxyInboundEnabled: Boolean = false,
    val proxyMixedPort: Int = DEFAULT_PROXY_PORT,
    /** Whether the local proxy answers other devices on the network, or only this one. */
    val proxyAllowLan: Boolean = false,
    /** Blocks advertising and tracking domains, if the rule set has been downloaded. */
    val adBlockEnabled: Boolean = false,
    val dnsStrategy: DnsStrategy = DnsStrategy.IPV4_ONLY,
    /** Answers address queries from a reserved range and keeps the domain for routing. */
    val fakeIpEnabled: Boolean = false,
    /** Whether the interface follows the system's wallpaper colours instead of the brand's. */
    val dynamicColour: Boolean = false,
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
            // The old key is read once so a device that had chosen a resolver for the
            // Russian direct route keeps it as its bootstrap; they are the same value.
            bootstrapDnsResolver = resolver(
                values[BOOTSTRAP_DNS_RESOLVER] ?: values[RUSSIA_DNS_DIRECT_RESOLVER],
                DEFAULT_BOOTSTRAP_DNS_RESOLVER,
            ),
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
            blockLeaks = bool(BLOCK_LEAKS, true),
            bypassLocalNetwork = bool(BYPASS_LOCAL_NETWORK, true),
            splitRoutingMode = when (values[SPLIT_ROUTING_MODE]) {
                "off" -> SplitRoutingMode.OFF
                "only_selected" -> SplitRoutingMode.ONLY_SELECTED
                else -> SplitRoutingMode.BYPASS_SELECTED
            },
            themeMode = when (values[THEME_MODE]) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            },
            language = when (values[LANGUAGE]) {
                "russian" -> AppLanguage.RUSSIAN
                "english" -> AppLanguage.ENGLISH
                else -> AppLanguage.SYSTEM
            },
            vpnStrictRoute = bool(VPN_STRICT_ROUTE, false),
            vpnTunStack = when (values[VPN_TUN_IMPLEMENTATION]) {
                "system" -> TunStack.SYSTEM
                "gvisor" -> TunStack.GVISOR
                else -> TunStack.MIXED
            },
            tcpFastOpen = bool(TCP_FAST_OPEN, false),
            tcpMultiPath = bool(TCP_MULTI_PATH, false),
            urlTestStrictTolerance = bool(URL_TEST_STRICT_TOLERANCE, false),
            interruptExistingConnections = bool(INTERRUPT_EXISTING_CONNECTIONS, false),
            logLevel = when (values[LOG_LEVEL]) {
                "off" -> LogLevel.OFF
                "trace" -> LogLevel.TRACE
                "debug" -> LogLevel.DEBUG
                "info" -> LogLevel.INFO
                "error" -> LogLevel.ERROR
                else -> LogLevel.WARN
            },
            vpnInboundEnabled = bool(VPN_INBOUND_ENABLED, true),
            proxyInboundEnabled = bool(PROXY_INBOUND_ENABLED, false),
            proxyMixedPort = number(PROXY_MIXED_PORT)?.takeIf { it in 1024..65535 } ?: DEFAULT_PROXY_PORT,
            proxyAllowLan = bool(PROXY_ALLOW_LAN, false),
            adBlockEnabled = bool(AD_BLOCK_ENABLED, false),
            fakeIpEnabled = bool(DNS_FAKEIP, false),
            dynamicColour = bool(DYNAMIC_COLOUR, false),
            dnsStrategy = when (values[DNS_STRATEGY]) {
                "auto" -> DnsStrategy.AUTO
                "ipv6_only" -> DnsStrategy.IPV6_ONLY
                else -> DnsStrategy.IPV4_ONLY
            },
        )
    }

    fun encode(settings: Settings): Map<String, String> = mapOf(
        PERFORMANCE_MODE to if (settings.performanceMode == PerformanceMode.ECONOMY) "economy" else "standard",
        URL_TEST_URL to settings.urlTestUrl, URL_TEST_INTERVAL to settings.urlTestIntervalSeconds.toString(), URL_TEST_TIMEOUT to settings.urlTestTimeoutSeconds.toString(), URL_TEST_CONCURRENCY to settings.urlTestConcurrency.toString(), URL_TEST_UNAVAILABLE to settings.urlTestUnavailableCheckIntervalSeconds.toString(),
        LOCATION_LOOKUP_LIMIT to settings.locationLookupLimit.toString(), LOCATION_LOOKUP_TIMEOUT to settings.locationLookupTimeoutSeconds.toString(), LOCATION_LOOKUP_CONCURRENCY to settings.locationLookupConcurrency.toString(),
        BOOTSTRAP_DNS_RESOLVER to settings.bootstrapDnsResolver, DNS_DIRECT_RESOLVER to settings.dnsDirectResolver, DNS_PROXY_RESOLVER to settings.dnsProxyResolver,
        MEMORY_LIMIT_ENABLED to flag(settings.memoryLimitEnabled), MEMORY_LIMIT_WARNING_DISMISSED to flag(settings.memoryLimitWarningDismissed), STATUS_NOTIFICATION_ENABLED to flag(settings.statusNotificationEnabled), NOTIFICATION_TRAFFIC_DISPLAY_MODE to settings.notificationTrafficDisplayMode.name.lowercase(),
        ACCEPTED_LEGAL_VERSION to settings.acceptedLegalVersion, ACCEPTED_LEGAL_AT_MILLIS to settings.acceptedLegalAtMillis?.toString().orEmpty(), TLS_FRAGMENTATION_MODE to settings.tlsFragmentationMode.name.lowercase(), PROXY_USERNAME to normalizeProxyUsername(settings.proxyUsername), PROXY_SORT to settings.proxySort, VPN_MTU to settings.vpnMtu.toString(), VPN_MTU_MIGRATED to "1", SPLIT_ROUTING_PACKAGES to normalizeSplitRoutingPackages(settings.splitRoutingPackages).joinToString("\n"),
        BLOCK_LEAKS to flag(settings.blockLeaks), BYPASS_LOCAL_NETWORK to flag(settings.bypassLocalNetwork), SPLIT_ROUTING_MODE to settings.splitRoutingMode.name.lowercase(), THEME_MODE to settings.themeMode.name.lowercase(), LANGUAGE to settings.language.name.lowercase(), VPN_STRICT_ROUTE to flag(settings.vpnStrictRoute), VPN_TUN_IMPLEMENTATION to settings.vpnTunStack.name.lowercase(), TCP_FAST_OPEN to flag(settings.tcpFastOpen), TCP_MULTI_PATH to flag(settings.tcpMultiPath), URL_TEST_STRICT_TOLERANCE to flag(settings.urlTestStrictTolerance), INTERRUPT_EXISTING_CONNECTIONS to flag(settings.interruptExistingConnections), LOG_LEVEL to settings.logLevel.name.lowercase(), VPN_INBOUND_ENABLED to flag(settings.vpnInboundEnabled), PROXY_INBOUND_ENABLED to flag(settings.proxyInboundEnabled), PROXY_MIXED_PORT to settings.proxyMixedPort.toString(), PROXY_ALLOW_LAN to flag(settings.proxyAllowLan), AD_BLOCK_ENABLED to flag(settings.adBlockEnabled), DNS_STRATEGY to settings.dnsStrategy.name.lowercase(), DNS_FAKEIP to flag(settings.fakeIpEnabled), DYNAMIC_COLOUR to flag(settings.dynamicColour),
    )

    fun safeExport(settings: Settings) = encode(settings)
}

fun normalizeProxyUsername(value: String): String {
    val normalized = value.trim()
    return if (normalized.isNotEmpty() && normalized.length <= 64 && normalized.none { it.isWhitespace() || it == ':' || it.code < 32 || it.code == 127 }) normalized else DEFAULT_PROXY_USERNAME
}

fun normalizeSplitRoutingPackages(values: Iterable<String>): List<String> = values.asSequence().map(String::trim).filter { it != "io.hydrabox.client" && it.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")) && it.length <= 255 }.distinct().take(MAX_SPLIT_ROUTING_PACKAGE_COUNT).toList()

/**
 * A stored resolver, or the default when what was stored cannot be used as one.
 *
 * The scheme and the address are both checked here, because this is the last place that can
 * answer a person: the generator downstream has to turn whatever it is handed into a server
 * object, and a value it cannot express is a tunnel that does not come up. Three shapes used to
 * pass and then mean something else — a scheme the core has no transport for, a port outside the
 * range, and a DoH address with a query string the configuration has nowhere to carry.
 */
private fun resolver(value: String?, fallback: String): String {
    val normalized = value?.trim().orEmpty()
    if (normalized.isEmpty()) return fallback
    val lower = normalized.lowercase()
    if (lower == PLATFORM_DNS_RESOLVER) return normalized
    if (normalized.any(Char::isWhitespace)) return fallback
    if ("://" in normalized) {
        val scheme = lower.substringBefore("://")
        if (scheme !in RESOLVER_SCHEMES) return fallback
        val rest = normalized.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?')
        val tail = rest.removePrefix(authority)
        // Only a DoH address may carry a path at all, and none of them may carry a query: the
        // core's DNS server has a `path` field and nothing that holds a query, so keeping the
        // value would mean saving one resolver and configuring a different one.
        if ('?' in tail) return fallback
        if (tail.isNotEmpty() && scheme != "https" && scheme != "h3") return fallback
        return if (isResolverAuthority(authority)) normalized else fallback
    }
    if (normalized.any { it in "/?#@" }) return fallback
    if (normalized.count { it == ':' } > 1) return "udp://[$normalized]"
    return if (normalized.matches(Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$"))) "udp://$normalized" else fallback
}

/** The DNS transports the core has. `device://network` is the platform's own, handled above. */
private val RESOLVER_SCHEMES = setOf("udp", "tcp", "tls", "https", "quic", "h3")

/** A host with an optional port, where the host may be a bare or bracketed IPv6 address. */
private fun isResolverAuthority(authority: String): Boolean {
    if (authority.isEmpty()) return false
    val port = when {
        authority.startsWith("[") -> {
            if (!authority.contains("]")) return false
            authority.substringAfter("]", "").let { if (it.isEmpty()) null else it.removePrefix(":") }
        }
        authority.count { it == ':' } > 1 -> null
        else -> authority.substringAfter(':', "").takeIf(String::isNotEmpty)
    }
    val host = when {
        authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
        authority.count { it == ':' } > 1 -> authority
        else -> authority.substringBefore(':')
    }
    if (host.isEmpty()) return false
    if (port != null) {
        val number = port.toIntOrNull() ?: return false
        if (number !in 1..65_535) return false
    }
    return host.all { it.isLetterOrDigit() || it in ".-:_" }
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
private const val BOOTSTRAP_DNS_RESOLVER = "dns_bootstrap_resolver"
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
private const val VPN_STRICT_ROUTE = "vpn_strict_route"
private const val VPN_TUN_IMPLEMENTATION = "vpn_tun_implementation"
private const val TCP_FAST_OPEN = "experimental_tcp_fast_open"
private const val TCP_MULTI_PATH = "experimental_tcp_multi_path"
private const val URL_TEST_STRICT_TOLERANCE = "urltest_strict_tolerance"
private const val INTERRUPT_EXISTING_CONNECTIONS = "interrupt_existing_connections"
private const val LOG_LEVEL = "singbox_log_level"
private const val VPN_INBOUND_ENABLED = "vpn_inbound_enabled"
private const val PROXY_INBOUND_ENABLED = "proxy_inbound_enabled"
private const val PROXY_MIXED_PORT = "proxy_mixed_port"
private const val PROXY_ALLOW_LAN = "proxy_allow_lan"
private const val AD_BLOCK_ENABLED = "ad_block_enabled"
private const val DNS_STRATEGY = "dns_strategy"
private const val DNS_FAKEIP = "dns_fakeip"
private const val DYNAMIC_COLOUR = "dynamic_colour"
