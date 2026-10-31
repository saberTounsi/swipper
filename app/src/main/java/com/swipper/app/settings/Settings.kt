package com.swipper.app.settings

import com.swipper.app.behavior.BehaviorConfig
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.control.TargetApps
import com.swipper.app.gesture.FractionPoint
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Réglages de l'utilisateur (CAP-6), persistés par [SettingsRepository]. Les paramètres du
 * moteur absents d'ici gardent les valeurs par défaut de [BehaviorConfig] ; la géométrie
 * des gestes et la zone de swipe ne sont pas réglables.
 *
 * Kotlin pur. Une instance peut être invalide : [validate] le dit, [toBehaviorConfig] ne
 * s'appelle que sur des réglages valides.
 *
 * @property rightRatioPercent part des swipes à droite, en %, dans [0 ; 100].
 * @property delayMedianSeconds médiane du délai entre swipes, en s, dans [0,8 ; 8].
 * @property sessionMin nombre minimal de swipes par session.
 * @property sessionMax nombre maximal de swipes par session.
 * @property restMinMinutes pause longue minimale entre deux sessions, en minutes.
 * @property restMaxMinutes pause longue maximale, en minutes.
 * @property dailyCap plafond quotidien de swipes.
 * @property activeStartMinute début de la plage horaire, en minutes depuis minuit.
 * @property activeEndMinute fin de la plage horaire, en minutes depuis minuit.
 * @property targetPackages apps cibles cochées, parmi [TargetApps.PACKAGES].
 * @property profileOpenPercent probabilité d'ouvrir un profil avant de swiper, en %, dans
 *   [0 ; 30] (story 7).
 * @property profilePoints position calibrée d'ouverture de profil par app (fractions de
 *   l'écran) ; une app absente n'ouvre jamais de profil. Enregistrée par la calibration
 *   ([SettingsRepository.saveProfilePoint]), pas par l'écran de réglages.
 */
