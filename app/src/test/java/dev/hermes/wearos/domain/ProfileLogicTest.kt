package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner ask #1: base-URL profile swap + `<profile>-watch-` session prefixes — the switcher core. */
class ProfileLogicTest {

    @Test fun `swap keeps everything up to the p segment`() {
        assertEquals(
            "http://192.168.1.50:8642/p/default",
            ProfileLogic.swapProfile("http://192.168.1.50:8642/p/wear", "default"),
        )
        assertEquals(
            "http://myhost.lan:8642/p/wear",
            ProfileLogic.swapProfile("http://myhost.lan:8642/p/research", "wear"),
        )
    }

    @Test fun `swap preserves a trailing path after the profile segment`() {
        assertEquals(
            "https://host:8642/p/default/v1",
            ProfileLogic.swapProfile("https://host:8642/p/wear/v1", "default"),
        )
    }

    @Test fun `swap appends a p segment when the base has none`() {
        assertEquals("http://host:8642/p/wear", ProfileLogic.swapProfile("http://host:8642", "wear"))
        assertEquals("http://host:8642/p/wear", ProfileLogic.swapProfile("http://host:8642/", "wear"))
    }

    @Test fun `only the p segment swaps - host ports and earlier paths stand`() {
        // A "/p/" that appears as a non-profile path segment earlier is still the segment we own —
        // documented behavior: profile-scoped bases are `<root>/p/<profile>` (SPEC §1).
        val swapped = ProfileLogic.swapProfile("http://h/p/wear", "research")
        assertEquals("http://h/p/research", swapped)
        assertEquals("http://h:1/p/wear", ProfileLogic.swapProfile("http://h:1/p/wear", "wear"))
    }

    @Test fun `junk urls and bad names are rejected cleanly`() {
        assertNull(ProfileLogic.swapProfile("", "wear"))
        assertNull(ProfileLogic.swapProfile("not a url", "wear"))
        assertNull(ProfileLogic.swapProfile("http://h/p/wear", ""))
        assertNull(ProfileLogic.swapProfile("http://h/p/wear", "Bad Name"))
        assertNull(ProfileLogic.swapProfile("http://h/p/wear", "../etc"))
        assertNull(ProfileLogic.swapProfile("http://h/p/wear", "a".repeat(41)))
    }

    @Test fun `currentProfile reads the segment back`() {
        assertEquals("wear", ProfileLogic.currentProfile("http://h:8642/p/wear"))
        assertEquals("default", ProfileLogic.currentProfile("http://h:8642/p/default/"))
        assertEquals("wear", ProfileLogic.currentProfile("http://h:8642/p/wear/v1"))
        assertEquals("", ProfileLogic.currentProfile("http://h:8642"))
        assertEquals("", ProfileLogic.currentProfile("garbage"))
    }

    @Test fun `round trip - currentProfile after swap is the new agent`() {
        val base = "http://192.168.1.50:8642/p/wear"
        assertEquals("research", ProfileLogic.currentProfile(ProfileLogic.swapProfile(base, "research")!!))
    }

    @Test fun `session prefix follows the profile`() {
        assertEquals("wear-watch-", ProfileLogic.sessionPrefix("wear"))
        assertEquals("default-watch-", ProfileLogic.sessionPrefix("default"))
        assertEquals("wear-watch-", ProfileLogic.sessionPrefix(""))  // blank → owner default
        assertTrue(ProfileLogic.newSessionId("default").startsWith("default-watch-"))
    }

    @Test fun `watch-ownership marker matches our prefixes across profiles`() {
        assertTrue(ProfileLogic.isWatchSession("wear-watch-mvma9on0"))
        assertTrue(ProfileLogic.isWatchSession("default-watch-lz4k01"))
        assertTrue(ProfileLogic.isWatchSession("redridge-treasurer-watch-x1"))
        assertTrue(ProfileLogic.isWatchSession("wear-watch")) // bare prefix id
        assertFalse(ProfileLogic.isWatchSession("0f2a9c1e-4b7d-4c3f")) // bare desktop id
        assertFalse(ProfileLogic.isWatchSession("watchkit"))
        assertFalse(ProfileLogic.isWatchSession(""))
    }

