package io.hydrabox.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    private val codec = SettingsCodec()

    @Test fun `defaults use standard cold runtime values`() {
        val state = codec.decode(emptyMap())
        assertEquals(PerformanceMode.STANDARD, state.performanceMode)
        assertEquals(1800, state.urlTestIntervalSeconds)
        assertEquals(15, state.urlTestTimeoutSeconds)
        assertEquals(8, state.urlTestConcurrency)
        assertEquals(120, state.urlTestUnavailableCheckIntervalSeconds)
        assertEquals(DEFAULT_URL_TEST_URL, state.urlTestUrl)
        assertEquals(1, state.locationLookupLimit)
        assertEquals(3, state.locationLookupTimeoutSeconds)
        assertEquals(1, state.locationLookupConcurrency)
        assertEquals(DEFAULT_BOOTSTRAP_DNS_RESOLVER, state.bootstrapDnsResolver)
        assertTrue(state.memoryLimitEnabled)
        assertFalse(state.memoryLimitWarningDismissed)
        assertTrue(state.statusNotificationEnabled)
        assertEquals(NotificationTrafficDisplayMode.SPEED, state.notificationTrafficDisplayMode)
    }

    @Test fun `notification traffic display mode persists`() {
        val state = codec.decode(mapOf("notification_traffic_display_mode" to "both"))
        assertEquals(NotificationTrafficDisplayMode.BOTH, state.notificationTrafficDisplayMode)
        assertEquals("both", codec.encode(state)["notification_traffic_display_mode"])
    }

    @Test fun `legacy Google URLTest default migrates to Cloudflare`() {
        assertEquals(DEFAULT_URL_TEST_URL, codec.decode(mapOf("urltest_url" to "https://www.gstatic.com/generate_204")).urlTestUrl)
    }

    @Test fun `legacy aggressive modes migrate to standard`() {
        assertEquals(PerformanceMode.STANDARD, codec.decode(mapOf("performance_mode" to "performance")).performanceMode)
        assertEquals(PerformanceMode.STANDARD, codec.decode(mapOf("performance_mode" to "cool")).performanceMode)
        assertEquals("standard", codec.encode(codec.decode(mapOf("performance_mode" to "performance")))["performance_mode"])
    }

    @Test fun `economy mode uses colder runtime values`() {
        val state = codec.decode(mapOf("performance_mode" to "economy"))
        assertEquals(PerformanceMode.ECONOMY, state.performanceMode)
        assertEquals(3600, state.urlTestIntervalSeconds)
        assertEquals(15, state.urlTestTimeoutSeconds)
        assertEquals(4, state.urlTestConcurrency)
        assertEquals(300, state.urlTestUnavailableCheckIntervalSeconds)
        assertEquals(0, state.locationLookupLimit)
        assertEquals(3, state.locationLookupTimeoutSeconds)
        assertEquals(1, state.locationLookupConcurrency)
    }

    @Test fun `old standard and economy URLTest defaults migrate`() {
        val standard = codec.decode(mapOf("performance_mode" to "standard", "url_test_interval_seconds" to "900", "url_test_timeout_seconds" to "10", "url_test_concurrency" to "4", "urltest_unavailable_check_interval_seconds" to "60", "location_lookup_concurrency" to "1"))
        assertEquals(1800, standard.urlTestIntervalSeconds); assertEquals(15, standard.urlTestTimeoutSeconds); assertEquals(8, standard.urlTestConcurrency); assertEquals(120, standard.urlTestUnavailableCheckIntervalSeconds); assertEquals(1, standard.locationLookupConcurrency)
        val previousStandard = codec.decode(mapOf("performance_mode" to "standard", "urltest_interval_seconds" to "120", "urltest_concurrency" to "8", "urltest_unavailable_check_interval_seconds" to "120"))
        assertEquals(1800, previousStandard.urlTestIntervalSeconds); assertEquals(8, previousStandard.urlTestConcurrency); assertEquals(120, previousStandard.urlTestUnavailableCheckIntervalSeconds)
        val economy = codec.decode(mapOf("performance_mode" to "economy", "url_test_interval_seconds" to "1800", "url_test_timeout_seconds" to "10", "url_test_concurrency" to "2", "urltest_unavailable_check_interval_seconds" to "120"))
        assertEquals(3600, economy.urlTestIntervalSeconds); assertEquals(15, economy.urlTestTimeoutSeconds); assertEquals(4, economy.urlTestConcurrency); assertEquals(300, economy.urlTestUnavailableCheckIntervalSeconds)
    }

    @Test fun `DNS resolvers normalize plain hosts as UDP`() {
        // The old key is still read, so a device that chose one keeps it as its bootstrap.
        assertEquals("udp://77.88.8.1", codec.decode(mapOf("russia_dns_direct_resolver" to "77.88.8.1")).bootstrapDnsResolver)
        assertEquals("udp://9.9.9.9", codec.decode(mapOf("dns_bootstrap_resolver" to "9.9.9.9")).bootstrapDnsResolver)
        assertEquals(PLATFORM_DNS_RESOLVER, codec.decode(mapOf("dns_bootstrap_resolver" to PLATFORM_DNS_RESOLVER)).bootstrapDnsResolver)
        assertEquals(DEFAULT_BOOTSTRAP_DNS_RESOLVER, codec.decode(mapOf("dns_bootstrap_resolver" to "bad resolver")).bootstrapDnsResolver)
        val state = codec.decode(mapOf("dns_direct_resolver" to "1.1.1.1", "dns_proxy_resolver" to "dns.google:5353"))
        assertEquals("udp://1.1.1.1", state.dnsDirectResolver); assertEquals("udp://dns.google:5353", state.dnsProxyResolver)
    }

    @Test fun `split routing packages are bounded Android package list`() {
        val packages = normalizeSplitRoutingPackages(listOf("Telegram", "com.example.app", "com.example.app", "io.hydrabox.client", "bad package", "") + (0..139).map { "com.example.app$it" })
        assertEquals("com.example.app", packages.first()); assertFalse("io.hydrabox.client" in packages); assertEquals(MAX_SPLIT_ROUTING_PACKAGE_COUNT, packages.size)
    }

    @Test fun `legal metadata and memory settings persist`() {
        val state = codec.decode(mapOf("accepted_legal_version" to "0.2.0", "accepted_legal_at_millis" to "1780000000000", "memory_limit_enabled" to "0", "memory_limit_warning_dismissed" to "1"))
        val encoded = codec.encode(state)
        assertEquals("0.2.0", state.acceptedLegalVersion); assertEquals(1780000000000, state.acceptedLegalAtMillis)
        assertEquals("0.2.0", encoded["accepted_legal_version"]); assertEquals("1780000000000", encoded["accepted_legal_at_millis"])
        assertFalse(state.memoryLimitEnabled); assertTrue(state.memoryLimitWarningDismissed)
    }

    @Test fun `notification status and TLS fragmentation persist`() {
        val state = codec.decode(mapOf("status_notification_enabled" to "0", "tls_fragmentation_mode" to "record"))
        assertFalse(state.statusNotificationEnabled); assertEquals("0", codec.safeExport(state)["status_notification_enabled"])
        assertEquals(TlsFragmentationMode.RECORD, state.tlsFragmentationMode); assertEquals("record", codec.encode(state)["tls_fragmentation_mode"])
        assertEquals(TlsFragmentationMode.FRAGMENT, codec.decode(mapOf("tls_fragmentation_mode" to "fragment")).tlsFragmentationMode)
        assertEquals(TlsFragmentationMode.DISABLED, codec.decode(mapOf("tls_fragmentation_mode" to "unknown")).tlsFragmentationMode)
    }

    @Test fun `proxy credentials keep username and exclude password from safe export`() {
        val state = codec.decode(mapOf("proxy_username" to "sergey", "proxy_password" to "LocalOnlyPassword123456"))
        assertEquals("sergey", state.proxyUsername); assertEquals("sergey", codec.encode(state)["proxy_username"])
        assertTrue("proxy_password" !in codec.safeExport(state)); assertEquals(DEFAULT_PROXY_USERNAME, codec.decode(mapOf("proxy_username" to "bad username")).proxyUsername)
    }

    @Test fun `proxy sort normalizes unknown values`() {
        val state = codec.decode(mapOf("proxy_sort" to "working"))
        assertEquals("working", state.proxySort); assertEquals("working", codec.safeExport(state)["proxy_sort"])
        assertEquals("source", codec.decode(mapOf("proxy_sort" to "broken")).proxySort)
    }

    @Test fun `legacy MTU defaults migrate but custom choices survive`() {
        assertEquals(9000, codec.decode(emptyMap()).vpnMtu); assertEquals(9000, codec.decode(mapOf("vpn_mtu" to "1500")).vpnMtu); assertEquals(9000, codec.decode(mapOf("vpn_mtu" to "3400")).vpnMtu)
        assertEquals("1", codec.encode(codec.decode(mapOf("vpn_mtu" to "1500")))["vpn_mtu_migrated_to_9000"])
        assertEquals(1400, codec.decode(mapOf("vpn_mtu" to "1400")).vpnMtu); assertEquals(8000, codec.decode(mapOf("vpn_mtu" to "8000")).vpnMtu)
        assertEquals(1500, codec.decode(mapOf("vpn_mtu" to "1500", "vpn_mtu_migrated_to_9000" to "1")).vpnMtu)
    }

    @Test fun `connection settings survive codec round trip`() {
        val settings = codec.decode(emptyMap()).copy(
            blockLeaks = false,
            bypassLocalNetwork = false,
            splitRoutingMode = SplitRoutingMode.ONLY_SELECTED,
            themeMode = ThemeMode.DARK,
            language = AppLanguage.ENGLISH,
            vpnStrictRoute = true,
            vpnTunStack = TunStack.GVISOR,
            tcpFastOpen = true,
            tcpMultiPath = true,
            urlTestStrictTolerance = true,
            interruptExistingConnections = true,
            logLevel = LogLevel.DEBUG,
            vpnInboundEnabled = false,
            proxyInboundEnabled = true,
            proxyMixedPort = 3080,
            proxyAllowLan = true,
            adBlockEnabled = true,
            dnsStrategy = DnsStrategy.IPV6_ONLY,
            fakeIpEnabled = true,
        )

        assertEquals(settings, codec.decode(codec.encode(settings)))
    }

    @Test fun `the DNS address family defaults to IPv4 and survives the round trip`() {
        // What 1.x generated, and what a device without IPv6 transit needs: an unset strategy
        // is the alpha's behaviour, not a choice, so the default is explicit here.
        assertEquals(DnsStrategy.IPV4_ONLY, codec.decode(emptyMap()).dnsStrategy)
        assertEquals(DnsStrategy.AUTO, codec.decode(mapOf("dns_strategy" to "auto")).dnsStrategy)
        assertEquals("auto", codec.encode(codec.decode(mapOf("dns_strategy" to "auto")))["dns_strategy"])
    }
}
