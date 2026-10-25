package com.swipper.app.control

import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutomationNotificationFormatTest {

    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    @Test
    fun `heure d'ete a Paris - UTC+2`() {
        assertEquals("09:17", formatResumeTime(Instant.parse("2026-07-01T07:17:00Z"), paris))
    }

    @Test
    fun `heure d'hiver a Paris - UTC+1`() {
        assertEquals("09:17", formatResumeTime(Instant.parse("2026-01-15T08:17:00Z"), paris))
    }

    @Test
    fun `apres-midi au format 24 h`() {
        assertEquals("14:30", formatResumeTime(Instant.parse("2026-07-01T12:30:00Z"), paris))
    }

    @Test
    fun `echeance inconnue`() {
        assertEquals("--:--", formatResumeTime(null, paris))
    }
}
