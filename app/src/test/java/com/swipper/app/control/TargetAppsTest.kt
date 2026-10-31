package com.swipper.app.control

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TargetAppsTest {

    @Test
    fun `ouverture de profil - Tinder seulement`() {
        assertTrue(TargetApps.supportsProfile(TargetApps.TINDER))
        // Sur Bumble, le profil se voit en faisant défiler la carte : pas de tap.
        assertFalse(TargetApps.supportsProfile(TargetApps.BUMBLE))
        assertFalse(TargetApps.supportsProfile("com.example"))
        assertFalse(TargetApps.supportsProfile(null))
    }
}
