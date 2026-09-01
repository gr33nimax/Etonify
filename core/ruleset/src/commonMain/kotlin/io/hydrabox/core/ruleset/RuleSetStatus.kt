package io.hydrabox.core.ruleset

/**
 * What is on disk, as the interface needs to know it.
 *
 * A rule set is either compiled and usable or absent; there is no half state, because a
 * switch that promises blocking while the file is missing is worse than no switch. 1.x kept
 * the same distinction (`AdBlockRuleSetStatus.unavailable`).
 */
data class RuleSetStatus(
    val available: Boolean = false,
    val blockedDomains: Int = 0,
    val allowedDomains: Int = 0,
    val updatedAtMillis: Long? = null,
    val bytes: Long = 0,
) {
    companion object {
        val Unavailable = RuleSetStatus()
    }
}

/** Where the compiled sets live, so the generator can name them and nothing else has to. */
data class RuleSetPaths(val block: String, val allow: String?)
