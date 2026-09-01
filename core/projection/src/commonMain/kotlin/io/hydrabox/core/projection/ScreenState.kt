package io.hydrabox.core.projection

import io.hydrabox.core.contract.RuntimeSnapshot
import io.hydrabox.core.model.OperationState

/** One stored source of servers, as the screens need it. */
data class SubscriptionSummary(
    val id: String,
    val name: String,
    val serverCount: Int,
    val updatedAtMillis: Long,
    /** From the source document's validity window, when it declares one. */
    val expiresAt: String? = null,
    val encrypted: Boolean = false,
    val problem: SourceProblem? = null,
    /** What the provider says the plan allows and how much of it is gone. */
    val usedTraffic: String? = null,
    val totalTraffic: String? = null,
)

/** What is wrong with a source, in the terms the person can act on. */
enum class SourceProblem { EXPIRED, UNREACHABLE, EMPTY, REJECTED }

/** Servers of one source, kept together because that is how a person recognises them. */
data class ServerGroup(val sourceId: String, val sourceName: String, val servers: List<ServerRef>)

/** How the chosen applications are treated, in the product's own words. */
enum class AppsMode { OFF, BYPASS_SELECTED, ONLY_SELECTED }

enum class Appearance { SYSTEM, LIGHT, DARK }

enum class Language { SYSTEM, RUSSIAN, ENGLISH }

/** Settings as shown. Only what a screen displays; every value already resolved. */
data class SettingsSummary(
    val economyMode: Boolean,
    val proxyDnsResolver: String,
    val directDnsResolver: String,
    val vpnMtu: Int,
    val appsOutsideTunnel: Int,
    val statusNotificationEnabled: Boolean,
    val blockLeaks: Boolean = true,
    val bypassLocalNetwork: Boolean = true,
    val appsMode: AppsMode = AppsMode.BYPASS_SELECTED,
    val appearance: Appearance = Appearance.SYSTEM,
    val language: Language = Language.SYSTEM,
    /**
     * Whether the platform can hold a per-app language at all. Android learned to do it in
     * 13; below that the app follows the system and the choice is not offered rather than
     * offered and ignored.
     */
    val languageChoice: Boolean = false,
)

/** One installed app, as the picker for apps outside the tunnel needs it. */
data class InstalledApp(val packageName: String, val label: String, val excluded: Boolean)

/** Technical detail, deliberately reachable from exactly one screen. */
data class DiagnosticsSummary(
    val level: String,
    val recentEvents: List<String>,
    val exportState: String,
    /** Runtime internals, shown here and refused everywhere else. */
    val runtimeState: String = "",
    val transport: String = "",
    val lastErrorCode: String? = null,
)

data class TrafficSummary(
    val available: Boolean,
    val uplink: String = "0 B/s",
    val downlink: String = "0 B/s",
    val uplinkTotal: String = "0 B",
    val downlinkTotal: String = "0 B",
    val connections: Int = 0,
)

/**
 * A transient product message. A typed value, not a sentence: the platform used to hand
 * the UI `"Failed: ${exception.message}"`, and the banner then guessed its own severity
 * from `startsWith("Failed")`.
 */
enum class Notice {
    VPN_PERMISSION_DENIED,
    SOURCE_ADDED,
    SOURCE_UPDATED,
    SOURCE_REMOVED,
    SOURCE_FAILED,
    SOURCE_EMPTY,
    SOURCE_UNREACHABLE,
    SOURCE_REJECTED,
    SOURCE_NOT_A_SUBSCRIPTION,
    SOURCE_INSECURE_LINK,
    SOURCE_UNSAFE_REDIRECT,
    SOURCE_NEEDS_KEY,
    SOURCE_TOO_LARGE,
    SERVER_SWITCHED,
    SETTINGS_NEED_RECONNECT,
    BACKUP_EXPORTED,
    BACKUP_IMPORTED,
    BACKUP_FAILED,
    SETTINGS_RESET,
    OPERATION_FAILED,
    ;

    val failure: Boolean
        get() = this != SOURCE_ADDED && this != SOURCE_UPDATED && this != SOURCE_REMOVED &&
            this != SERVER_SWITCHED && this != SETTINGS_NEED_RECONNECT && this != BACKUP_EXPORTED &&
            this != BACKUP_IMPORTED && this != SETTINGS_RESET
}

/** Which long operation is running. Screens show progress where it belongs, not on top. */
data class Busy(
    val source: Boolean = false,
    val servers: Boolean = false,
    val backup: Boolean = false,
) {
    val any get() = source || servers || backup
}

/**
 * Everything every screen reads. One value per product question; nothing here names a
 * runtime phase, an outbound, a lane or a generation.
 */
data class ScreenState(
    val connection: Connection,
    val legalAccepted: Boolean = true,
    val servers: List<ServerGroup> = emptyList(),
    val autoServer: ServerRef? = null,
    val selectedServerId: String? = null,
    val sources: List<SubscriptionSummary> = emptyList(),
    val settings: SettingsSummary? = null,
    val diagnostics: DiagnosticsSummary? = null,
    val apps: List<InstalledApp> = emptyList(),
    val busy: Busy = Busy(),
    val notice: Notice? = null,
) {
    val serverCount get() = servers.sumOf { it.servers.size }
    val hasSources get() = sources.isNotEmpty()

    /** The blocking first-run flow is over once the terms are accepted and a source exists. */
    val onboardingComplete get() = legalAccepted && hasSources
}

/** Everything the projection reads, one field per owning subsystem. */
data class AppReadModel(
    val runtime: RuntimeSnapshot,
    val sources: List<SubscriptionSummary> = emptyList(),
    val servers: List<ServerGroup> = emptyList(),
    val autoServer: ServerRef? = null,
    val selectedServerId: String? = null,
    val settings: SettingsSummary? = null,
    val diagnostics: DiagnosticsSummary? = null,
    val sourceOperation: OperationState<Unit> = OperationState.Idle,
    val backupOperation: OperationState<Unit> = OperationState.Idle,
    val legalAccepted: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    /** The system consent for a VPN was asked for and refused. */
    val vpnPermissionMissing: Boolean = false,
    /** How long the current tunnel has been up, measured by the platform. */
    val connectedForSeconds: Int? = null,
    val notice: Notice? = null,
)
