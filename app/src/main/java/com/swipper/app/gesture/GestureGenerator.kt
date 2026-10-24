package com.swipper.app.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Génère des swipes qui ressemblent à un doigt (CAP-2) : tracé en Bézier cubique,
 * accélération puis décélération, paramètres tirés à chaque geste.
 *
 * Kotlin pur : [random] et les dimensions sont injectés, donc à graine fixe le
 * résultat est reproductible. Ne décide pas du sens (story 4) et n'exécute rien
 * (story 3).
 */
class GestureGenerator(private val random: Random) {

    /**
     * Point d'observation pour les tests : à `false`, le tremblement est tiré (même
     * consommation de [random]) mais pas appliqué, ce qui donne la courbe sans bruit.
     */
    internal var jitterEnabled: Boolean = true

    /**
     * Produit un [SwipePlan] dans le sens [direction], avec un départ tiré dans
     * [zone] (fractions de [screen]).
     *
     * @throws IllegalArgumentException si la zone a xMin < [ZONE_X_MIN_LIMIT] ou
     *   xMax > [ZONE_X_MAX_LIMIT] : le trajet minimal de [MIN_TRAVEL] ne serait
     *   pas garanti.
     */
    fun plan(screen: ScreenSize, zone: SwipeZone, direction: Direction): SwipePlan {
        require(zone.xMin >= ZONE_X_MIN_LIMIT) {
            "xMin de la zone (${zone.xMin}) doit être ≥ $ZONE_X_MIN_LIMIT pour garantir le trajet"
        }
        require(zone.xMax <= ZONE_X_MAX_LIMIT) {
            "xMax de la zone (${zone.xMax}) doit être ≤ $ZONE_X_MAX_LIMIT pour garantir le trajet"
        }
        val w = screen.widthPx.toDouble()
        val h = screen.heightPx.toDouble()
        val maxX = max(0.0, w - 1)
        val maxY = max(0.0, h - 1)

        // 1. Départ : biais vers la droite (u^0,8 a une moyenne > 0,5).
        val sxFrac = zone.xMin + (zone.xMax - zone.xMin) * random.nextDouble().pow(RIGHT_BIAS_EXPONENT)
        val syFrac = zone.yMin + (zone.yMax - zone.yMin) * random.nextDouble()
        val sx = (sxFrac * w).coerceIn(0.0, maxX)
        val sy = (syFrac * h).coerceIn(0.0, maxY)

        // 2. Arrivée : bande près du bord du sens choisi ; trajet ≥ MIN_TRAVEL.
        val exFrac = when (direction) {
            Direction.RIGHT -> uniform(max(RIGHT_BAND_MIN, sxFrac + MIN_TRAVEL), RIGHT_BAND_MAX)
            Direction.LEFT -> uniform(LEFT_BAND_MIN, min(LEFT_BAND_MAX, sxFrac - MIN_TRAVEL))
        }
        val ex = (exFrac * w).coerceIn(0.0, maxX)
        // Dérive verticale : sens tiré au hasard, inversé s'il sortirait de l'écran
        // (l'autre sens tient toujours, la dérive max étant < moitié de la hauteur).
        val drift = uniform(DRIFT_MIN, DRIFT_MAX) * h
        var driftSign = if (random.nextBoolean()) 1.0 else -1.0
        if (sy + driftSign * drift !in 0.0..maxY) driftSign = -driftSign
        val ey = (sy + driftSign * drift).coerceIn(0.0, maxY)

        // 3. Tracé : contrôles au 1/3 et 2/3 de la corde, décalés du même côté.
        val dx = ex - sx
        val dy = ey - sy
        val chord = hypot(dx, dy)
        val nx = -dy / chord
        val ny = dx / chord
        val c1x0 = sx + dx / 3
        val c1y0 = sy + dy / 3
        val c2x0 = sx + 2 * dx / 3
        val c2y0 = sy + 2 * dy / 3
        var k1 = uniform(CONTROL_OFFSET_MIN, CONTROL_OFFSET_MAX) * w
        var k2 = uniform(CONTROL_OFFSET_MIN, CONTROL_OFFSET_MAX) * w
        var side = if (random.nextBoolean()) 1.0 else -1.0
        // Les contrôles restent dans l'écran, donc toute la courbe aussi (enveloppe convexe).
        fun cap(s: Double) = min(
            maxOffset(c1x0, c1y0, s * nx, s * ny, maxX, maxY),
            maxOffset(c2x0, c2y0, s * nx, s * ny, maxX, maxY),
        )
        val capSide = cap(side)
        val capOther = cap(-side)
        if (max(k1, k2) > capSide) {
            if (max(k1, k2) <= capOther || capOther > capSide) side = -side
            val c = max(capSide, capOther)
            k1 = min(k1, c)
            k2 = min(k2, c)
        }
        val curve = Bezier(
            sx, sy,
            c1x0 + side * k1 * nx, c1y0 + side * k1 * ny,
            c2x0 + side * k2 * nx, c2y0 + side * k2 * ny,
            ex, ey,
        )

        // 4. Vitesse : durées ~égales, frontières à la fraction de longueur E(τᵢ).
        val n = random.nextInt(MIN_SEGMENTS, MAX_SEGMENTS + 1)
        val total = random.nextLong(MIN_TOTAL_MS, MAX_TOTAL_MS + 1)
        val hesitant = random.nextDouble() < HESITANT_PROBABILITY
        val durations = splitDuration(total, n)
        val fractions = DoubleArray(n + 1)
        var cumulative = 0L
        for (i in 1 until n) {
            cumulative += durations[i - 1]
            fractions[i] = ease(cumulative.toDouble() / total, hesitant)
        }
        fractions[n] = 1.0

        val boundaries = Array(n + 1) { i ->
            when (i) {
                0 -> Point(sx.toFloat(), sy.toFloat())
                n -> Point(ex.toFloat(), ey.toFloat())
                else -> curve.pointAt(curve.tAtFraction(fractions[i])).let { (x, y) ->
                    Point(x.toFloat(), y.toFloat())
                }
            }
        }

        // 5. Points intermédiaires avec tremblement perpendiculaire à la corde.
        val segments = List(n) { i ->
            val a = fractions[i]
            val b = fractions[i + 1]
            val m = random.nextInt(MIN_INTERMEDIATE_POINTS, MAX_INTERMEDIATE_POINTS + 1)
            val raw = List(m) { j ->
                curve.pointAt(curve.tAtFraction(a + (b - a) * (j + 1) / (m + 1)))
            }
            val ref = listOf(boundaries[i].x.toDouble() to boundaries[i].y.toDouble()) + raw +
                listOf(boundaries[i + 1].x.toDouble() to boundaries[i + 1].y.toDouble())
            val inner = raw.mapIndexed { j, (px, py) ->
                val prev = ref[j]
                val next = ref[j + 2]
                val gap = min(hypot(px - prev.first, py - prev.second), hypot(next.first - px, next.second - py))
                val amp = min(uniform(JITTER_MIN_PX, JITTER_MAX_PX), gap / 2)
                val sign = if (random.nextBoolean()) 1.0 else -1.0
                val signed = if (jitterEnabled) sign * amp else 0.0
                val qx = px + signed * nx
                val qy = py + signed * ny
                if (qx in 0.0..maxX && qy in 0.0..maxY) Point(qx.toFloat(), qy.toFloat())
                else Point(px.coerceIn(0.0, maxX).toFloat(), py.coerceIn(0.0, maxY).toFloat())
            }
            Segment(listOf(boundaries[i]) + inner + boundaries[i + 1], durations[i])
        }
        return SwipePlan(segments, hesitant)
    }

