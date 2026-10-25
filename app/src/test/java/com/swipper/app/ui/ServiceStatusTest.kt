package com.swipper.app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ServiceStatusTest {

    @ParameterizedTest(name = "réglage={0}, connecté={1} → {2}")
    @CsvSource(
        "false, false, Inactive",
        "false, true, Inactive",
        "true, true, Active",
        "true, false, StoppedBySystem",
    )
    fun `etat du service`(settingEnabled: Boolean, connected: Boolean, expected: ServiceStatus) {
        assertEquals(expected, ServiceStatus.of(settingEnabled, connected))
    }
}
