package com.swipper.app.gesture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TapGeneratorTest {

    private val screen = ScreenSize(1080, 2400)
    private val target = FractionPoint(0.50f, 0.80f)

    @Test
    fun `tap humain - dans ±1,5 % de largeur, 60–140 ms, un seul segment, jamais deux identiques sur 100`() {
        val generator = TapGenerator(Random(42))
        val box = 0.015 * screen.widthPx
        val cx = 0.50 * screen.widthPx
        val cy = 0.80 * screen.heightPx
        val plans = List(100) { generator.plan(screen, target) }
        for (plan in plans) {
            assertEquals(1, plan.segments.size)
            val segment = plan.segments.single()
            assertTrue(segment.durationMs in 60L..140L, "durée ${segment.durationMs}")
            for (p in segment.points) {
                assertTrue(abs(p.x - cx) <= box + 1e-3, "x hors boîte : ${p.x}")
                assertTrue(abs(p.y - cy) <= box + 1e-3, "y hors boîte : ${p.y}")
            }
            val slide = hypot((plan.end.x - plan.start.x).toDouble(), (plan.end.y - plan.start.y).toDouble())
            assertTrue(slide > 0.0 && slide <= 4.0 + 1e-3, "glissement $slide px")
        }
        assertEquals(100, plans.toSet().size, "taps identiques")
        assertTrue(plans.map { it.totalDurationMs }.toSet().size > 10, "durées trop peu variées")
    }

    @Test
    fun `point calibré au bord - tap ramené dans l'écran`() {
        val generator = TapGenerator(Random(1))
        repeat(200) {
            for (corner in listOf(FractionPoint(0f, 0f), FractionPoint(1f, 1f))) {
                val plan = generator.plan(screen, corner)
                for (p in plan.segments.single().points) {
                    assertTrue(p.x in 0f..(screen.widthPx - 1).toFloat() && p.y in 0f..(screen.heightPx - 1).toFloat(), "$p")
                }
            }
        }
    }

    @Test
    fun `calibration - toucher en (540, 1900) sur 1080x2400 donne 0,50 et 0,79`() {
        val p = FractionPoint.of(540f, 1900f, screen)
        assertEquals(0.50f, p.x, 0.005f)
        assertEquals(0.79f, p.y, 0.005f)
        // Hors écran (barre système, arrondis) : ramené dans [0 ; 1].
        assertEquals(FractionPoint(1f, 0f), FractionPoint.of(2000f, -5f, screen))
    }

    @Test
    fun `fraction hors bornes refusée`() {
        assertThrows<IllegalArgumentException> { FractionPoint(1.2f, 0.5f) }
        assertThrows<IllegalArgumentException> { FractionPoint(0.5f, Float.NaN) }
    }
}