    @Test fun `short label is at most 8 chars`() {
        assertEquals("redridge", ProfileLogic.shortLabel("redridge-treasurer"))
        assertEquals(8, ProfileLogic.shortLabel("redridge-treasurer").length)
        assertEquals("wear", ProfileLogic.shortLabel("wear"))
    }

    @Test fun `display list falls back and always offers the current agent`() {
        assertEquals(listOf("wear", "default"), ProfileLogic.displayAgents(emptyList(), "wear"))
        val fetched = listOf("default", "wear", "research")
        assertEquals(fetched, ProfileLogic.displayAgents(fetched, "wear"))
        // current agent missing from the fetched list (brand-new profile): appended, not lost
        assertEquals(listOf("default", "wear", "research", "newbie"), ProfileLogic.displayAgents(fetched, "newbie"))
    }

    @Test fun `valid profile names mirror the gateway's path-segment rules`() {
        assertTrue(ProfileLogic.isValidProfile("wear"))
        assertTrue(ProfileLogic.isValidProfile("redridge-treasurer"))
        assertTrue(ProfileLogic.isValidProfile("k-dense"))
        assertTrue(ProfileLogic.isValidProfile("bot.v2"))
        assertFalse(ProfileLogic.isValidProfile("-lead"))
        assertFalse(ProfileLogic.isValidProfile("has space"))
        assertFalse(ProfileLogic.isValidProfile("slash/es"))
    }

    // ── cross-profile sessions screen (walkthrough): the computer chip ──────────

    @Test fun `default profile reads as computer, others as short names`() {
        assertEquals("💻 computer", ProfileLogic.chipLabel("default"))
        assertEquals("wear", ProfileLogic.chipLabel("wear"))
        assertEquals("research", ProfileLogic.chipLabel("research"))          // exactly 8 kept
        assertEquals("redridge", ProfileLogic.chipLabel("redridge-treasurer")) // longer → 8-char cap
        assertEquals("", ProfileLogic.chipLabel(""))
    }

    @Test fun `computer profile constant is the multiplexed default`() {
        assertEquals("default", ProfileLogic.PROFILE_COMPUTER)
        assertEquals(ProfileLogic.PROFILE_COMPUTER, "default") // chipLabel's special case key
    }

    // ── last-agent memory (owner batch 2): rebuild the URL's /p/ segment at cold start ──

    @Test fun `baseForAgent swaps the segment to the remembered agent`() {
        assertEquals(
            "http://192.168.1.50:8642/p/default",
            ProfileLogic.baseForAgent("http://192.168.1.50:8642/p/wear", "default"),
        )
        assertEquals(
            "http://h:8642/p/wear",
            ProfileLogic.baseForAgent("http://h:8642/p/default", "wear"),
        )
        // Already on the remembered agent → stable (no churn on every start).
        assertEquals(
            "http://h:1/p/research",
            ProfileLogic.baseForAgent("http://h:1/p/research", "research"),
        )
    }

    @Test fun `baseForAgent appends a segment when the URL lost one, keeps trailing path`() {
        assertEquals("http://h:8642/p/wear", ProfileLogic.baseForAgent("http://h:8642", "wear"))
        assertEquals("http://h/p/default/v1", ProfileLogic.baseForAgent("http://h/p/wear/v1", "default"))
    }

    @Test fun `baseForAgent defaults to wear and keeps junk URLs standing`() {
        // Blank remembered agent falls back to the watch default.
        assertEquals("http://h:1/p/wear", ProfileLogic.baseForAgent("http://h:1/p/default", ""))
        // Garbage agent name (corrupt prefs): URL untouched (caller keeps its old base).
        assertEquals("http://h:1/p/wear", ProfileLogic.baseForAgent("http://h:1/p/wear", "bad name"))
        // Non-URL at all: normalized, never null.
        assertEquals("not-a-url", ProfileLogic.baseForAgent("not-a-url", "wear"))
    }
}
