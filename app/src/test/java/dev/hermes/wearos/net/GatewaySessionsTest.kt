package dev.hermes.wearos.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape parser for the sessions fast-path (owner ask #2) + the /agents list (ask #1),
 * pinned against the REAL 0.21.7 payload (GET /api/sessions on the live gateway, 2026-10-24).
 */
class GatewaySessionsTest {

    private val sample = """
        {"object":"list","data":[
          {"id":"wear-watch-mvma9on0","source":"api_server","model":"m",
           "title":"what's my current gps location of this watch",
           "started_at":1792839369.855,"ended_at":null,"message_count":1,"tool_call_count":0,
           "last_active":1792839369.657,"preview":"what's my current gps location"},
          {"id":"0f2a9c1e-4b7d","title":"desktop chat","last_active":1792839999.5,"message_count":7},
          {"id":"legacy-no-fields"}
        ]}
    """.trimIndent()

    @Test fun `parses id title last_active and message_count`() {
        val list = GatewaySessions.parse(sample)
        assertEquals(3, list.size)
        val watch = list.first { it.id == "wear-watch-mvma9on0" }
        assertEquals("what's my current gps location of this watch", watch.title)
        assertEquals(1, watch.messages)
        assertEquals(1792839369657L, watch.lastActiveMs) // float unix seconds → whole millis
    }

    @Test fun `sorted newest first`() {
        val list = GatewaySessions.parse(sample)
        assertEquals("0f2a9c1e-4b7d", list.first().id) // last_active 1792839999 > others
        assertEquals(listOf("0f2a9c1e-4b7d", "wear-watch-mvma9on0", "legacy-no-fields"), list.map { it.id })
    }

    @Test fun `missing fields degrade to empty title zero time zero messages`() {
        val legacy = GatewaySessions.parse(sample).last()
        assertEquals("", legacy.title)
        assertEquals(0L, legacy.lastActiveMs)
        assertEquals(0, legacy.messages)
    }

    @Test fun `empty data list parses to empty not crash`() {
        assertEquals(emptyList<GatewaySessions.Info>(), GatewaySessions.parse("""{"object":"list","data":[]}"""))
        assertEquals(emptyList<GatewaySessions.Info>(), GatewaySessions.parse("""{"object":"list"}"""))
    }

    @Test fun `junk input throws for the caller's cache-vs-error branch`() {
        assertThrows(Exception::class.java) { GatewaySessions.parse("<html>502</html>") }
    }

    @Test fun `non-object entries inside data are skipped`() {
        val list = GatewaySessions.parse("""{"data":[42,{"id":"ok"},null]}""")
        assertEquals(listOf("ok"), list.map { it.id })
    }

    @Test fun `agents list parses dedupes and drops blanks`() {
        val agents = GatewaySessions.parseAgents(
            """{"agents":["default","wear","wear","", 42, "research"]}""",
        )
        // Primitives are read leniently (a directory literally named "42" is legal): dedupe + drop blanks only.
        assertEquals(listOf("default", "wear", "42", "research"), agents)
    }

    @Test fun `agents round-trips the AgentsRepo cache format`() {
        val raw = """{"agents":["default","comfyui","wear"]}"""
        assertTrue(GatewaySessions.parseAgents(raw).contains("comfyui"))
    }

    // ── cross-profile sessions (walkthrough): tagging, merge, filter, cache ─────

    @Test fun `parse reads the watchkit profile field when present`() {
        val list = GatewaySessions.parse(
            """{"data":[{"id":"a","title":"t","last_active":100.0,"message_count":2,"profile":"default"}]}""",
        )
        assertEquals("default", list.single().profile)
        // Gateway rows (no profile key) parse untagged; callers tag via parseProfiled.
        assertEquals("", GatewaySessions.parse(sample).first().profile)
    }

    @Test fun `parseProfiled tags untagged rows but never overwrites a real profile`() {
        val tagged = GatewaySessions.parseProfiled(sample, "wear")
        assertTrue(tagged.all { it.profile == "wear" })
        val mixed = GatewaySessions.parseProfiled(
            """{"data":[{"id":"a","profile":"research"},{"id":"b"}]}""", "wear",
        )
        assertEquals("research", mixed.first { it.id == "a" }.profile)
        assertEquals("wear", mixed.first { it.id == "b" }.profile)
    }

    private fun info(id: String, ms: Long, profile: String = "") =
        GatewaySessions.Info(id, "t", ms, 1, profile)

    @Test fun `merge dedupes by id preferring tagged and fresher rows newest first`() {
        val merged = GatewaySessions.merge(
            listOf(
                info("dup", 500L),                                    // untagged (gateway list)
                info("dup", 400L, "default"),                          // same row, tagged, older stamp
                info("older", 100L, "wear"),
                info("newest", 900L, "default"),
            ),
        )
        assertEquals(listOf("newest", "dup", "older"), merged.map { it.id })
        assertEquals("default", merged.first { it.id == "dup" }.profile) // tagged wins over untagged
    }

    @Test fun `merge keeps the freshest stamp of duplicate ids`() {
        val merged = GatewaySessions.merge(
            listOf(info("x", 100L, "default"), info("x", 800L, "default")),
        )
        assertEquals(800L, merged.single().lastActiveMs)
    }

    @Test fun `merge caps to 30 rows — the owner's get-out-of-hand guard`() {
        val many = (1..80).map { info("s$it", it.toLong()) }
        val merged = GatewaySessions.merge(many)
        assertEquals(GatewaySessions.MAX_ROWS, merged.size)
        assertEquals("s80", merged.first().id) // freshest kept
        assertEquals("s51", merged.last().id)  // oldest 50 dropped
        assertEquals(3, GatewaySessions.merge(many, cap = 3).size)
        assertEquals(GatewaySessions.merge(many, cap = 999).size, GatewaySessions.MAX_ROWS) // cap clamps
    }

    @Test fun `filterProfile powers the all and per-profile chips`() {
        val rows = listOf(info("a", 300L, "wear"), info("b", 200L, "default"), info("c", 100L, "wear"))
        assertEquals(3, GatewaySessions.filterProfile(rows, GatewaySessions.FILTER_ALL).size)
        assertEquals(listOf("a", "c"), GatewaySessions.filterProfile(rows, "wear").map { it.id })
        assertEquals(listOf("b"), GatewaySessions.filterProfile(rows, "default").map { it.id })
        assertEquals(emptyList<GatewaySessions.Info>(), GatewaySessions.filterProfile(rows, "ghost"))
    }

    @Test fun `profilesIn lists profiles in newest-seen order without duplicates`() {
        val rows = listOf(info("a", 300L, "wear"), info("b", 200L, "default"), info("c", 100L, "wear"), info("d", 50L))
        assertEquals(listOf("wear", "default"), GatewaySessions.profilesIn(rows))
    }

    @Test fun `encode caches the merged list and parses back identically`() {
        val rows = listOf(
            GatewaySessions.Info("wear-watch-x", "title with \"quotes\" and \\ slashes\nnew\ttab", 1792839369657L, 7, "wear"),
            GatewaySessions.Info("0f2a", "电脑 chat ✓ ünïcode", 1792839999500L, 3, "default"),
            GatewaySessions.Info("legacy", "", 0L, 0, ""),
        )
        val back = GatewaySessions.parse(GatewaySessions.encode(rows))
        assertEquals(rows.sortedByDescending { it.lastActiveMs }, back) // ms → sec → ms keeps whole ms
    }
}
