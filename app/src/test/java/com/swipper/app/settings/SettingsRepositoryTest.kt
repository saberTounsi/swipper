package com.swipper.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.control.TargetApps
import com.swipper.app.gesture.FractionPoint
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsRepositoryTest {

    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    /** DataStore du fichier [name] ; une seule instance par fichier (règle de DataStore). */
    private fun store(name: String = "test") = PreferenceDataStoreFactory.create(scope = newScope()) {
        File(dir, "$name.preferences_pb")
    }

    private val day = LocalDate.of(2026, 6, 15)

    @Test
    fun `défauts sans données puis aller-retour des réglages`() = runBlocking {
        val repo = SettingsRepository(store(), newScope())
        assertEquals(Settings(), repo.currentSettings())
        assertNull(repo.currentBehaviorState())

        val custom = Settings(
            rightRatioPercent = 60, delayMedianSeconds = 2.5, sessionMin = 10, sessionMax = 20,
            restMinMinutes = 5, restMaxMinutes = 30, dailyCap = 100, activeStartMinute = 20 * 60,
            activeEndMinute = 2 * 60, targetPackages = setOf(TargetApps.BUMBLE),
        )
        repo.save(custom)
        assertEquals(custom, repo.currentSettings())
    }

    @Test
    fun `aller-retour de l'état avec et sans session ni pause`() = runBlocking {
        val repo = SettingsRepository(store(), newScope())
        val full = BehaviorState(40, day, sessionSwipes = 12, sessionTarget = 30, restUntil = Instant.ofEpochMilli(1_781_000_000_000))
        repo.saveBehaviorState(full)
        assertEquals(full, repo.currentBehaviorState())

        val bare = BehaviorState(41, day)
        repo.saveBehaviorState(bare)
        assertEquals(bare, repo.currentBehaviorState(), "sessionTarget et restUntil effacés")
    }

    @Test
    fun `jour corrompu - aucun état, champs de session corrompus - compteur conservé`() = runBlocking {
        val s = store()
        val repo = SettingsRepository(s, newScope())
        s.edit { it[stringPreferencesKey("state_day")] = "pas-une-date" }
        assertNull(repo.currentBehaviorState())

        s.edit {
            it[stringPreferencesKey("state_day")] = day.toString()
            it[intPreferencesKey("state_swipes_today")] = 150
            it[intPreferencesKey("state_session_swipes")] = -3
            it[intPreferencesKey("state_session_target")] = 0
        }
        assertEquals(BehaviorState(150, day, sessionSwipes = 0, sessionTarget = null), repo.currentBehaviorState())
    }

    @Test
    fun `lecture après écriture asynchrone - jamais un état plus ancien`() = runBlocking {
        val repo = SettingsRepository(store(), newScope())
        repo.saveBehaviorState(BehaviorState(10, day))
        repeat(50) { i -> repo.saveBehaviorStateAsync(BehaviorState(11 + i, day, sessionSwipes = i)) }
        assertEquals(BehaviorState(60, day, sessionSwipes = 49), repo.currentBehaviorState())
    }

    @Test
    fun `positions calibrées et probabilité d'ouverture - aller-retour, non écrasées par save`() = runBlocking {
        val repo = SettingsRepository(store(), newScope())
        assertEquals(emptyMap<String, FractionPoint>(), repo.currentSettings().profilePoints)
        repo.saveProfilePoint(TargetApps.TINDER, FractionPoint(0.5f, 0.79f))
        assertEquals(mapOf(TargetApps.TINDER to FractionPoint(0.5f, 0.79f)), repo.currentSettings().profilePoints)

        // Enregistrement des réglages sans la position (formulaire ouvert avant la calibration).
        repo.save(Settings(profileOpenPercent = 30))
        val read = repo.currentSettings()
        assertEquals(30, read.profileOpenPercent)
        assertEquals(FractionPoint(0.5f, 0.79f), read.profilePoint(TargetApps.TINDER))
        assertEquals(null, read.profilePoint(TargetApps.BUMBLE))

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runBlocking { repo.saveProfilePoint("com.example", FractionPoint(0.5f, 0.5f)) }
        }
        Unit
    }

    @Test
    fun `clearProfilePoint - position effacée, autres réglages gardés`() = runBlocking {
        val repo = SettingsRepository(store(), newScope())
        repo.save(Settings(profileOpenPercent = 30))
        repo.saveProfilePoint(TargetApps.TINDER, FractionPoint(0.9f, 0.64f))
        repo.saveProfilePoint(TargetApps.BUMBLE, FractionPoint(0.5f, 0.5f))
        repo.clearProfilePoint(TargetApps.TINDER)
        val read = repo.currentSettings()
        assertEquals(null, read.profilePoint(TargetApps.TINDER))
        assertEquals(FractionPoint(0.5f, 0.5f), read.profilePoint(TargetApps.BUMBLE))
        assertEquals(30, read.profileOpenPercent)
        repo.clearProfilePoint(TargetApps.TINDER) // déjà effacée : sans effet
        assertEquals(null, repo.currentSettings().profilePoint(TargetApps.TINDER))
    }

    @Test
    fun `positions calibrées corrompues - ignorées sans exception`() = runBlocking {
        val s = store()
        val repo = SettingsRepository(s, newScope())
        s.edit {
            it[floatPreferencesKey("profile_x_${TargetApps.TINDER}")] = 1.5f
            it[floatPreferencesKey("profile_y_${TargetApps.TINDER}")] = 0.5f
            it[floatPreferencesKey("profile_x_${TargetApps.BUMBLE}")] = 0.5f // sans y
        }
        val read = repo.currentSettings()
        assertEquals(emptyMap<String, FractionPoint>(), read.profilePoints)
        assertEquals(Settings(), read)
    }
}
