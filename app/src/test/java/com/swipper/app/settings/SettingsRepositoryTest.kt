package com.swipper.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.control.TargetApps
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
}
