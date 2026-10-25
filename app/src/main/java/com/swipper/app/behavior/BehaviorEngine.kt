package com.swipper.app.behavior

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/** Prochaine action décidée par le [BehaviorEngine]. */
sealed interface Action {
    /** Attendre [delayMs] puis swiper. */
    data class Swipe(val delayMs: Long) : Action

    /** Fin de session : pause longue de [durationMs] avant la session suivante. */
    data class Rest(val durationMs: Long) : Action

    /**
     * Plafond quotidien atteint : attendre [waitMs], soit jusqu'à la première ouverture de
     * la plage horaire à partir du prochain minuit local, plus un décalage aléatoire.
     */
    data class CapReached(val waitMs: Long) : Action

    /**
     * Heure locale hors de la plage horaire d'activité : attendre [waitMs], soit jusqu'à
     * la prochaine ouverture de la plage plus un décalage aléatoire.
     */
    data class OutsideHours(val waitMs: Long) : Action
}

/**
 * État du moteur, persisté (story 6) : nombre de swipes du jour local [day] et avancement
 * de la session en cours ([sessionSwipes] sur [sessionTarget]).
 *
 * @property sessionTarget cible de la session en cours ; `null` = à tirer (aucune session).
 * @property restUntil fin de la pause longue en cours ; `null` = pas de pause. Restaurée,
 *   elle est respectée (Stop puis Démarrer, ou service tué, ne la raccourcit pas).
 * @throws IllegalArgumentException si [swipesToday] < 0, [sessionSwipes] < 0 ou
 *   [sessionTarget] ≤ 0.
 */
data class BehaviorState(
    val swipesToday: Int,
    val day: LocalDate,
    val sessionSwipes: Int = 0,
    val sessionTarget: Int? = null,
    val restUntil: Instant? = null,
) {
    init {
        require(swipesToday >= 0) { "swipesToday doit être ≥ 0 (reçu $swipesToday)" }
        require(sessionSwipes >= 0) { "sessionSwipes doit être ≥ 0 (reçu $sessionSwipes)" }
        require(sessionTarget == null || sessionTarget > 0) { "sessionTarget doit être > 0 (reçu $sessionTarget)" }
    }
}

/**
 * Décide du rythme des swipes (CAP-4) : délai irrégulier, sessions bornées séparées
 * de pauses longues, plafond quotidien, plage horaire d'activité. Les reprises (hors
 * plage, plafond) tombent à l'ouverture de la plage plus un décalage aléatoire, pour ne
 * jamais repartir à heure fixe.
 *
 * Kotlin pur : [random] et [clock] sont injectés. Les swipes ne sont comptés que par
 * [recordSwipe], que l'appelant invoque quand un swipe a réellement eu lieu ;
 * [nextAction] ne modifie pas le compteur de swipes.
 *
 * @param initialState état restauré ; `null` = aucun swipe aujourd'hui. Un état d'un
 *   jour passé est remis à zéro au premier appel. La session restaurée reprend là où elle
 *   en était ; une cible hors de [BehaviorConfig.sessionMin ; BehaviorConfig.sessionMax]
 *   (réglages modifiés depuis) est tirée à nouveau et la session repart de zéro. Une pause
 *   longue restaurée ([BehaviorState.restUntil]) est respectée jusqu'à son terme.
 */