    /**
     * Découpe [total] ms en [n] durées proches (poids ±[DURATION_JITTER]), chacune
     * ≥ [MIN_SEGMENT_MS], dont la somme vaut exactement [total].
     */
    internal fun splitDuration(total: Long, n: Int): List<Long> {
        require(n > 0) { "n doit être > 0 (reçu $n)" }
        require(total >= MIN_SEGMENT_MS * n) {
            "Durée totale $total ms insuffisante pour $n segments de ≥ $MIN_SEGMENT_MS ms"
        }
        val weights = DoubleArray(n) { 1.0 + uniform(-DURATION_JITTER, DURATION_JITTER) }
        val sum = weights.sum()
        val d = LongArray(n) { i -> max(MIN_SEGMENT_MS, Math.round(total * weights[i] / sum)) }
        var diff = total - d.sum()
        while (diff > 0) {
            d[d.indices.minBy { d[it] }]++
            diff--
        }
        while (diff < 0) {
            d[d.indices.maxBy { d[it] }]--
            diff++
        }
        return d.toList()
    }

    private fun uniform(from: Double, until: Double): Double =
        if (until <= from) from else from + (until - from) * random.nextDouble()

    /** Cubique de Bézier avec table de longueur d'arc pour un paramétrage à vitesse uniforme. */
    private class Bezier(
        val x0: Double, val y0: Double,
        val x1: Double, val y1: Double,
        val x2: Double, val y2: Double,
        val x3: Double, val y3: Double,
    ) {
        private val lengths = DoubleArray(ARC_SAMPLES + 1)

        init {
            var (px, py) = pointAt(0.0)
            for (i in 1..ARC_SAMPLES) {
                val (qx, qy) = pointAt(i.toDouble() / ARC_SAMPLES)
                lengths[i] = lengths[i - 1] + hypot(qx - px, qy - py)
                px = qx
                py = qy
            }
        }

        fun pointAt(t: Double): Pair<Double, Double> {
            val u = 1 - t
            val a = u * u * u
            val b = 3 * u * u * t
            val c = 3 * u * t * t
            val d = t * t * t
            return (a * x0 + b * x1 + c * x2 + d * x3) to (a * y0 + b * y1 + c * y2 + d * y3)
        }

        /** Paramètre t correspondant à la fraction [f] de la longueur (interpolation linéaire). */
        fun tAtFraction(f: Double): Double {
            val total = lengths[ARC_SAMPLES]
            if (total <= 0.0) return f.coerceIn(0.0, 1.0)
            val target = f.coerceIn(0.0, 1.0) * total
            var lo = 0
            var hi = ARC_SAMPLES
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (lengths[mid] < target) lo = mid else hi = mid
            }
            val span = lengths[hi] - lengths[lo]
            val local = if (span <= 0.0) 0.0 else (target - lengths[lo]) / span
            return (lo + local.coerceIn(0.0, 1.0)) / ARC_SAMPLES
        }
    }

    internal companion object {
        const val MIN_SEGMENTS = 6
        const val MAX_SEGMENTS = 10
        const val MIN_TOTAL_MS = 180L
        const val MAX_TOTAL_MS = 600L
        const val MIN_SEGMENT_MS = 16L
        const val HESITANT_PROBABILITY = 0.10

        /** Bornes de la zone de départ qui garantissent le trajet minimal. */
        const val ZONE_X_MIN_LIMIT = 0.18f
        const val ZONE_X_MAX_LIMIT = 0.82f

        /** Trajet horizontal minimal, en fraction de la largeur. */
        const val MIN_TRAVEL = 0.15
        const val RIGHT_BAND_MIN = 0.80
        const val RIGHT_BAND_MAX = 0.97
        const val LEFT_BAND_MIN = 0.03
        const val LEFT_BAND_MAX = 0.20

        /** Dérive verticale, en fraction de la hauteur. */
        const val DRIFT_MIN = 0.04
        const val DRIFT_MAX = 0.12

        /** Décalage perpendiculaire des points de contrôle, en fraction de la largeur. */
        const val CONTROL_OFFSET_MIN = 0.02
        const val CONTROL_OFFSET_MAX = 0.08

        const val MIN_INTERMEDIATE_POINTS = 2
        const val MAX_INTERMEDIATE_POINTS = 4
        const val JITTER_MIN_PX = 1.0
        const val JITTER_MAX_PX = 3.0
        const val DURATION_JITTER = 0.15
        const val RIGHT_BIAS_EXPONENT = 0.8
        const val HESITANT_EXPONENT = 1.6
        const val ARC_SAMPLES = 64

        /** Easing sinusoïdal (1 − cos πu) / 2, précédé de u^1,6 pour un geste hésitant. */
        fun ease(u: Double, hesitant: Boolean): Double {
            val v = if (hesitant) u.pow(HESITANT_EXPONENT) else u
            return (1 - cos(PI * v)) / 2
        }

        /**
         * Plus grand k ≥ 0 tel que (cx, cy) + k·(nx, ny) reste dans [0 ; maxX] × [0 ; maxY].
         */
        fun maxOffset(cx: Double, cy: Double, nx: Double, ny: Double, maxX: Double, maxY: Double): Double {
            fun axis(c: Double, d: Double, limit: Double) = when {
                abs(d) < 1e-12 -> Double.POSITIVE_INFINITY
                d > 0 -> (limit - c) / d
                else -> c / -d
            }
            return max(0.0, min(axis(cx, nx, maxX), axis(cy, ny, maxY)))
        }
    }
}