data class Settings(
    val rightRatioPercent: Int = 70,
    val delayMedianSeconds: Double = 1.8,
    val sessionMin: Int = 25,
    val sessionMax: Int = 60,
    val restMinMinutes: Int = 15,
    val restMaxMinutes: Int = 90,
    val dailyCap: Int = 150,
    val activeStartMinute: Int = 9 * 60,
    val activeEndMinute: Int = 23 * 60,
    val targetPackages: Set<String> = TargetApps.PACKAGES,
    val profileOpenPercent: Int = 8,
    val profilePoints: Map<String, FractionPoint> = emptyMap(),
) {
    /** Erreurs de validation, vide si les réglages sont valides. Ne lève jamais d'exception. */
    fun validate(): List<FieldError> = buildList {
        if (rightRatioPercent !in RATIO_RANGE) add(FieldError(Field.RightRatio, Reason.OutOfRange))
        if (!delayMedianSeconds.isFinite() || delayMedianSeconds < DELAY_MIN_S || delayMedianSeconds > DELAY_MAX_S) {
            add(FieldError(Field.DelayMedian, Reason.OutOfRange))
        }
        if (sessionMin !in SESSION_RANGE) add(FieldError(Field.SessionMin, Reason.OutOfRange))
        if (sessionMax !in SESSION_RANGE) add(FieldError(Field.SessionMax, Reason.OutOfRange))
        if (sessionMin in SESSION_RANGE && sessionMax in SESSION_RANGE && sessionMin > sessionMax) {
            add(FieldError(Field.SessionMin, Reason.MinAboveMax))
        }
        if (restMinMinutes !in REST_RANGE) add(FieldError(Field.RestMin, Reason.OutOfRange))
        if (restMaxMinutes !in REST_RANGE) add(FieldError(Field.RestMax, Reason.OutOfRange))
        if (restMinMinutes in REST_RANGE && restMaxMinutes in REST_RANGE && restMinMinutes > restMaxMinutes) {
            add(FieldError(Field.RestMin, Reason.MinAboveMax))
        }
        if (dailyCap !in CAP_RANGE) add(FieldError(Field.DailyCap, Reason.OutOfRange))
        if (activeStartMinute !in MINUTE_RANGE) add(FieldError(Field.ActiveStart, Reason.OutOfRange))
        if (activeEndMinute !in MINUTE_RANGE) add(FieldError(Field.ActiveEnd, Reason.OutOfRange))
        if (activeStartMinute in MINUTE_RANGE && activeStartMinute == activeEndMinute) {
            add(FieldError(Field.ActiveEnd, Reason.SameStartEnd))
        }
        if (targetPackages.none { it in TargetApps.PACKAGES }) add(FieldError(Field.TargetApps, Reason.NoTargetApp))
        if (profileOpenPercent !in PROFILE_OPEN_RANGE) add(FieldError(Field.ProfileOpen, Reason.OutOfRange))
    }

    /** `true` si [validate] ne renvoie aucune erreur. */
    val isValid: Boolean get() = validate().isEmpty()

    /**
     * Configuration du moteur correspondante (autres paramètres par défaut).
     *
     * @throws IllegalArgumentException si les réglages sont invalides (voir [validate]).
     */
    fun toBehaviorConfig(): BehaviorConfig {
        val errors = validate()
        require(errors.isEmpty()) { "réglages invalides : $errors" }
        return BehaviorConfig(
            delayMedianMs = (delayMedianSeconds * 1000).roundToLong(),
            sessionMin = sessionMin,
            sessionMax = sessionMax,
            restMinMs = restMinMinutes * MINUTE_MS,
            restMaxMs = restMaxMinutes * MINUTE_MS,
            dailyCap = dailyCap,
            activeStartMinute = activeStartMinute,
            activeEndMinute = activeEndMinute,
            profileOpenProbability = profileOpenPercent / 100.0,
        )
    }

    /** Position calibrée d'ouverture de profil de [packageName], ou `null`. */
    fun profilePoint(packageName: String?): FractionPoint? = packageName?.let { profilePoints[it] }

    /**
     * Position où taper pour ouvrir un profil dans [packageName] : seulement pour une app qui
     * ouvre son profil par un tap ([TargetApps.supportsProfile]) ; une position Bumble
     * enregistrée par une version précédente est ignorée.
     */
    fun openableProfilePoint(packageName: String?): FractionPoint? =
        profilePoint(packageName)?.takeIf { TargetApps.supportsProfile(packageName) }

    /** Ratio de swipes à droite pour le `DirectionPicker`, dans [0 ; 1]. */
    val rightRatio: Double get() = rightRatioPercent / 100.0

    /** Apps cibles effectives (seulement les paquets connus). */
    val allowedPackages: Set<String> get() = targetPackages intersect TargetApps.PACKAGES

    /** Ces réglages s'ils sont valides, sinon les réglages par défaut (données corrompues). */
    fun orDefaults(): Settings = if (isValid) this else Settings(profilePoints = profilePoints)

    companion object {
        val RATIO_RANGE = 0..100
        const val DELAY_MIN_S = 0.8
        const val DELAY_MAX_S = 8.0
        val SESSION_RANGE = 1..1_000
        val REST_RANGE = 1..24 * 60
        val CAP_RANGE = 1..10_000
        val MINUTE_RANGE = 0 until 24 * 60
        val PROFILE_OPEN_RANGE = 0..30
        private const val MINUTE_MS = 60_000L
    }
}

/** Champ de l'écran de réglages. */
enum class Field {
    RightRatio, DelayMedian, SessionMin, SessionMax, RestMin, RestMax, DailyCap, ActiveStart, ActiveEnd, TargetApps,
    ProfileOpen,
}

/** Motif d'une erreur de saisie. */
enum class Reason {
    /** Valeur illisible ou hors bornes. */
    OutOfRange,

    /** Minimum supérieur au maximum (signalé sous le champ du minimum). */
    MinAboveMax,

    /** Début et fin de la plage horaire identiques (signalé sous la fin). */
    SameStartEnd,

    /** Aucune app cible cochée. */
    NoTargetApp,
}

/** Erreur signalée sous [field]. */
data class FieldError(val field: Field, val reason: Reason)

/**
 * Saisie brute de l'écran de réglages (texte des champs). [parse] la convertit en
 * [Settings] ; un texte illisible donne [Reason.OutOfRange] sur son champ.
 */
