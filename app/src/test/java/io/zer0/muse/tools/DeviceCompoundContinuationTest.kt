package io.zer0.muse.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix — Compound Device Commands (2026-11-08): the pure
 * decision layer behind the loop's continuation check / honest partial
 * ending. The loop wiring itself (ToolOrchestrator completion branch) is
 * exercised by ToolOrchestratorRunLoopTest; these tests pin the
 * classification and the decision matrix it delegates.
 */
class DeviceCompoundContinuationTest {

    // ── Compound classification ──────────────────────────────────────

    @Test
    fun `malik's exact compound command is compound`() {
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("YouTube open krk sharara song open kro"))
    }

    @Test
    fun `two goal families are compound`() {
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("YouTube kholo aur gana chalao"))
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("YouTube kholo phir pehla gana chalao"))
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("search settings and open wifi"))
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("mere phone par YouTube open krk video play kro"))
    }

    @Test
    fun `one goal chained or aimed at a named target is compound`() {
        // sequencing marker
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("YouTube kholo phir batao"))
        // named target beyond the app itself
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("YouTube par Sharara song chalao"))
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("gaana sunao"))
        // quoted title
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("video play karo jiska title \"Sharara\" hai"))
        // a second app named alongside the first
        assertTrue(DeviceCompoundContinuation.isCompoundDeviceCommand("YouTube aur WhatsApp kholo"))
    }

    @Test
    fun `single step device commands are not compound`() {
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand("YouTube kholo"))
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand("WhatsApp kholo"))
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand("mere phone par YouTube kholo"))
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand("YouTube open karo"))
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand("video like karo"))
    }

    @Test
    fun `goal-less or blank text is not compound`() {
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand("hello"))
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand("koi gana batao jo acha ho"))
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand(""))
        assertFalse(DeviceCompoundContinuation.isCompoundDeviceCommand(null))
    }

    // ── Continuation / partial decision matrix ───────────────────────

    @Test
    fun `continuation fires only on the full debt picture with budget and a round left`() {
        assertTrue(
            DeviceCompoundContinuation.shouldRequestContinuation(
                isCompound = true,
                deviceActionExecuted = true,
                actionSinceCheck = true,
                continuationRepromptsUsed = 0,
                canReprompt = true,
            ),
        )
        // budget spent
        assertFalse(
            DeviceCompoundContinuation.shouldRequestContinuation(
                isCompound = true,
                deviceActionExecuted = true,
                actionSinceCheck = true,
                continuationRepromptsUsed = 1,
                canReprompt = true,
            ),
        )
        // no round available / tools force-closed
        assertFalse(
            DeviceCompoundContinuation.shouldRequestContinuation(
                isCompound = true,
                deviceActionExecuted = true,
                actionSinceCheck = true,
                continuationRepromptsUsed = 0,
                canReprompt = false,
            ),
        )
        // debt settled by a verification or a newer step
        assertFalse(
            DeviceCompoundContinuation.shouldRequestContinuation(
                isCompound = true,
                deviceActionExecuted = true,
                actionSinceCheck = false,
                continuationRepromptsUsed = 0,
                canReprompt = true,
            ),
        )
        // nothing executed yet, or not a compound command
        assertFalse(
            DeviceCompoundContinuation.shouldRequestContinuation(
                isCompound = true,
                deviceActionExecuted = false,
                actionSinceCheck = true,
                continuationRepromptsUsed = 0,
                canReprompt = true,
            ),
        )
        assertFalse(
            DeviceCompoundContinuation.shouldRequestContinuation(
                isCompound = false,
                deviceActionExecuted = true,
                actionSinceCheck = true,
                continuationRepromptsUsed = 0,
                canReprompt = true,
            ),
        )
    }

    @Test
    fun `partial marking fires when the debt stands but the check cannot run again`() {
        // the check already ran once and the model still stopped
        assertTrue(
            DeviceCompoundContinuation.shouldMarkPartial(
                isCompound = true,
                deviceActionExecuted = true,
                actionSinceCheck = true,
                continuationRepromptsUsed = 1,
                canReprompt = true,
            ),
        )
        // no round was ever available for the check
        assertTrue(
            DeviceCompoundContinuation.shouldMarkPartial(
                isCompound = true,
                deviceActionExecuted = true,
                actionSinceCheck = true,
                continuationRepromptsUsed = 0,
                canReprompt = false,
            ),
        )
        // the check is still owed instead — no partial marking yet
        assertFalse(
            DeviceCompoundContinuation.shouldMarkPartial(
                isCompound = true,
                deviceActionExecuted = true,
                actionSinceCheck = true,
                continuationRepromptsUsed = 0,
                canReprompt = true,
            ),
        )
        // debt settled / nothing executed / not compound — never marked
        assertFalse(
            DeviceCompoundContinuation.shouldMarkPartial(
                isCompound = true,
                deviceActionExecuted = true,
                actionSinceCheck = false,
                continuationRepromptsUsed = 1,
                canReprompt = true,
            ),
        )
        assertFalse(
            DeviceCompoundContinuation.shouldMarkPartial(
                isCompound = true,
                deviceActionExecuted = false,
                actionSinceCheck = true,
                continuationRepromptsUsed = 1,
                canReprompt = true,
            ),
        )
        assertFalse(
            DeviceCompoundContinuation.shouldMarkPartial(
                isCompound = false,
                deviceActionExecuted = true,
                actionSinceCheck = true,
                continuationRepromptsUsed = 1,
                canReprompt = true,
            ),
        )
    }
}
