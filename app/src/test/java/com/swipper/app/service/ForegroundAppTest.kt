package com.swipper.app.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ForegroundAppTest {

    private val own = "com.swipper.app"
    private val ime = "com.google.android.inputmethod.latin"
    private val transient = setOf(SYSTEM_UI_PACKAGE, ime)

    private fun from(pkg: String?, cls: String? = null, type: Int = WINDOW_STATE_CHANGED) =
        foregroundFrom(type, pkg, cls, own, transient)

    @Test
    fun `app ordinaire retenue`() {
        assertEquals("com.tinder", from("com.tinder", "com.tinder.MainActivity"))
        assertEquals("com.android.chrome", from("com.android.chrome"))
    }

    @Test
    fun `autre type d'evenement ignore`() {
        assertNull(from("com.tinder", type = 0x00000800))
    }

    @Test
    fun `paquet absent ou vide ignore`() {
        assertNull(from(null))
        assertNull(from(""))
    }

    @Test
    fun `interface systeme ignoree (volet, verrouillage, dialogues)`() {
        assertNull(from(SYSTEM_UI_PACKAGE, "android.widget.FrameLayout"))
    }

    @Test
    fun `clavier ignore`() {
        assertNull(from(ime, "android.inputmethodservice.SoftInputWindow"))
    }

    @Test
    fun `activite de Swipper retenue, autres fenetres de Swipper ignorees`() {
        assertEquals(own, from(own, "com.swipper.app.ui.MainActivity"))
        assertNull(from(own, "android.widget.LinearLayout"))
        assertNull(from(own, null))
    }
}
