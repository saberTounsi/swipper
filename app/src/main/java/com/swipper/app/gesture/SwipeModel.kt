package com.swipper.app.gesture

/** Point à l'écran, en pixels. */
data class Point(val x: Float, val y: Float)

/**
 * Portion de geste parcourue à vitesse constante en [durationMs] millisecondes
 * (une `StrokeDescription` en story 3). Contient au moins deux points.
 */
data class Segment(val points: List<Point>, val durationMs: Long) {
    init {
        require(points.size >= 2) { "Un segment contient au moins 2 points (reçu ${points.size})" }
        require(durationMs > 0) { "La durée d'un segment doit être > 0 ms (reçu $durationMs)" }
    }

    val start: Point get() = points.first()
    val end: Point get() = points.last()
}

/**
 * Geste complet : segments enchaînés par `continueStroke`. Le premier point de
 * chaque segment est exactement le dernier point du segment précédent.
 * [hesitant] indique un geste au départ plus lent.
 */
data class SwipePlan(val segments: List<Segment>, val hesitant: Boolean) {
    init {
        require(segments.isNotEmpty()) { "Un plan contient au moins un segment" }
        for (i in 1 until segments.size) {
            require(segments[i].start == segments[i - 1].end) {
                "Segment $i discontinu : ${segments[i].start} ≠ ${segments[i - 1].end}"
            }
        }
    }

    val totalDurationMs: Long get() = segments.sumOf { it.durationMs }
    val start: Point get() = segments.first().start
    val end: Point get() = segments.last().end
}

/** Sens du swipe. */
enum class Direction { LEFT, RIGHT }

/** Dimensions de l'écran en pixels. */
data class ScreenSize(val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0) { "La largeur d'écran doit être > 0 (reçu $widthPx)" }
        require(heightPx > 0) { "La hauteur d'écran doit être > 0 (reçu $heightPx)" }
    }
}

/**
 * Zone de départ des swipes, en fractions de l'écran (indépendante de la
 * résolution). Chaque borne est dans [0 ; 1] et min ≤ max ; NaN est refusé.
 */
data class SwipeZone(val xMin: Float, val xMax: Float, val yMin: Float, val yMax: Float) {
    init {
        for ((name, v) in listOf("xMin" to xMin, "xMax" to xMax, "yMin" to yMin, "yMax" to yMax)) {
            require(v in 0f..1f) { "$name doit être une fraction dans [0 ; 1] (reçu $v)" }
        }
        require(xMin <= xMax) { "xMin ($xMin) doit être ≤ xMax ($xMax)" }
        require(yMin <= yMax) { "yMin ($yMin) doit être ≤ yMax ($yMax)" }
    }

    companion object {
        /** Zone par défaut : x ∈ [0,35 ; 0,65], y ∈ [0,45 ; 0,65]. */
        val DEFAULT = SwipeZone(xMin = 0.35f, xMax = 0.65f, yMin = 0.45f, yMax = 0.65f)
    }
}
