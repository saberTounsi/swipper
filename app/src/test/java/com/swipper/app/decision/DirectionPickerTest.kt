package com.swipper.app.decision

import com.swipper.app.gesture.Direction
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DirectionPickerTest {

    @Test
    fun `ratio 0,70 sur 1 000 décisions donne 65 à 75 % de RIGHT`() {
        val picker = DirectionPicker(Random(42), 0.70)
        val rights = List(1000) { picker.pick() }.count { it == Direction.RIGHT }
        assertTrue(rights in 650..750, "RIGHT = $rights / 1000")
    }

    @Test
    fun `ratio par défaut est 0,70`() {
        assertEquals(0.70, DirectionPicker(Random(1)).rightRatio)
    }

    @Test
    fun `ratios extrêmes 0 donne toujours LEFT et 1 toujours RIGHT`() {
        val left = DirectionPicker(Random(7), 0.0)
        assertTrue(List(1000) { left.pick() }.all { it == Direction.LEFT })
        val right = DirectionPicker(Random(7), 1.0)
        assertTrue(List(1000) { right.pick() }.all { it == Direction.RIGHT })
    }

    @Test
    fun `même graine donne la même suite`() {
        val a = DirectionPicker(Random(3))
        val b = DirectionPicker(Random(3))
        assertEquals(List(200) { a.pick() }, List(200) { b.pick() })
    }

    @Test
    fun `ratio hors de 0 à 1 ou NaN est refusé`() {
        for (r in listOf(-0.01, 1.01, Double.NaN)) {
            assertThrows<IllegalArgumentException> { DirectionPicker(Random(0), r) }
        }
    }
}
