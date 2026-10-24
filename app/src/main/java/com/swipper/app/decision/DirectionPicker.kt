package com.swipper.app.decision

import com.swipper.app.gesture.Direction
import kotlin.random.Random

/**
 * Choisit le sens de chaque swipe (CAP-3) : [Direction.RIGHT] avec la probabilité
 * [rightRatio], sinon [Direction.LEFT].
 *
 * Kotlin pur : [random] est injecté, donc à graine fixe la suite est reproductible.
 *
 * @throws IllegalArgumentException si [rightRatio] n'est pas dans [0 ; 1] (NaN refusé).
 */
class DirectionPicker(
    private val random: Random,
    val rightRatio: Double = DEFAULT_RIGHT_RATIO,
) {
    init {
        require(rightRatio in 0.0..1.0) { "rightRatio doit être dans [0 ; 1] (reçu $rightRatio)" }
    }

    /** Tire le sens du prochain swipe ; consomme exactement un tirage de [random]. */
    fun pick(): Direction = if (random.nextDouble() < rightRatio) Direction.RIGHT else Direction.LEFT

    companion object {
        /** Ratio par défaut : 70 % de swipes à droite. */
        internal const val DEFAULT_RIGHT_RATIO = 0.70
    }
}
