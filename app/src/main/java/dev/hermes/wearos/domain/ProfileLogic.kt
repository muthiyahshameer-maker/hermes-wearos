package dev.hermes.wearos.domain

/**
 * Pure logic for the agent switcher (owner ask #1): the app's base URL is profile-scoped
 * (`http://host:8642/p/wear`), and switching agent = swapping the `/p/<profile>` segment while
 * everything up to `/p/` stays. Gateway sessions created by the watch follow `<profile>-watch-xxxx`
 * (SPEC §3), so the session-id prefix travels with the profile too. No android.* imports — unit-tested.
 */
object ProfileLogic {
    /** Watch-native default agent (SPEC §9: default agent `wear`). */
    const val FALLBACK_AGENT = "wear"

    /** The multiplexed HOME profile — it IS the desktop/computer (owner: "the DESKTOP sessions"). */
    const val PROFILE_COMPUTER = "default"

    /** On fetch failure the switcher still offers these (owner ask #1). */
    val DEFAULT_AGENTS = listOf("wear", "default")

    /** Path-segment-safe profile names: starts alnum, then alnum/dot/dash/underscore, ≤40. */
    private val PROFILE_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")

    fun isValidProfile(name: String): Boolean = PROFILE_RE.matches(name)

    /** The `/p/<profile>` segment of a base URL, or "" when it isn't profile-scoped. */
    fun currentProfile(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        val scheme = b.indexOf("://")
        if (scheme < 0) return ""
        val from = b.indexOf('/', scheme + 3)
        if (from < 0) return ""
        val idx = b.indexOf("/p/", from)
        if (idx < 0) return ""
        val segStart = idx + 3
        val segEnd = b.indexOf('/', segStart).let { if (it < 0) b.length else it }
        return b.substring(segStart, segEnd)
    }

    /**
     * Swap the profile segment: keeps everything up to and including `/p/` plus any trailing path
     * (`/p/wear/v1` → `/p/default/v1`). No `/p/` segment at all → append one. Invalid/blank profile
     * or junk URL → null (caller keeps the old base and shows a clean error — no hardcoding).
     */
    fun swapProfile(baseUrl: String, profile: String): String? {
        if (!isValidProfile(profile)) return null
        val b = baseUrl.trim().trimEnd('/')
        if (b.isEmpty() || !b.contains("://")) return null
        val scheme = b.indexOf("://")
        val from = b.indexOf('/', scheme + 3)
        if (from < 0) return "$b/p/$profile"
        val idx = b.indexOf("/p/", from)
        if (idx < 0) return "$b/p/$profile"
        val segStart = idx + 3
        val segEnd = b.indexOf('/', segStart).let { if (it < 0) b.length else it }
        return b.substring(0, segStart) + profile + b.substring(segEnd)
    }

    /** Session-id prefix per agent (SPEC §3): sessions are `<profile>-watch-xxxx`. */
    fun sessionPrefix(profile: String): String =
        "${profile.trim().ifEmpty { FALLBACK_AGENT }}-watch-"

    /**
     * The base URL that SHOULD be in force for persisted agent [agent] (owner batch 2
     * last-agent memory): swap the `/p/` segment via [swapProfile]; a blank agent falls back to
     * [FALLBACK_AGENT], an unbuildable URL (junk/invalid agent) is returned untouched so the
     * caller can compare-and-keep. Used by AppSettings.loadProfile() on cold start.
     */
    fun baseForAgent(baseUrl: String, agent: String): String {
        val a = agent.trim().ifEmpty { FALLBACK_AGENT }
        return swapProfile(baseUrl, a) ?: baseUrl.trim().trimEnd('/')
    }

    /** Fresh session id for a new chat under [profile]. */
    fun newSessionId(profile: String): String = sessionPrefix(profile) + System.currentTimeMillis().toString(36)

    /**
     * Sessions this watch created, under any profile (the ⌚ marker on the gateway sessions list).
     * Deliberately generous: `wear-watch-x`, `default-watch-x` qualify; bare ids and titles don't.
     */
    fun isWatchSession(sessionId: String): Boolean =
        Regex("^[A-Za-z0-9][A-Za-z0-9._-]*-watch(-.*)?$").matches(sessionId)

    /** Tiny label under the agent button — max 8 chars (owner: "keep short, first 8 chars"). */
    fun shortLabel(profile: String): String = profile.take(8)

    /** Agents to offer: fetched list (or fallback) with the current profile always present. */
    fun displayAgents(fetched: List<String>, current: String): List<String> {
        val base = fetched.filter { it.isNotBlank() }.distinct()
            .ifEmpty { DEFAULT_AGENTS }
        return if (current.isBlank() || base.contains(current)) base else base + current
    }

    /**
     * Chip label on the sessions screen (owner: "the DESKTOP/computer sessions too — profile
     * `default`"). The multiplexed home profile IS the computer, so it reads as `💻 computer`
     * instead of the confusing word "default"; everything else gets its short name.
     */
    fun chipLabel(profile: String): String =
        if (profile == "default") "💻 computer" else shortLabel(profile)
}