class BehaviorEngine(
    private val config: BehaviorConfig,
    private val random: Random,
    private val clock: Clock,
    initialState: BehaviorState? = null,
) {
    /** Nombre de swipes enregistrés pendant le jour local [day]. */
    var swipesToday: Int = initialState?.swipesToday ?: 0
        private set

    /** Jour local (selon `clock.zone`) auquel se rapporte [swipesToday]. */
    var day: LocalDate = initialState?.day ?: today()
        private set

    /** Swipes enregistrés dans la session en cours. */
    var sessionSwipes: Int = initialState?.sessionSwipes ?: 0
        private set

    /** Nombre de swipes visé pour la session en cours, dans [sessionMin ; sessionMax]. */
    var sessionTarget: Int = initialState?.sessionTarget
        ?.takeIf { it in config.sessionMin..config.sessionMax }
        ?: drawSessionTarget().also { sessionSwipes = 0 } // cible retirée : nouvelle session
        private set

    /** Fin de la pause longue en cours (posée à chaque [Action.Rest]) ; `null` hors pause. */
    var restUntil: Instant? = initialState?.restUntil
        private set

    /**
     * Pause restaurée à respecter : tant que l'heure n'a pas atteint [restUntil],
     * [nextAction] renvoie le [Action.Rest] restant.
     */
    private var restoredRest: Boolean = initialState?.restUntil != null

    /** État à persister. */
    val state: BehaviorState get() = BehaviorState(swipesToday, day, sessionSwipes, sessionTarget, restUntil)

    private val activeStart: LocalTime = minuteToTime(config.activeStartMinute)
    private val activeEnd: LocalTime = minuteToTime(config.activeEndMinute)

    /**
     * Décide de la prochaine action. Ordre : 1) changement de jour → remise à zéro ;
     * 2) plafond → [Action.CapReached] ; 3) hors plage horaire → [Action.OutsideHours] ;
     * 3 bis) pause restaurée non finie → [Action.Rest] du temps restant ; 4) session terminée → [Action.Rest] et nouvelle session ; 5) sinon [Action.Swipe].
     * Hors plage, la session en cours est close : la reprise en démarre une nouvelle.
     *
     * Chaque appel engage l'action renvoyée (une fin de session remet la session à zéro
     * et tire une nouvelle cible) : l'appelant doit exécuter chaque action reçue, car
     * appeler deux fois de suite saute par exemple un [Action.Rest].
     */
    fun nextAction(): Action {
        rollOverIfNewDay()
        val now = clock.now().atZone(clock.zone)
        if (swipesToday >= config.dailyCap) return Action.CapReached(millisUntilResumeAfterMidnight(now))
        if (!isInActiveHours(now.toLocalTime())) {
            // Longue interruption : sans remise à zéro, un Rest pourrait suivre de près la reprise.
            sessionSwipes = 0
            sessionTarget = drawSessionTarget()
            clearRest()
            return Action.OutsideHours(millisUntilResume(now, nextOpeningFrom(now)))
        }
        restUntil?.let { until ->
            val remaining = until.toEpochMilli() - now.toInstant().toEpochMilli()
            if (remaining <= 0) clearRest() else if (restoredRest) return Action.Rest(remaining)
        }
        if (sessionSwipes >= sessionTarget) {
            val rest = uniformLong(config.restMinMs, config.restMaxMs)
            sessionSwipes = 0
            sessionTarget = drawSessionTarget()
            restUntil = now.toInstant().plusMillis(rest)
            restoredRest = false
            return Action.Rest(rest)
        }
        return Action.Swipe(drawDelay())
    }

    /** Enregistre un swipe réellement effectué (compté sur le jour local courant). */
    fun recordSwipe() {
        rollOverIfNewDay()
        swipesToday++
        sessionSwipes++
    }

    private fun today(): LocalDate = clock.now().atZone(clock.zone).toLocalDate()

    private fun rollOverIfNewDay() {
        val today = today()
        // Seulement vers l'avant : une horloge reculée ne doit pas rouvrir le plafond.
        if (today.isAfter(day)) {
            day = today
            swipesToday = 0
            sessionSwipes = 0
            sessionTarget = drawSessionTarget()
            clearRest()
        }
    }

    private fun clearRest() {
        restUntil = null
        restoredRest = false
    }

    /** Vrai si [instant] tombe dans la plage horaire d'activité (heure locale de `clock.zone`). */
    fun isInActiveHours(instant: Instant): Boolean = isInActiveHours(instant.atZone(clock.zone).toLocalTime())

    /** Vrai si [time] (heure locale) est dans la plage [activeStart ; activeEnd[, éventuellement à cheval sur minuit. */
    internal fun isInActiveHours(time: LocalTime): Boolean =
        if (activeStart < activeEnd) {
            time >= activeStart && time < activeEnd
        } else {
            time >= activeStart || time < activeEnd
        }

    /** [from] s'il est dans la plage, sinon la prochaine ouverture de la plage. */
    private fun nextOpeningFrom(from: ZonedDateTime): ZonedDateTime =
        if (isInActiveHours(from.toLocalTime())) from else nextOccurrence(activeStart, from)

    /**
     * Première occurrence de l'heure locale [time] strictement après [after]. Une heure
     * sautée par le passage à l'heure d'été est décalée d'autant (règle de [ZonedDateTime.of]).
     */
    private fun nextOccurrence(time: LocalTime, after: ZonedDateTime): ZonedDateTime {
        var date = after.toLocalDate()
        while (true) {
            val candidate = ZonedDateTime.of(date, time, clock.zone)
            if (candidate.isAfter(after)) return candidate
            date = date.plusDays(1)
        }
    }

    /**
     * Plafond : reprise à la première ouverture de plage à partir du prochain minuit local
     * (le compteur ne repart à zéro qu'à minuit), plus le décalage aléatoire.
     */
    private fun millisUntilResumeAfterMidnight(now: ZonedDateTime): Long {
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(clock.zone)
        return millisUntilResume(now, nextOpeningFrom(nextMidnight))
    }

    /**
     * Durée réelle (jours de 23 h et 25 h compris) de [now] jusqu'à [opening] plus un
     * décalage tiré dans [resumeJitterMinMs ; resumeJitterMaxMs], réduit si besoin pour que
     * la reprise tombe au moins [CLOSING_MARGIN_MS] avant la fermeture suivante de la plage
     * (un retard d'ordonnancement ne doit pas faire sauter toute la plage).
     */
    private fun millisUntilResume(now: ZonedDateTime, opening: ZonedDateTime): Long {
        val closing = nextOccurrence(activeEnd, opening)
        val room = Duration.between(opening.toInstant(), closing.toInstant()).toMillis()
        val latest = (room - CLOSING_MARGIN_MS).coerceAtLeast(0)
        val max = minOf(config.resumeJitterMaxMs, latest)
        val min = minOf(config.resumeJitterMinMs, max)
        val jitter = uniformLong(min, max)
        return Duration.between(now.toInstant(), opening.toInstant()).toMillis() + jitter
    }

    private fun drawSessionTarget(): Int = random.nextInt(config.sessionMin, config.sessionMax + 1)

    private fun drawDelay(): Long {
        if (random.nextDouble() < config.distractionProbability) {
            return uniformLong(config.distractionMinMs, config.distractionMaxMs)
        }
        // Log-normale tronquée : on retire les valeurs hors bornes au lieu de les
        // ramener à la borne (ce qui répéterait la même valeur exacte).
        repeat(MAX_DELAY_DRAWS) {
            val raw = exp(ln(config.delayMedianMs.toDouble()) + config.delaySigma * standardNormal())
            val delay = raw.roundToLong()
            if (delay in config.delayMinMs..config.delayMaxMs) return delay
        }
        return uniformLong(config.delayMinMs, config.delayMaxMs)
    }

    /** N(0,1) par Box-Muller ; u1 ∈ ]0 ; 1] pour éviter ln(0). */
    private fun standardNormal(): Double {
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    private fun uniformLong(min: Long, max: Long): Long = random.nextLong(min, max + 1)

    companion object {
        private fun minuteToTime(minute: Int): LocalTime = LocalTime.of(minute / 60, minute % 60)

        /** Marge minimale entre une reprise et la fermeture suivante de la plage. */
        internal const val CLOSING_MARGIN_MS = 60_000L

        /** Tirages log-normaux tentés avant de retomber sur un tirage uniforme dans les bornes. */
        internal const val MAX_DELAY_DRAWS = 20
    }
}
