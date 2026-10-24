package com.swipper.app.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessibilityStatusTest {

    // Dérivés de la vraie classe : renommer/déplacer le service casse ces tests.
    private val cls = SwipeAccessibilityService::class.java.name
    private val pkg = SwipeAccessibilityService::class.java.`package`!!.name.removeSuffix(".service")
    private val long = "$pkg/$cls"
    private val short = "$pkg/${cls.removePrefix(pkg)}"

    private fun enabled(setting: String?) = isServiceEnabled(setting, pkg, cls)

    @Test
    fun `null gives inactive`() = assertFalse(enabled(null))

    @Test
    fun `empty or blank gives inactive`() {
        assertFalse(enabled(""))
        assertFalse(enabled("   "))
        assertFalse(enabled(":"))
    }

    @Test
    fun `malformed entries give inactive without crash`() {
        assertFalse(enabled("garbage"))
        assertFalse(enabled("/"))
        assertFalse(enabled("$pkg/"))
        assertFalse(enabled("/$cls"))
        assertFalse(enabled("::$pkg::"))
    }

    @Test
    fun `other service gives inactive`() {
        assertFalse(enabled("com.other.app/com.other.app.OtherService"))
        assertFalse(enabled("com.other.app/.SwipeAccessibilityService"))
    }

    @Test
    fun `similar names are not false positives`() {
        assertFalse(enabled("$pkg/${cls}Extra"))
        assertFalse(enabled("${pkg}x/$cls"))
        assertFalse(enabled("com.evil/$cls"))
        assertFalse(enabled("x$long"))
    }

    @Test
    fun `long form gives active`() = assertTrue(enabled(long))

    @Test
    fun `short form gives active`() = assertTrue(enabled(short))

    @Test
    fun `several services separated by colon`() {
        assertTrue(enabled("com.a/.A:$long:com.b/com.b.B"))
        assertTrue(enabled("com.a/.A:$short"))
        assertTrue(enabled("$long:com.a/.A"))
        assertFalse(enabled("com.a/.A:com.b/com.b.B"))
    }

    @Test
    fun `surrounding whitespace is rejected like unflattenFromString`() =
        assertFalse(enabled("com.a/.A: $long "))

    @Test
    fun `case matters like ComponentName`() {
        assertFalse(enabled(long.uppercase()))
        assertFalse(enabled("COM.SWIPPER.APP/.service.swipeaccessibilityservice"))
    }

    @Test
    fun `parseComponent expands short form`() {
        assertEquals(pkg to cls, parseComponent(short))
        assertEquals(pkg to cls, parseComponent(long))
        assertNull(parseComponent("nope"))
    }
}
