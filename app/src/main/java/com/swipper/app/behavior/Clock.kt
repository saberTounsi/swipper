package com.swipper.app.behavior

import java.time.Instant
import java.time.ZoneId

/**
 * Horloge injectable : le moteur de comportement ne lit jamais l'heure système
 * directement, ce qui permet de le tester avec une horloge simulée.
 */
interface Clock {
    /** Instant courant. */
    fun now(): Instant

    /** Fuseau utilisé pour déterminer le jour local (et donc minuit). */
    val zone: ZoneId
}

/** Horloge réelle : [Instant.now] dans le fuseau par défaut du système. */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
    override val zone: ZoneId get() = ZoneId.systemDefault()
}
