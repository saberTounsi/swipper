package com.swipper.app.settings

import com.swipper.app.behavior.BehaviorConfig
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.control.TargetApps
import com.swipper.app.gesture.FractionPoint
import com.swipper.app.ui.isXiaomiFamily
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class SettingsTest {

    private val defaultsForm = SettingsForm.of(Settings())

    @Test
    fun `openableProfilePoint - Tinder seul, position Bumble ancienne ignoree`() {
        val tinder = FractionPoint(0.9f, 0.8f)
        val s = Settings(profilePoints = mapOf(TargetApps.TINDER to tinder, TargetApps.BUMBLE to FractionPoint(0.5f, 0.5f)))
        assertEquals(tinder, s.openableProfilePoint(TargetApps.TINDER))
        assertEquals(null, s.openableProfilePoint(TargetApps.BUMBLE))
        assertEquals(null, s.openableProfilePoint(null))
        assertEquals(null, Settings().openableProfilePoint(TargetApps.TINDER))
    }

    @Test
    fun `défauts valides et moteur identique à aujourd'hui`() {
        val s = Settings()
        assertTrue(s.validate().isEmpty())
        assertEquals(BehaviorConfig(), s.toBehaviorConfig())
        assertEquals(0.70, s.rightRatio, 1e-9)
        assertEquals(TargetApps.PACKAGES, s.allowedPackages)
    }

    @Test
    fun `formulaire pré-rempli avec les défauts se relit à l'identique`() {
        assertEquals("70", defaultsForm.rightRatioPercent)
        assertEquals("1,8", defaultsForm.delayMedianSeconds)
        assertEquals("09:00", defaultsForm.activeStart)
        assertEquals("23:00", defaultsForm.activeEnd)
        assertEquals(ParseResult.Valid(Settings()), defaultsForm.parse())
    }

    @Test
    fun `ratio 60 et plafond 100 - conversion`() {
        val result = defaultsForm.copy(rightRatioPercent = "60", dailyCap = "100").parse()
        val s = (result as ParseResult.Valid).settings
        assertEquals(0.60, s.rightRatio, 1e-9)
        val config = s.toBehaviorConfig()
        assertEquals(100, config.dailyCap)
        assertEquals(BehaviorConfig().copy(dailyCap = 100), config)
    }

    @Test
    fun `conversion de tous les champs réglables`() {
        val s = Settings(
            delayMedianSeconds = 2.5, sessionMin = 10, sessionMax = 20, restMinMinutes = 5, restMaxMinutes = 30,
            dailyCap = 80, activeStartMinute = 20 * 60, activeEndMinute = 2 * 60,
        )
        val c = s.toBehaviorConfig()
        assertEquals(2_500, c.delayMedianMs)
        assertEquals(10, c.sessionMin)
        assertEquals(20, c.sessionMax)
        assertEquals(5 * 60_000L, c.restMinMs)
        assertEquals(30 * 60_000L, c.restMaxMs)
        assertEquals(80, c.dailyCap)
        assertEquals(1200, c.activeStartMinute)
        assertEquals(120, c.activeEndMinute)
        // Paramètres non réglables : valeurs par défaut.
        assertEquals(BehaviorConfig().delaySigma, c.delaySigma)
        assertEquals(BehaviorConfig().distractionProbability, c.distractionProbability)
    }

    @Test
    fun `session min supérieure au max - erreur sous le champ min`() {
        val result = defaultsForm.copy(sessionMin = "70", sessionMax = "60").parse()
        assertEquals(ParseResult.Invalid(listOf(FieldError(Field.SessionMin, Reason.MinAboveMax))), result)
    }

    @Test
    fun `pause min supérieure au max`() {
        val errors = Settings(restMinMinutes = 100, restMaxMinutes = 90).validate()
        assertEquals(listOf(FieldError(Field.RestMin, Reason.MinAboveMax)), errors)
    }

    @Test
    fun `aucune app cible refusée`() {
        val result = defaultsForm.copy(targetPackages = emptySet()).parse()
        assertEquals(ParseResult.Invalid(listOf(FieldError(Field.TargetApps, Reason.NoTargetApp))), result)
        assertEquals(
            listOf(FieldError(Field.TargetApps, Reason.NoTargetApp)),
            Settings(targetPackages = setOf("com.inconnue")).validate(),
        )
        val bumbleOnly = Settings(targetPackages = setOf(TargetApps.BUMBLE))
        assertTrue(bumbleOnly.isValid)
        assertEquals(setOf(TargetApps.BUMBLE), bumbleOnly.allowedPackages)
    }

    @Test
    fun `valeurs hors bornes ou illisibles - une erreur par champ, jamais d'exception`() {
        val form = defaultsForm.copy(
            rightRatioPercent = "101",
            delayMedianSeconds = "0,5",
            sessionMin = "abc",
            restMaxMinutes = "-1",
            dailyCap = "",
            activeStart = "25:00",
            activeEnd = "9h75",
        )
        val result = assertDoesNotThrow<ParseResult> { form.parse() }
        val fields = (result as ParseResult.Invalid).errors.map { it.field }
        assertEquals(
            setOf(Field.RightRatio, Field.DelayMedian, Field.SessionMin, Field.RestMax, Field.DailyCap, Field.ActiveStart, Field.ActiveEnd),
            fields.toSet(),
        )
        assertEquals(fields.size, fields.toSet().size, "erreur en double : $fields")
        assertTrue(result.errors.all { it.reason == Reason.OutOfRange })
    }

    @Test
    fun `bornes du délai médian 0,8 et 8 s acceptées`() {
        assertTrue(defaultsForm.copy(delayMedianSeconds = "0,8").parse() is ParseResult.Valid)
        assertTrue(defaultsForm.copy(delayMedianSeconds = "8").parse() is ParseResult.Valid)
        assertTrue(defaultsForm.copy(delayMedianSeconds = "8.1").parse() is ParseResult.Invalid)
        // Une décimale au plus : « 1,85 » serait relu « 1,9 » (formulaire aussitôt modifié).
        assertEquals(
            ParseResult.Invalid(listOf(FieldError(Field.DelayMedian, Reason.OutOfRange))),
            defaultsForm.copy(delayMedianSeconds = "1,85").parse(),
        )
        val tenth = (defaultsForm.copy(delayMedianSeconds = "2.5").parse() as ParseResult.Valid).settings
        assertEquals(ParseResult.Valid(tenth), SettingsForm.of(tenth).parse(), "relecture à l'identique")
        assertEquals(800, Settings(delayMedianSeconds = 0.8).toBehaviorConfig().delayMedianMs)
        assertEquals(8_000, Settings(delayMedianSeconds = 8.0).toBehaviorConfig().delayMedianMs)
    }

    @Test
    fun `début égal à la fin refusé`() {
        val result = defaultsForm.copy(activeStart = "10:00", activeEnd = "10h").parse()
        assertEquals(ParseResult.Invalid(listOf(FieldError(Field.ActiveEnd, Reason.SameStartEnd))), result)
    }

    @Test
    fun `réglages invalides - toBehaviorConfig refuse, orDefaults revient aux défauts`() {
        val bad = Settings(sessionMin = 70, sessionMax = 60)
        assertThrows<IllegalArgumentException> { bad.toBehaviorConfig() }
        assertEquals(Settings(), bad.orDefaults())
        val good = Settings(dailyCap = 99)
        assertEquals(good, good.orDefaults())
    }

    @Test
    fun `lecture des heures`() {
        assertEquals(9 * 60, parseTimeOfDay("9:00"))
        assertEquals(9 * 60 + 30, parseTimeOfDay(" 09:30 "))
        assertEquals(23 * 60, parseTimeOfDay("23h"))
        assertEquals(7 * 60 + 5, parseTimeOfDay("7h05"))
        assertEquals(0, parseTimeOfDay("0"))
        assertEquals(null, parseTimeOfDay("24:00"))
        assertEquals(null, parseTimeOfDay("12:60"))
        assertEquals(null, parseTimeOfDay(""))
        assertEquals("07:05", formatTimeOfDay(425))
    }

    @Test
    fun `compteur du jour - remis à zéro le lendemain`() {
        val day = LocalDate.of(2026, 6, 15)
        val state = BehaviorState(40, day, sessionSwipes = 10, sessionTarget = 30)
        assertEquals(40, swipesForDay(state, day))
        assertEquals(0, swipesForDay(state, day.plusDays(1)))
        assertEquals(0, swipesForDay(null, day))
    }

    @Test
    fun `détection Xiaomi insensible à la casse`() {
        assertTrue(isXiaomiFamily("Xiaomi"))
        assertTrue(isXiaomiFamily("XIAOMI"))
        assertTrue(isXiaomiFamily("Redmi"))
        assertTrue(isXiaomiFamily("POCO"))
        assertFalse(isXiaomiFamily("Google"))
        assertFalse(isXiaomiFamily("samsung"))
        assertFalse(isXiaomiFamily(null))
    }

    @Test
    fun `ouverture de profil - probabilité 0–30 %, défaut 8 %, positions recopiées par le formulaire`() {
        assertEquals(0.08, Settings().toBehaviorConfig().profileOpenProbability, 1e-12)
        assertEquals("8", defaultsForm.profileOpenPercent)
        val zero = (defaultsForm.copy(profileOpenPercent = "0").parse() as ParseResult.Valid).settings
        assertEquals(0.0, zero.toBehaviorConfig().profileOpenProbability)
        val thirty = (defaultsForm.copy(profileOpenPercent = "30").parse() as ParseResult.Valid).settings
        assertEquals(0.30, thirty.toBehaviorConfig().profileOpenProbability, 1e-12)
        for (bad in listOf("31", "-1", "abc")) {
            val result = defaultsForm.copy(profileOpenPercent = bad).parse()
            assertEquals(ParseResult.Invalid(listOf(FieldError(Field.ProfileOpen, Reason.OutOfRange))), result, bad)
        }

        val calibrated = Settings(profilePoints = mapOf(TargetApps.TINDER to FractionPoint(0.5f, 0.79f)))
        assertEquals(ParseResult.Valid(calibrated), SettingsForm.of(calibrated).parse())
        assertEquals(FractionPoint(0.5f, 0.79f), calibrated.profilePoint(TargetApps.TINDER))
        assertEquals(null, calibrated.profilePoint(TargetApps.BUMBLE))
        assertEquals(null, calibrated.profilePoint(null))
        // Réglages corrompus : défauts, mais la calibration est conservée.
        assertEquals(calibrated.profilePoints, calibrated.copy(dailyCap = 0).orDefaults().profilePoints)
    }
}
