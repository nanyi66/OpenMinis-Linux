package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-stall-resume] continuation-note construction for mid-stream stalls. */
class StallResumeTest {

    @Test
    fun blankPartialProducesEmptyNote() {
        assertEquals("", StallResume.note("   "))
        assertEquals("", StallResume.note(""))
    }

    @Test
    fun noteWrapsPartialWithInstruction() {
        val note = StallResume.note("The unit tests pass, but the integration")
        assertTrue(note.startsWith("<stall-resume>"))
        assertTrue(note.endsWith("</stall-resume>"))
        assertTrue(note.contains("Continue from where it stopped"))
        assertTrue(note.contains("Do NOT repeat the text"))
        assertTrue(note.contains("The unit tests pass, but the integration"))
    }

    @Test
    fun noteKeepsOnlyTrailingTailWhenOversized() {
        val long = "x".repeat(20_000)
        val note = StallResume.note(long, maxChars = 300)
        // the instruction itself is fixed overhead; the echo must be capped
        assertTrue("note must be bounded, was ${note.length}", note.length < 1_500)
        assertTrue(note.endsWith("</stall-resume>"))
        // leading content was dropped (only the tail survives)
        assertFalse(note.contains("x".repeat(500)))
        assertTrue(note.contains("x".repeat(200)))
    }

    @Test
    fun noteTrimsThePartial() {
        val note = StallResume.note("  hello  ", maxChars = 100)
        assertTrue(note.contains("hello"))
        assertFalse(note.contains("\n\"\"\"  hello"))
    }
}