data class SettingsForm(
    val rightRatioPercent: String,
    val delayMedianSeconds: String,
    val sessionMin: String,
    val sessionMax: String,
    val restMinMinutes: String,
    val restMaxMinutes: String,
    val dailyCap: String,
    val activeStart: String,
    val activeEnd: String,
    val targetPackages: Set<String>,
    val profileOpenPercent: String = Settings().profileOpenPercent.toString(),
    /** Positions calibrées, recopiées telles quelles (non éditées par le formulaire). */
    val profilePoints: Map<String, FractionPoint> = emptyMap(),
) {
    /** Réglages valides, ou la liste des erreurs (jamais d'exception). */
    fun parse(): ParseResult {
        val parseErrors = mutableListOf<FieldError>()
        fun int(text: String, field: Field): Int? =
            text.trim().toIntOrNull() ?: null.also { parseErrors += FieldError(field, Reason.OutOfRange) }
        fun minute(text: String, field: Field): Int? =
            parseTimeOfDay(text) ?: null.also { parseErrors += FieldError(field, Reason.OutOfRange) }
        // Une décimale au plus : c'est la précision du formulaire relu (pas d'arrondi silencieux).
        val delay = delayMedianSeconds.trim().takeIf { DECIMAL_REGEX.matches(it) }?.replace(',', '.')?.toDoubleOrNull()
            ?: null.also { parseErrors += FieldError(Field.DelayMedian, Reason.OutOfRange) }

        val settings = Settings(
            rightRatioPercent = int(rightRatioPercent, Field.RightRatio) ?: DEFAULTS.rightRatioPercent,
            delayMedianSeconds = delay ?: DEFAULTS.delayMedianSeconds,
            sessionMin = int(sessionMin, Field.SessionMin) ?: DEFAULTS.sessionMin,
            sessionMax = int(sessionMax, Field.SessionMax) ?: DEFAULTS.sessionMax,
            restMinMinutes = int(restMinMinutes, Field.RestMin) ?: DEFAULTS.restMinMinutes,
            restMaxMinutes = int(restMaxMinutes, Field.RestMax) ?: DEFAULTS.restMaxMinutes,
            dailyCap = int(dailyCap, Field.DailyCap) ?: DEFAULTS.dailyCap,
            activeStartMinute = minute(activeStart, Field.ActiveStart) ?: DEFAULTS.activeStartMinute,
            activeEndMinute = minute(activeEnd, Field.ActiveEnd) ?: DEFAULTS.activeEndMinute,
            targetPackages = targetPackages,
            profileOpenPercent = int(profileOpenPercent, Field.ProfileOpen) ?: DEFAULTS.profileOpenPercent,
            profilePoints = profilePoints,
        )
        // Un champ illisible n'est signalé qu'une fois (pas d'erreur dérivée de la valeur de repli).
        val unreadable = parseErrors.map { it.field }.toSet()
        val errors = parseErrors + settings.validate().filter { it.field !in unreadable }
        return if (errors.isEmpty()) ParseResult.Valid(settings) else ParseResult.Invalid(errors)
    }

    companion object {
        private val DEFAULTS = Settings()

        /** Formulaire pré-rempli avec [settings]. */
        fun of(settings: Settings): SettingsForm = SettingsForm(
            rightRatioPercent = settings.rightRatioPercent.toString(),
            delayMedianSeconds = formatSeconds(settings.delayMedianSeconds),
            sessionMin = settings.sessionMin.toString(),
            sessionMax = settings.sessionMax.toString(),
            restMinMinutes = settings.restMinMinutes.toString(),
            restMaxMinutes = settings.restMaxMinutes.toString(),
            dailyCap = settings.dailyCap.toString(),
            activeStart = formatTimeOfDay(settings.activeStartMinute),
            activeEnd = formatTimeOfDay(settings.activeEndMinute),
            targetPackages = settings.targetPackages,
            profileOpenPercent = settings.profileOpenPercent.toString(),
            profilePoints = settings.profilePoints,
        )
    }
}

/** Résultat de [SettingsForm.parse]. */
sealed interface ParseResult {
    data class Valid(val settings: Settings) : ParseResult
    data class Invalid(val errors: List<FieldError>) : ParseResult
}

/** Nombre positif avec au plus une décimale (virgule ou point). */
private val DECIMAL_REGEX = Regex("""^\d+(?:[.,]\d)?$""")

private val TIME_REGEX =Regex("""^(\d{1,2})(?:[:hH](\d{2})?)?$""")

/** « 9:00 », « 09:30 », « 23h », « 7 » → minutes depuis minuit ; `null` si illisible ou hors bornes. */
fun parseTimeOfDay(text: String): Int? {
    val m = TIME_REGEX.matchEntire(text.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
    if (h !in 0..23 || min !in 0..59) return null
    return h * 60 + min
}

/** Minutes depuis minuit → « HH:mm ». */
fun formatTimeOfDay(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

/** Secondes avec une décimale et une virgule (« 1,8 »). */
fun formatSeconds(seconds: Double): String {
    val tenths = (seconds * 10).roundToInt()
    return "${tenths / 10},${tenths % 10}"
}

/** Compteur à afficher pour [today] : 0 si l'état sauvegardé date d'un jour passé (ou est absent). */
fun swipesForDay(state: BehaviorState?, today: LocalDate): Int =
    if (state == null || today.isAfter(state.day)) 0 else state.swipesToday
