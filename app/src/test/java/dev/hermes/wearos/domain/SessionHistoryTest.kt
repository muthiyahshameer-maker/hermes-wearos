package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcript-backfill adapter (owner bug: adopted computer sessions opened EMPTY). Pinned
 * against the REAL watchkit `GET /session_messages` payload shape from the live state.db
 * (session 20261023_185354_f6d464, the SSH-troubleshoot chat, 2026-10-24).
 */
class SessionHistoryTest {

    private val sample = """
        {"messages":[
          {"role":"user","content":"my ssh connection to this computer from my laptop ain't working","ts":1792839369},
          {"role":"assistant","content":"sshd is alive and listening, and it *was* authenticating you","ts":1792839400},
          {"role":"summary","content":"[CONTEXT COMPACTION — REFERENCE ONLY] Earlier discussion…","ts":1792839450},
          {"role":"tool","content":"{\"output\": \"nope\"}","ts":1792839460},
          {"role":"user","content":"   ","ts":1792839470},
          {"role":"user","content":"[IMPORTANT: Background process proc_4529 completed]","ts":1792839480},
          {"role":"assistant","content":"tail check","ts":1792839500}
        ],"session_id":"20261023_185354_f6d464","profile":"default","count":7}
    """.trimIndent()

    @Test fun `maps endpoint rows to chat roles oldest-first`() {
        val msgs = SessionHistory.parse(sample)
        assertEquals(
            listOf(
                ChatMessage.Role.User, ChatMessage.Role.Hermes, ChatMessage.Role.Hermes, ChatMessage.Role.Hermes,
            ),
            msgs.map { it.role },
        )
        assertEquals("my ssh connection to this computer from my laptop ain't working", msgs[0].text)
    }

    @Test fun `summary rows surface as one marked assistant line`() {
        val summary = SessionHistory.parse(sample).filter { it.text.startsWith(SessionHistory.SUMMARY_PREFIX) }
        assertEquals(1, summary.size)
        assertEquals(ChatMessage.Role.Hermes, summary.single().role)
    }

    @Test fun `noise rows drop — tool roles blank text background-process notices unknown roles`() {
        val texts = SessionHistory.parse(sample).map { it.text }
        assertTrue(texts.none { it.startsWith("[IMPORTANT: Background process") })
        assertTrue(texts.none { it.isBlank() })
        assertTrue(texts.none { it.contains("\"output\"") }) // the tool row is gone
        assertEquals(4, texts.size)
    }

    @Test fun `cap keeps the NEWEST window of a long chat`() {
        val many = """{"messages":[${(1..200).joinToString(",") { """{"role":"user","content":"m$it","ts":$it}""" }}]}"""
        val msgs = SessionHistory.parse(many, cap = 3)
        assertEquals(listOf("m198", "m199", "m200"), msgs.map { it.text }) // oldest 197 dropped
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parse(many, cap = 0))
    }

    @Test fun `oversized content trims to the watch cap with ellipsis`() {
        val big = """{"messages":[{"role":"assistant","content":"${"x".repeat(5000)}","ts":1}]}"""
        val msg = SessionHistory.parse(big).single()
        assertEquals(SessionHistory.MAX_TEXT + 1, msg.text.length) // 2000 chars + the … marker
        assertTrue(msg.text.endsWith("…"))
    }

    @Test fun `empty and malformed payloads — missing key parses empty junk throws`() {
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parse("""{"messages":[]}"""))
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parse("""{"count":0}"""))
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parse("""{"messages":[42,null]}"""))
        assertThrows(Exception::class.java) { SessionHistory.parse("<html>502</html>") }
    }

    @Test fun `messages are id-zero for the caller to stamp`() {
        // Seeding re-ids rows against the live chat's counter — parse must not invent ids.
        assertTrue(SessionHistory.parse(sample).all { it.id == 0L })
    }

    // ── the backfill rule + probe order (pure owners of the two behaviors) ────────

    @Test fun `backfill fires only where no local transcript stands`() {
        // fresh adoption (computer/desktop chat): zero local rows
        assertTrue(SessionHistory.shouldBackfill("20261023_185354_f6d464", localTranscriptSize = 0))
        // wear-profile session created via curl/another device — same: nothing local
        assertTrue(SessionHistory.shouldBackfill("wear-watch-curl99", localTranscriptSize = 0))
        // ⌚ watch-created chat WITH rows: the local transcript stands, no backfill — ever
        assertFalse(SessionHistory.shouldBackfill("wear-watch-mvma9on0", localTranscriptSize = 7))
        // blank id never backfills, whatever the transcript says
        assertFalse(SessionHistory.shouldBackfill("", localTranscriptSize = 0))
    }

    @Test fun `profileProbes tries the claimed profile then the computer home`() {
        assertEquals(listOf("wear", "default"), SessionHistory.profileProbes("wear"))
        assertEquals(listOf("default"), SessionHistory.profileProbes("default")) // no double-knock
        assertEquals(listOf("default"), SessionHistory.profileProbes(""))         // unknown → home first
        assertEquals(listOf("research", "default"), SessionHistory.profileProbes("  research "))
    }

    @Test fun `caption reads like the sessions chip — computer not default`() {
        assertEquals("history · 12 messages · from 💻 computer", SessionHistory.captionLine(12, "default"))
        assertEquals("history · 3 messages · from research", SessionHistory.captionLine(3, "research"))
    }
}
