package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rewrap contract for edit-&-resend (owner batch 2): what actually becomes the new ask. */
class PromptEditTest {

    @Test fun realEditTrimmedToOutgoingAsk() {
        assertEquals("what about the tests?", PromptEdit.rewrap("what about the docs?", "  what about the tests?  "))
    }

    @Test fun blankEditIsANoOp() {
        assertNull(PromptEdit.rewrap("original prompt", "   \n\t "))
        assertNull(PromptEdit.rewrap("original prompt", ""))
    }

    @Test fun uneditedTextIsANoOp() {
        // Opening the sheet, changing nothing, tapping send: don't spam the session.
        assertNull(PromptEdit.rewrap("same words", "  same words \n"))
    }

    @Test fun multilineKeptAsTrimmed() {
        val edited = "line one\n  line two  \n"
        assertEquals("line one\n  line two", PromptEdit.rewrap("old", edited))
    }

    @Test fun freshPromptFromEmptyOriginal() {
        // Editor opened on a blank draft ("" original = new prompt): any text sends.
        assertEquals("hello hermes", PromptEdit.rewrap("", "  hello hermes "))
    }
}
