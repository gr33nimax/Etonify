package io.hydrabox.core.config

/**
 * The rule sets on disk that the configuration may point at.
 *
 * HydraBox 1.x downloaded and compiled these outside the configuration and then referenced
 * them as local files (`singbox_config_builder.dart`, `type: local`). Nothing here decides
 * whether a set is used — that is a setting — but a set that is not on disk cannot be used at
 * all, which is why the paths are nullable and the switch above them reads this.
 */
data class RouteData(
    val adBlockPath: String? = null,
    val adBlockAllowPath: String? = null,
    val russiaRuleSetPaths: Map<String, String> = emptyMap(),
) {
    val adBlockAvailable get() = adBlockPath != null

    companion object {
        val None = RouteData()

        fun russiaAddressExclusions(useRussiaRouteData: Boolean, routeExcludeRussiaEnabled: Boolean): List<String> =
            if (useRussiaRouteData && routeExcludeRussiaEnabled) listOf("ru-geoip-ru") else emptyList()
    }
}
