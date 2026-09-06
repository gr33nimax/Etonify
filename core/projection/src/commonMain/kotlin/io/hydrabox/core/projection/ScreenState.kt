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
    /**
     * The same two figures as numbers, because a meter needs a fraction and a formatted string
     * cannot give one. Null when the provider reports no cap.
     */
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    /** Days left in the validity window, when the document declares one. */
    val expiresInDays: Int? = null,
    /**
     * How often the provider says the document should be read again. Parsed from the same
     * header as the quota and kept, because "checked every day" is the difference between a
     * figure a person can trust and a figure of unknown age.
     */
    val updateIntervalHours: Int? = null,
    /** When the source was last read, as a person reads a date. */
    val updatedAt: String? = null,
    /**
     * What the source is made of: how many servers of each protocol. Two names in prose said
     * nothing; the composition says what a person can actually pick from.
     */
    val protocols: Map<String, Int> = emptyMap(),
    /** The address the source is fetched from, when it is remote. For copying, not for showing. */
    val link: String? = null,
    /**
     * Whether the source contributes to the server list and to the configuration. A subscription
     * kept for later is not the same thing as a subscription deleted.
     */
    val enabled: Boolean = true,
) {
    /** Whether the provider declared a data cap at all, as opposed to an unmetered plan. */
    val metered: Boolean get() = totalBytes != null

    /** Whether the provider declared an end date at all, as opposed to an open-ended plan. */
    val dated: Boolean get() = expiresAt != null || expiresInDays != null

    /** How much of the plan is gone, between 0 and 1, when there is a cap to compare with. */
    val quota: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { total ->
            ((usedBytes ?: 0L).toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

/** What is wrong with a source, in the terms the person can act on. */
enum class SourceProblem { EXPIRED, UNREACHABLE, EMPTY, REJECTED }

/** Servers of one source, kept together because that is how a person recognises them. */
data class ServerGroup(val sourceId: String, val sourceName: String, val servers: List<ServerRef>)

/** How the chosen applications are treated, in the product's own words. */
enum class AppsMode { OFF, BYPASS_SELECTED, ONLY_SELECTED }

enum class Appearance { SYSTEM, LIGHT, DARK }

enum class Language { SYSTEM, RUSSIAN, ENGLISH }

/** How the tunnel device is implemented; a person picks it only in the advanced section. */
enum class TunnelStack { SYSTEM, GVISOR, MIXED }

/** How a TLS handshake is split so that inspection does not recognise it. */
enum class TlsFragmentation { OFF, RECORD, FRAGMENT }

enum class LogDetail { OFF, TRACE, DEBUG, INFO, WARN, ERROR }

/** Which address family the tunnel's resolver may answer with. */
enum class DnsMode { AUTO, IPV4, IPV6 }

/** What the ongoing notification says under the state, if anything. */
enum class NotificationDetail { OFF, SPEED, TOTAL, BOTH }

/** Settings as shown. Only what a screen displays; every value already resolved. */
data class SettingsSummary(
    val economyMode: Boolean,
    val proxyDnsResolver: String,
    val directDnsResolver: String,
    val bootstrapDnsResolver: String = "",
    val vpnMtu: Int,
    val appsOutsideTunnel: Int,
    val statusNotificationEnabled: Boolean,
    val notificationDetail: NotificationDetail = NotificationDetail.SPEED,
    val dnsMode: DnsMode = DnsMode.IPV4,
    val fakeIp: Boolean = false,
    /** Whether changing server drops the connections that are already open. */
    val interruptConnections: Boolean = false,
    val blockLeaks: Boolean = true,
    val bypassLocalNetwork: Boolean = true,
    val appsMode: AppsMode = AppsMode.BYPASS_SELECTED,
    val appearance: Appearance = Appearance.SYSTEM,
    /** Whether the interface takes the system's wallpaper colours instead of the brand's. */
    val dynamicColour: Boolean = false,
    val language: Language = Language.SYSTEM,
    /** The local proxy replaces the system tunnel: 1.x called it proxy-only. */
    val proxyOnly: Boolean = false,
    val proxyPort: Int = 2080,
    val proxyAllowLan: Boolean = false,
    val adBlock: Boolean = false,
    val tcpFastOpen: Boolean = false,
    val tcpMultiPath: Boolean = false,
    val strictRoute: Boolean = false,
    val stack: TunnelStack = TunnelStack.MIXED,
    val fragmentation: TlsFragmentation = TlsFragmentation.OFF,
    val logDetail: LogDetail = LogDetail.WARN,
    /**
     * Whether the platform can hold a per-app language at all. Android learned to do it in
     * 13; below that the app follows the system and the choice is not offered rather than
     * offered and ignored.
     */
    val languageChoice: Boolean = false,
)

/**
 * The blocking rule set, as the switch above it needs to know it: either it is on disk with a
 * number of domains and a date, or it has to be downloaded first.
 */
data class RuleSetsSummary(
    val available: Boolean = false,
    val blockedDomains: Int = 0,
    val updatedAt: String? = null,
    val downloading: Boolean = false,
)

/**
 * Where the tunnel comes out: the address a site sees, and the country it belongs to. Without
 * it "connected" is a claim with nothing behind it, which is why 1.x showed it under the server
 * name. [checking] is its own state because the answer arrives over the network and the row must
 * not look empty while it does.
 */
data class ExitAddress(
    val address: String? = null,
    val countryCode: String? = null,
    val flag: String? = null,
    val checking: Boolean = false,
)

/** One installed app, as the picker for apps outside the tunnel needs it. */
data class InstalledApp(val packageName: String, val label: String, val excluded: Boolean)

/** How loud one journal line is. Four levels, because the core emits four. */
enum class JournalLevel { ERROR, WARN, INFO, DEBUG }

/**
 * One line of the journal, already resolved for reading: the clock time as a person reads it,
 * the level as a value rather than a colour, where the line came from, and the text.
 *
 * The message is deliberately not pre-trimmed. The screen shows one line of it and opens the
 * rest on demand, which is what 1.x's log page did — a truncated failure is a failure nobody
 * can report.
 */
data class JournalEntry(
    val id: Long,
    val time: String,
    val level: JournalLevel,
    val source: String,
    val message: String,
    /**
     * How many times this line arrived in a row. The core repeats itself — "no active paths"
     * once per request — and a hundred identical lines is a journal nobody scrolls through.
     */
    val repeats: Int = 1,
)

/**
 * Support information, reachable from exactly one screen.
 *
 * Runtime phase, transport health, lanes and generations are deliberately absent: they were
 * the first thing this screen showed and the last thing a person could act on. What stays is
 * what a support conversation actually needs — which build, which server, which resolver,
 * whether the rule set is on disk, the last failure, and the journal.
 */
data class DiagnosticsSummary(
    val level: String,
    val appVersion: String = "",
    val coreVersion: String = "",
    val activeServer: String? = null,
    val dnsResolver: String = "",
    val ruleSet: String? = null,
    val lastError: String? = null,
    val journal: List<JournalEntry> = emptyList(),
)

data class TrafficSummary(
    val available: Boolean,
    val uplink: String = "0 B/s",
    val downlink: String = "0 B/s",
    val uplinkTotal: String = "0 B",
    val downlinkTotal: String = "0 B",
    val connections: Int = 0,
    /** The same rates as numbers: a chart needs values, not labels. */
    val uplinkRate: Long = 0,
    val downlinkRate: Long = 0,
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

    /** The subscription is fine and asks for a core this build does not carry. */
    SOURCE_NEEDS_NEWER_APP,
    SOURCE_TOO_LARGE,
    SERVER_SWITCHED,
    /** The switch crossed the VK boundary, so the core restarted and its connections closed. */
    SERVER_SWITCH_RESTARTED,
    SETTINGS_NEED_RECONNECT,
    BACKUP_EXPORTED,
    BACKUP_IMPORTED,
    BACKUP_FAILED,
    SETTINGS_RESET,
    RULES_UPDATED,
    RULES_FAILED,
    OPERATION_FAILED,
    ;

    val failure: Boolean
        get() = this !in setOf(
            SOURCE_ADDED, SOURCE_UPDATED, SOURCE_REMOVED, SERVER_SWITCHED, SERVER_SWITCH_RESTARTED,
            SETTINGS_NEED_RECONNECT, BACKUP_EXPORTED, BACKUP_IMPORTED, SETTINGS_RESET, RULES_UPDATED,
        )
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
    /**
     * False until storage says otherwise. The default used to be true, which meant the very
     * first composition — before the stored model has been read — claimed the terms had been
     * accepted, and the first run then started at the wrong step and never finished.
     */
    val legalAccepted: Boolean = false,
    val servers: List<ServerGroup> = emptyList(),
    val autoServer: ServerRef? = null,
    val selectedServerId: String? = null,
    val sources: List<SubscriptionSummary> = emptyList(),
    val settings: SettingsSummary? = null,
    val diagnostics: DiagnosticsSummary? = null,
    val apps: List<InstalledApp> = emptyList(),
    val ruleSets: RuleSetsSummary = RuleSetsSummary(),
    val exit: ExitAddress = ExitAddress(),
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
    val legalAccepted: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val ruleSets: RuleSetsSummary = RuleSetsSummary(),
    val exit: ExitAddress = ExitAddress(),
    /** The system consent for a VPN was asked for and refused. */
    val vpnPermissionMissing: Boolean = false,
    /** How long the current tunnel has been up, measured by the platform. */
    val connectedForSeconds: Int? = null,
    val notice: Notice? = null,
)
