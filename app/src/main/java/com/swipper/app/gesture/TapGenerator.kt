package com.swipper.app.gesture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Position à l'écran en fractions de la largeur ([x]) et de la hauteur ([y]), indépendante
 * de la résolution (position calibrée d'ouverture de profil, story 7). Chaque coordonnée
 * est dans [0 ; 1] ; NaN est refusé.
 */
data class FractionPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f) { "x doit être une fraction dans [0 ; 1] (reçu $x)" }
        require(y in 0f..1f) { "y doit être une fraction dans [0 ; 1] (reçu $y)" }
    }

    companion object {
        /**
         * Fractions d'écran du toucher en ([xPx] ; [yPx]) pixels sur [screen], ramenées dans
         * [0 ; 1]. Ex. (540 ; 1900) sur 1080×2400 → (0,50 ; 0,79).
         */
        fun of(xPx: Float, yPx: Float, screen: ScreenSize): FractionPoint = FractionPoint(
            (xPx / screen.widthPx).let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) },
            (yPx / screen.heightPx).let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) },
        )
    }
}

/**
 * Génère un tap humain autour d'un point calibré (story 7) : un plan à un seul segment
 * court (envoyé comme un swipe, par `dispatchGesture`), d'une durée tirée dans
 * [[MIN_DURATION_MS] ; [MAX_DURATION_MS]], posé à un décalage aléatoire d'au plus
 * [MAX_OFFSET_FRACTION] de la largeur (sur chaque axe) autour du point, avec un
 * micro-glissement de [MIN_SLIDE_PX]–[MAX_SLIDE_PX] px. Tous les points restent dans la
 * boîte ±[MAX_OFFSET_FRACTION] × largeur et dans l'écran.
 *
 * Kotlin pur : [random] est injecté.
 */
class TapGenerator(private val random: Random) {

    /** Tap autour de [target] (fractions de [screen]). */
    fun plan(screen: ScreenSize, target: FractionPoint): SwipePlan {
        val w = screen.widthPx.toDouble()
        val h = screen.heightPx.toDouble()
        val maxX = max(0.0, w - 1)
        val maxY = max(0.0, h - 1)
        val cx = target.x * w
        val cy = target.y * h
        val box = MAX_OFFSET_FRACTION * w
        // Départ dans la boîte réduite du glissement maximal : l'arrivée y reste toujours.
        val inner = max(0.0, box - MAX_SLIDE_PX)
        val sx = (cx + uniform(-inner, inner)).coerceIn(0.0, maxX)
        val sy = (cy + uniform(-inner, inner)).coerceIn(0.0, maxY)
        val slide = uniform(MIN_SLIDE_PX, MAX_SLIDE_PX)
        val angle = uniform(0.0, 2 * PI)
        val ex = (sx + slide * cos(angle)).coerceIn(cx - box, cx + box).coerceIn(0.0, maxX)
        val ey = (sy + slide * sin(angle)).coerceIn(cy - box, cy + box).coerceIn(0.0, maxY)
        val duration = random.nextLong(MIN_DURATION_MS, MAX_DURATION_MS + 1)
        val segment = Segment(
            listOf(Point(sx.toFloat(), sy.toFloat()), Point(ex.toFloat(), ey.toFloat())),
            duration,
        )
        return SwipePlan(listOf(segment), hesitant = false)
    }

    private fun uniform(from: Double, until: Double): Double =
        if (until <= from) from else from + (until - from) * random.nextDouble()

    companion object {
        const val MIN_DURATION_MS = 60L
        const val MAX_DURATION_MS = 140L

        /** Décalage maximal autour du point calibré, en fraction de la largeur (sur chaque axe). */
        const val MAX_OFFSET_FRACTION = 0.015

        const val MIN_SLIDE_PX = 1.0
        const val MAX_SLIDE_PX = 4.0
    }
}
