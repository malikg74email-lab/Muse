package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Auto-Training Pack (Part 2, 2026-11-07).
 *
 * Provider-path coverage note: every provider consumes the SAME
 * assembled system prompt (SystemPromptAssembler builds one UIMessage
 * list; providers differ only in transport — the Phase 1/3 finding).
 * The pack section is a single unconditional add fed from the canonical
 * [WorkingInstructionsPack.TEXT], so content identity across providers
 * holds by construction; this test pins the canonical content itself —
 * every spec section present, in order — plus the pure delivery
 * bookkeeping (ack verify/strip, staleness, keys).
 */
class WorkingInstructionsPackTest {

    @Test
    fun `pack contains every spec section in order`() {
        var lastIndex = -1
        for (marker in WorkingInstructionsPack.SECTION_MARKERS) {
            val idx = WorkingInstructionsPack.TEXT.indexOf(marker)
            assertTrue("missing pack section: $marker", idx >= 0)
            assertTrue("pack section out of order: $marker", idx > lastIndex)
            lastIndex = idx
        }
        assertEquals(10, WorkingInstructionsPack.SECTION_MARKERS.size)
    }

    @Test
    fun `pack carries the standing rules verbatim in substance`() {
        val text = WorkingInstructionsPack.TEXT
        // Identity & owner principle
        assertTrue(text.contains("OWNER"))
        assertTrue(text.contains("Roman Urdu"))
        // Tool truth (Phase 1 law)
        assertTrue(text.contains("Never print a tool call as chat text"))
        // Approval cards (Part 1)
        assertTrue(text.contains("Allow card must appear in chat"))
        assertTrue(text.contains("No card, no action"))
        // Settings auto-on — the owner is never sent hunting
        assertTrue(text.contains("NEVER tell the owner to \"go to Settings and find X\""))
        // Browser method
        assertTrue(text.contains("Perceive, then act, then verify"))
        assertTrue(text.contains("CAPTCHA"))
        // Coding completeness (Part 3)
        assertTrue(text.contains("write it with the file tools in chunks"))
        assertTrue(text.contains("NEVER the final delivery"))
        // Modes (Phase 3)
        assertTrue(text.contains("never touch the real screen unasked"))
        // Live call conduct
        assertTrue(text.contains("Never fake success out loud"))
    }

    @Test
    fun `ack line format is exact`() {
        assertEquals("SHAHEEN_READY PACK_V1", WorkingInstructionsPack.ackLine(1))
        assertEquals("SHAHEEN_READY PACK_V7", WorkingInstructionsPack.ackLine(7))
    }

    @Test
    fun `ack verifies only as the first non-blank line`() {
        assertTrue(WorkingInstructionsPack.verifyAck("SHAHEEN_READY PACK_V1\nMalik, ho gaya."))
        assertTrue(WorkingInstructionsPack.verifyAck("\n\nSHAHEEN_READY PACK_V1\nDone"))
        assertTrue(WorkingInstructionsPack.verifyAck("SHAHEEN_READY PACK_V1 \nDone"))
        assertFalse(WorkingInstructionsPack.verifyAck("Malik, SHAHEEN_READY PACK_V1"))
        assertFalse(WorkingInstructionsPack.verifyAck("Done.\nSHAHEEN_READY PACK_V1"))
        assertFalse(WorkingInstructionsPack.verifyAck("SHAHEEN_READY PACK_V2\nDone", version = 1))
        assertFalse(WorkingInstructionsPack.verifyAck(""))
    }

    @Test
    fun `strip ack removes only the ack line`() {
        assertEquals(
            "Malik, ho gaya.",
            WorkingInstructionsPack.stripAck("SHAHEEN_READY PACK_V1\n\nMalik, ho gaya."),
        )
        assertEquals(
            "No ack here.",
            WorkingInstructionsPack.stripAck("No ack here."),
        )
        assertEquals(
            "SHAHEEN_READY PACK_V1",
            WorkingInstructionsPack.stripAck("SHAHEEN_READY PACK_V1", version = 2),
        )
    }

    @Test
    fun `delivery directive carries the exact ack line`() {
        val directive = WorkingInstructionsPack.deliveryDirective(3)
        assertTrue(directive.contains("SHAHEEN_READY PACK_V3"))
        assertTrue(directive.contains("v3"))
    }

    @Test
    fun `delivery key needs both ids`() {
        assertEquals("prov-1|model-9", WorkingInstructionsPack.deliveryKey("prov-1", "model-9"))
        assertNull(WorkingInstructionsPack.deliveryKey(null, "model-9"))
        assertNull(WorkingInstructionsPack.deliveryKey("prov-1", "  "))
        assertNull(WorkingInstructionsPack.deliveryKey("", ""))
    }

    @Test
    fun `staleness - no record or older version needs delivery`() {
        assertTrue(WorkingInstructionsPack.needsDelivery(null, currentVersion = 2))
        assertTrue(
            WorkingInstructionsPack.needsDelivery(
                PackDeliveryRecord(packVersion = 1, deliveredAtMs = 100L, ackStatus = AckStatus.VERIFIED),
                currentVersion = 2,
            ),
        )
        assertFalse(
            WorkingInstructionsPack.needsDelivery(
                PackDeliveryRecord(packVersion = 2, deliveredAtMs = 100L, ackStatus = AckStatus.VERIFIED),
                currentVersion = 2,
            ),
        )
        // An unverified delivery at the current version is NOT re-delivered
        // every session: the ack is weak evidence, behaviour is the proof.
        assertFalse(
            WorkingInstructionsPack.needsDelivery(
                PackDeliveryRecord(
                    packVersion = 2,
                    deliveredAtMs = 100L,
                    ackStatus = AckStatus.DELIVERED_UNVERIFIED,
                ),
                currentVersion = 2,
            ),
        )
        // A record from a newer pack never triggers a downgrade delivery.
        assertFalse(
            WorkingInstructionsPack.needsDelivery(
                PackDeliveryRecord(packVersion = 3, deliveredAtMs = 100L, ackStatus = AckStatus.VERIFIED),
                currentVersion = 2,
            ),
        )
    }
}
