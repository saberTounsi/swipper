package com.swipper.app.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.control.TargetApps
import com.swipper.app.gesture.FractionPoint
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Persistance (Jetpack DataStore Preferences) des [Settings] et de l'état du moteur
 * ([BehaviorState]). Une seule instance de DataStore par processus ([get]).
 */
class SettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    /** Écritures de l'état qui doivent survivre à l'arrêt du service (portée du processus). */
    writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) {
            Log.w(TAG, "Lecture des réglages impossible, valeurs par défaut", e)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }

    /** Réglages courants (défauts si absents ou invalides). */
    val settings: Flow<Settings> = data.map { readSettings(it) }.distinctUntilChanged()

    /** État du moteur sauvegardé ; `null` si aucun. */
    val behaviorState: Flow<BehaviorState?> = data.map { readState(it) }.distinctUntilChanged()

    /**
     * Enregistre [settings], sauf les positions calibrées ([Settings.profilePoints]), qui
     * ne s'écrivent que par [saveProfilePoint] (une calibration faite pendant que l'écran de
     * réglages est ouvert n'est pas écrasée).
     *
     * @throws IllegalArgumentException si les réglages sont invalides (l'écran valide avant).
     */
    suspend fun save(settings: Settings) {
        require(settings.isValid) { "réglages invalides : ${settings.validate()}" }
        store.edit { p ->
            p[RIGHT_RATIO] = settings.rightRatioPercent
            p[DELAY_MEDIAN] = settings.delayMedianSeconds
            p[SESSION_MIN] = settings.sessionMin
            p[SESSION_MAX] = settings.sessionMax
            p[REST_MIN] = settings.restMinMinutes
            p[REST_MAX] = settings.restMaxMinutes
            p[DAILY_CAP] = settings.dailyCap
            p[ACTIVE_START] = settings.activeStartMinute
            p[ACTIVE_END] = settings.activeEndMinute
            p[TARGETS] = settings.allowedPackages
            p[PROFILE_OPEN] = settings.profileOpenPercent
        }
    }

    /**
     * Enregistre la position calibrée d'ouverture de profil de [packageName].
     *
     * @throws IllegalArgumentException si [packageName] n'est pas une app cible connue.
     */
    suspend fun saveProfilePoint(packageName: String, point: FractionPoint) {
        require(packageName in TargetApps.PACKAGES) { "app cible inconnue : $packageName" }
        store.edit { p ->
            p[profileXKey(packageName)] = point.x
            p[profileYKey(packageName)] = point.y
        }
    }

    /** Lecture ponctuelle des réglages. */
    suspend fun currentSettings(): Settings = settings.first()

    /**
     * Lecture ponctuelle de l'état du moteur, après l'écriture de tout état demandé via
     * [saveBehaviorStateAsync] (une nouvelle instance du service ne relit jamais un état
     * plus ancien que celui laissé par la précédente).
     */
    suspend fun currentBehaviorState(): BehaviorState? {
        flushBehaviorState()
        return behaviorState.first()
    }

    /** Attend que le dernier état demandé via [saveBehaviorStateAsync] ait été écrit (ou ait échoué). */
    suspend fun flushBehaviorState() {
        val requested = pendingState.value?.first ?: return
        writtenSeq.first { it >= requested }
    }

    /** Enregistre l'état du moteur. */
    suspend fun saveBehaviorState(state: BehaviorState) {
        store.edit { p ->
            p[SWIPES_TODAY] = state.swipesToday
            p[DAY] = state.day.toString()
            p[SESSION_SWIPES] = state.sessionSwipes
            if (state.sessionTarget != null) p[SESSION_TARGET] = state.sessionTarget else p.remove(SESSION_TARGET)
            if (state.restUntil != null) p[REST_UNTIL] = state.restUntil.toEpochMilli() else p.remove(REST_UNTIL)
        }
    }

    /** Dernier état à écrire, numéroté ; un seul collecteur écrit, toujours le plus récent. */
    private val pendingState = MutableStateFlow<Pair<Long, BehaviorState>?>(null)

    /** Numéro du dernier état traité (écrit ou en échec) par le collecteur. */
    private val writtenSeq = MutableStateFlow(0L)

    private val seqLock = Any()
    private var nextSeq = 0L

    init {
        writeScope.launch {
            pendingState.filterNotNull().collect { (seq, state) ->
                try {
                    saveBehaviorState(state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Une écriture ratée n'arrête pas la persistance des suivantes.
                    runCatching { Log.w(TAG, "Écriture de l'état impossible", e) }
                }
                writtenSeq.value = seq
            }
        }
    }

    /**
     * Enregistre l'état du moteur sans attendre, dans une portée qui survit au service.
     * Les états intermédiaires peuvent être sautés ; le dernier est toujours écrit.
     */
    fun saveBehaviorStateAsync(state: BehaviorState) {
        synchronized(seqLock) { pendingState.value = ++nextSeq to state }
    }

    private fun readSettings(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            rightRatioPercent = p[RIGHT_RATIO] ?: d.rightRatioPercent,
            delayMedianSeconds = p[DELAY_MEDIAN] ?: d.delayMedianSeconds,
            sessionMin = p[SESSION_MIN] ?: d.sessionMin,
            sessionMax = p[SESSION_MAX] ?: d.sessionMax,
            restMinMinutes = p[REST_MIN] ?: d.restMinMinutes,
            restMaxMinutes = p[REST_MAX] ?: d.restMaxMinutes,
            dailyCap = p[DAILY_CAP] ?: d.dailyCap,
            activeStartMinute = p[ACTIVE_START] ?: d.activeStartMinute,
            activeEndMinute = p[ACTIVE_END] ?: d.activeEndMinute,
            targetPackages = p[TARGETS] ?: d.targetPackages,
            profileOpenPercent = p[PROFILE_OPEN] ?: d.profileOpenPercent,
            profilePoints = readProfilePoints(p),
        ).orDefaults()
    }

    /** Positions calibrées des apps cibles connues ; une position illisible est ignorée. */
    private fun readProfilePoints(p: Preferences): Map<String, FractionPoint> =
        TargetApps.PACKAGES.mapNotNull { pkg ->
            val x = p[profileXKey(pkg)] ?: return@mapNotNull null
            val y = p[profileYKey(pkg)] ?: return@mapNotNull null
            runCatching { pkg to FractionPoint(x, y) }.getOrNull()
        }.toMap()

    /**
     * État sauvegardé ; `null` seulement sans jour lisible. Des champs de session corrompus
     * sont remis à zéro sans toucher au compteur du jour (pas de contournement du plafond).
     */
    private fun readState(p: Preferences): BehaviorState? {
        val day = p[DAY]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return BehaviorState(
            swipesToday = (p[SWIPES_TODAY] ?: 0).coerceAtLeast(0),
            day = day,
            sessionSwipes = (p[SESSION_SWIPES] ?: 0).coerceAtLeast(0),
            sessionTarget = p[SESSION_TARGET]?.takeIf { it > 0 },
            restUntil = p[REST_UNTIL]?.let { Instant.ofEpochMilli(it) },
        )
    }

    companion object {
        private const val TAG = "SettingsRepository"

        private val RIGHT_RATIO = intPreferencesKey("right_ratio_percent")
        private val DELAY_MEDIAN = doublePreferencesKey("delay_median_seconds")
        private val SESSION_MIN = intPreferencesKey("session_min")
        private val SESSION_MAX = intPreferencesKey("session_max")
        private val REST_MIN = intPreferencesKey("rest_min_minutes")
        private val REST_MAX = intPreferencesKey("rest_max_minutes")
        private val DAILY_CAP = intPreferencesKey("daily_cap")
        private val ACTIVE_START = intPreferencesKey("active_start_minute")
        private val ACTIVE_END = intPreferencesKey("active_end_minute")
        private val TARGETS = stringSetPreferencesKey("target_packages")
        private val PROFILE_OPEN = intPreferencesKey("profile_open_percent")

        private fun profileXKey(pkg: String) = floatPreferencesKey("profile_x_$pkg")
        private fun profileYKey(pkg: String) = floatPreferencesKey("profile_y_$pkg")

        private val SWIPES_TODAY = intPreferencesKey("state_swipes_today")
        private val DAY = stringPreferencesKey("state_day")
        private val SESSION_SWIPES = intPreferencesKey("state_session_swipes")
        private val SESSION_TARGET = intPreferencesKey("state_session_target")
        private val REST_UNTIL = longPreferencesKey("state_rest_until_ms")

        @Volatile
        private var instance: SettingsRepository? = null

        /** Instance unique du processus (DataStore interdit deux instances sur le même fichier). */
        fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: SettingsRepository(
                PreferenceDataStoreFactory.create {
                    context.applicationContext.preferencesDataStoreFile("swipper_settings")
                },
            ).also { instance = it }
        }
    }
}
