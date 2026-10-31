package com.swipper.app.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeepScreenOnTest {

    @Test
    fun `swipe en cours - ecran maintenu allume`() {
        assertTrue(shouldKeepScreenOn(AutomationState.Running, userPaused = false))
    }

    @Test
    fun `pause manuelle - veille normale`() {
        assertFalse(shouldKeepScreenOn(AutomationState.Running, userPaused = true))
        assertFalse(shouldKeepScreenOn(AutomationState.Resting, userPaused = true))
    }

    @Test
    fun `app non cible au premier plan - veille normale`() {
        assertFalse(shouldKeepScreenOn(AutomationState.Paused, userPaused = false))
    }

    @Test
    fun `pause longue - ecran maintenu allume pour la reprise`() {
        assertTrue(shouldKeepScreenOn(AutomationState.Resting, userPaused = false))
    }

    @Test
    fun `plafond et hors plage - veille normale`() {
        assertFalse(shouldKeepScreenOn(AutomationState.CapReached, userPaused = false))
        assertFalse(shouldKeepScreenOn(AutomationState.OutsideHours, userPaused = false))
    }

    @Test
    fun `stop - veille normale`() {
        assertFalse(shouldKeepScreenOn(AutomationState.Idle, userPaused = false))
    }

    @Test
    fun `seuls Running et Resting sans pause manuelle gardent l'ecran allume`() {
        for (state in AutomationState.entries) {
            for (paused in listOf(false, true)) {
                val expected = (state == AutomationState.Running || state == AutomationState.Resting) && !paused
                assertEquals(expected, shouldKeepScreenOn(state, paused), "$state, pause=$paused")
            }
        }
    }
}
