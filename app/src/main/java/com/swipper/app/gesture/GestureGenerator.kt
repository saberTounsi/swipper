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
 * profil de « lancer » (départ lent, pic de vitesse, levée du doigt encore rapide),
 * paramètres tirés à chaque geste.
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
     * @throws IllegalArgumentException si la zone a xMin > [RIGHT_START_MAX] ou
     *   xMax < [LEFT_START_MIN] : un des deux sens ne pourrait pas garantir le
     *   trajet minimal de [MIN_TRAVEL].
     */
    fun plan(screen: ScreenSize, zone: SwipeZone, direction: Direction): SwipePlan {
        // Tolérance : les bornes Float (0,62f = 0,6200000048…) doivent passer à la limite exacte.
        require(zone.xMin <= RIGHT_START_MAX + LIMIT_EPS) {
            "xMin de la zone (${zone.xMin}) doit être ≤ $RIGHT_START_MAX pour garantir le trajet vers la droite"
        }
        require(zone.xMax >= LEFT_START_MIN - LIMIT_EPS) {
            "xMax de la zone (${zone.xMax}) doit être ≥ $LEFT_START_MIN pour garantir le trajet vers la gauche"
        }
        val w = screen.widthPx.toDouble()
        val h = screen.heightPx.toDouble()
        val maxX = max(0.0, w - 1)
        val maxY = max(0.0, h - 1)

        // 1. Départ : dans la zone ∩ [0 ; RIGHT_START_MAX] (RIGHT) ou ∩ [LEFT_START_MIN ; 1]
        // (LEFT), pour garantir le trajet ; biais vers la droite (u^0,8 a une moyenne > 0,5).
        val (xLo, xHi) = when (direction) {
            Direction.RIGHT -> min(zone.xMin.toDouble(), RIGHT_START_MAX) to min(zone.xMax.toDouble(), RIGHT_START_MAX)
            Direction.LEFT -> max(zone.xMin.toDouble(), LEFT_START_MIN) to max(zone.xMax.toDouble(), LEFT_START_MIN)
        }
        val sxFrac = xLo + (xHi - xLo) * random.nextDouble().pow(RIGHT_BIAS_EXPONENT)
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

        // 3. Tracé : premier contrôle au 1/3 de la corde mais à la hauteur du départ
        // (tangente initiale horizontale : le doigt est pris pour un swipe de carte, pas
        // pour un défilement vertical, ex. Bumble) ; second au 2/3, décalé de côté.
        val dx = ex - sx
        val dy = ey - sy
        val chord = hypot(dx, dy)
        val nx = -dy / chord
        val ny = dx / chord
        val c1x = sx + dx / 3
        val c2x0 = sx + 2 * dx / 3
        val c2y0 = sy + 2 * dy / 3
        var k2 = uniform(CONTROL_OFFSET_MIN, CONTROL_OFFSET_MAX) * w
        var side = if (random.nextBoolean()) 1.0 else -1.0
        // Les contrôles restent dans l'écran, donc toute la courbe aussi (enveloppe convexe) :
        // c1 est entre sx et ex, à la hauteur sy ; seul c2 a besoin d'être borné.
        fun cap(s: Double) = maxOffset(c2x0, c2y0, s * nx, s * ny, maxX, maxY)
        val capSide = cap(side)
        val capOther = cap(-side)
        if (k2 > capSide) {
            if (k2 <= capOther || capOther > capSide) side = -side
            k2 = min(k2, max(capSide, capOther))
        }
        fun curveFor(s: Double, k: Double) = Bezier(
            sx, sy,
            c1x, sy,
            c2x0 + s * k * nx, c2y0 + s * k * ny,
            ex, ey,
        )
        var curve = curveFor(side, k2)
        // Cas rare (petit écran, dérive et décalage max du même côté) : le début du tracé
        // monte trop ; la courbure opposée le ramène vers l'horizontale.
        if (curve.startSlope(sx, sy) > START_SLOPE_GUARD) {
            val flipped = curveFor(-side, min(k2, cap(-side)))
            if (flipped.startSlope(sx, sy) < curve.startSlope(sx, sy)) curve = flipped
        }

        // 4. Vitesse : durées ~égales, frontières à la fraction de longueur E(τᵢ).
        val n = random.nextInt(MIN_SEGMENTS, MAX_SEGMENTS + 1)
        val total = random.nextLong(MIN_TOTAL_MS, MAX_TOTAL_MS + 1)
        val hesitant = random.nextDouble() < HESITANT_PROBABILITY
        val peak = uniform(PEAK_MIN, PEAK_MAX)
        val release = uniform(RELEASE_MIN, RELEASE_MAX)
        val durations = splitDuration(total, n)
        val fractions = DoubleArray(n + 1)
        var cumulative = 0L
        for (i in 1 until n) {
            cumulative += durations[i - 1]
            fractions[i] = ease(cumulative.toDouble() / total, hesitant, peak, release)
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
                // ≤ gap/4 : au départ (points serrés), le bruit ne rend pas le geste vertical.
                val amp = min(uniform(JITTER_MIN_PX, JITTER_MAX_PX), gap / 4)
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

        /**
         * Pente maximale |Δy| / |Δx| depuis ([x0] ; [y0]) des points de la courbe à moins de
         * [START_CHECK_PX] du départ.
         */
        fun startSlope(x0: Double, y0: Double): Double {
            var worst = 0.0
            for (i in 1..ARC_SAMPLES) {
                val (x, y) = pointAt(i.toDouble() / ARC_SAMPLES)
                if (hypot(x - x0, y - y0) > START_CHECK_PX) break
                val ax = abs(x - x0)
                worst = max(worst, if (ax > 0.0) abs(y - y0) / ax else 0.0)
            }
            return worst
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
        const val MAX_TOTAL_MS = 450L
        const val MIN_SEGMENT_MS = 16L
        const val HESITANT_PROBABILITY = 0.10

        /** Trajet horizontal minimal, en fraction de la largeur. */
        const val MIN_TRAVEL = 0.35
        const val RIGHT_BAND_MIN = 0.80
        const val RIGHT_BAND_MAX = 0.97
        const val LEFT_BAND_MIN = 0.03
        const val LEFT_BAND_MAX = 0.20

        /**
         * Départ maximal d'un swipe RIGHT et minimal d'un swipe LEFT (fractions de la
         * largeur) : dérivés des bandes pour garantir le trajet de [MIN_TRAVEL].
         */
        const val RIGHT_START_MAX = RIGHT_BAND_MAX - MIN_TRAVEL
        const val LEFT_START_MIN = LEFT_BAND_MIN + MIN_TRAVEL

        /** Tolérance d'arrondi Float sur les limites de la zone. */
        const val LIMIT_EPS = 1e-6

        /** Dérive verticale, en fraction de la hauteur. */
        const val DRIFT_MIN = 0.04
        const val DRIFT_MAX = 0.12

        /** Distance au départ sur laquelle le tracé doit rester à dominante horizontale (px). */
        const val START_CHECK_PX = 80.0

        /**
         * Pente de départ (|Δy| / |Δx|, courbe sans bruit) au-delà de laquelle la courbure est
         * inversée ; marge sous 0,6 pour le tremblement.
         */
        const val START_SLOPE_GUARD = 0.45

        /** Décalage perpendiculaire du second point de contrôle, en fraction de la largeur. */
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

        /** Position du pic de vitesse, en fraction du temps. */
        const val PEAK_MIN = 0.55
        const val PEAK_MAX = 0.75

        /** Vitesse à la levée du doigt, en fraction de la vitesse du pic. */
        const val RELEASE_MIN = 0.5
        const val RELEASE_MAX = 0.8

        /**
         * Fraction de longueur parcourue au temps [u] ∈ [0 ; 1] avec le profil « lancer » :
         * vitesse v(s) = sin(π/2 · s/[peak]) jusqu'au pic, puis baisse linéaire jusqu'à
         * [release] à la levée. E(u) = ∫₀ᵘ v / ∫₀¹ v (intégrale analytique), monotone,
         * E(0) = 0, E(1) = 1. Un geste hésitant applique u^1,6 avant (départ plus lent).
         */
        fun ease(u: Double, hesitant: Boolean, peak: Double, release: Double): Double {
            val x = (if (hesitant) u.pow(HESITANT_EXPONENT) else u).coerceIn(0.0, 1.0)
            val rise = 2 * peak / PI
            val total = rise + (1 - peak) * (1 + release) / 2
            val area = if (x <= peak) {
                rise * (1 - cos(PI / 2 * x / peak))
            } else {
                val d = x - peak
                rise + d - (1 - release) * d * d / (2 * (1 - peak))
            }
            return area / total
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
