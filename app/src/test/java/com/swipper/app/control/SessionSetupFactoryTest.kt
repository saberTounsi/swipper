package com.swipper.app.control

import com.swipper.app.behavior.Action
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.behavior.Clock
import com.swipper.app.gesture.FractionPoint
import com.swipper.app.settings.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionSetupFactoryTest {

    private var now: Instant = Instant.parse("2026-06-15T12:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now
        override val zone: ZoneId = ZoneOffset.UTC
    }

    @Test
    fun `réglages valides - moteur, ratio et apps cibles repris`() {
        val settings = Settings(rightRatioPercent = 60, dailyCap = 80, targetPackages = setOf(TargetApps.BUMBLE))
        val setup = sessionSetupFrom(settings, null, Random(1), clock) { null }
        assertEquals(settings.toBehaviorConfig(), setup.engine.config)
        assertEquals(0.60, setup.picker.rightRatio, 1e-9)
        assertTrue(setup.isTarget(TargetApps.BUMBLE))
        assertFalse(setup.isTarget(TargetApps.TINDER))
        assertFalse(setup.isTarget("com.example"))
        assertFalse(setup.isTarget(null))
    }

    @Test
    fun `réglages invalides - défauts utilisés`() {
        val invalid = Settings(sessionMin = 50, sessionMax = 10, rightRatioPercent = 90)
        assertFalse(invalid.isValid)
        val setup = sessionSetupFrom(invalid, null, Random(1), clock) { null }
        assertEquals(Settings().toBehaviorConfig(), setup.engine.config)
        assertEquals(Settings().rightRatio, setup.picker.rightRatio, 1e-9)
        assertTrue(setup.isTarget(TargetApps.TINDER) && setup.isTarget(TargetApps.BUMBLE))
    }

    @Test
    fun `état sauvegardé repris - compteur du jour et plafond`() {
        val state = BehaviorState(swipesToday = 80, day = LocalDate.of(2026, 6, 15))
        val setup = sessionSetupFrom(Settings(dailyCap = 80), state, Random(1), clock) { null }
        assertEquals(80, setup.engine.swipesToday)
        assertTrue(setup.engine.nextAction() is Action.CapReached)
    }

    @Test
    fun `ouverture de profil - suit la position lue en direct`() {
        var point: FractionPoint? = null
        val setup = sessionSetupFrom(Settings(profileOpenPercent = 30), null, Random(3), clock) { point }
        fun opens(n: Int) = (1..n).count {
            val a = setup.engine.nextAction()
            if (a is Action.Swipe || a is Action.OpenProfile) setup.engine.recordSwipe()
            a is Action.OpenProfile
        }
        assertEquals(0, opens(100), "ouverture sans position calibrée")
        point = FractionPoint(0.9f, 0.8f) // calibration faite pendant la session
        assertTrue(opens(100) > 0, "aucune ouverture une fois calibré")
    }
}
