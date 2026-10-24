package com.swipper.app.behavior

import java.time.Duration
import java.time.LocalDate
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

    /** Plafond quotidien atteint : attendre [waitMs], soit jusqu'au prochain minuit local. */
    data class CapReached(val waitMs: Long) : Action
}

/**
 * État minimal du moteur, à persister (story 6) : nombre de swipes du jour local [day].
 *
 * @throws IllegalArgumentException si [swipesToday] < 0.
 */
data class BehaviorState(val swipesToday: Int, val day: LocalDate) {
    init {
        require(swipesToday >= 0) { "swipesToday doit être ≥ 0 (reçu $swipesToday)" }
    }
}

/**
 * Décide du rythme des swipes (CAP-4) : délai irrégulier, sessions bornées séparées
 * de pauses longues, plafond quotidien jusqu'à minuit local.
 *
 * Kotlin pur : [random] et [clock] sont injectés. Les swipes ne sont comptés que par
 * [recordSwipe], que l'appelant invoque quand un swipe a réellement eu lieu ;
 * [nextAction] ne modifie pas le compteur de swipes.
 *
 * @param initialState état restauré ; `null` = aucun swipe aujourd'hui. Un état d'un
 *   autre jour est remis à zéro au premier appel.
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

    /** État à persister. */
    val state: BehaviorState get() = BehaviorState(swipesToday, day)

    /** Swipes enregistrés dans la session en cours. */
    internal var sessionSwipes: Int = 0
        private set

    /** Nombre de swipes visé pour la session en cours, dans [sessionMin ; sessionMax]. */
    internal var sessionTarget: Int = drawSessionTarget()
        private set

    /**
     * Décide de la prochaine action. Ordre : 1) changement de jour → remise à zéro ;
     * 2) plafond → [Action.CapReached] ; 3) session terminée → [Action.Rest] et nouvelle
     * session ; 4) sinon [Action.Swipe].
     *
     * Chaque appel engage l'action renvoyée (une fin de session remet la session à zéro
     * et tire une nouvelle cible) : l'appelant doit exécuter chaque action reçue, car
     * appeler deux fois de suite saute par exemple un [Action.Rest].
     */
    fun nextAction(): Action {
        rollOverIfNewDay()
        if (swipesToday >= config.dailyCap) return Action.CapReached(millisUntilNextMidnight())
        if (sessionSwipes >= sessionTarget) {
            val rest = uniformLong(config.restMinMs, config.restMaxMs)
            sessionSwipes = 0
            sessionTarget = drawSessionTarget()
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
        }
    }

    /** Durée réelle jusqu'au prochain minuit local (jours de 23 h et 25 h compris). */
    private fun millisUntilNextMidnight(): Long {
        val now = clock.now()
        val nextMidnight = now.atZone(clock.zone).toLocalDate().plusDays(1).atStartOfDay(clock.zone)
        return Duration.between(now, nextMidnight.toInstant()).toMillis()
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
        /** Tirages log-normaux tentés avant de retomber sur un tirage uniforme dans les bornes. */
        internal const val MAX_DELAY_DRAWS = 20
    }
}
