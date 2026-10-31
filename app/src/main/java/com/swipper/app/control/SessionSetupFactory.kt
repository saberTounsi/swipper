package com.swipper.app.control

import com.swipper.app.behavior.BehaviorEngine
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.behavior.Clock
import com.swipper.app.decision.DirectionPicker
import com.swipper.app.gesture.FractionPoint
import com.swipper.app.settings.Settings
import kotlin.random.Random

/**
 * Moteur, sélecteur et apps cibles d'une nouvelle session (story 6), depuis les réglages
 * enregistrés [saved] et l'état sauvegardé [state]. Des réglages invalides sont remplacés par
 * les défauts (positions calibrées gardées).
 *
 * @param liveProfilePoint position d'ouverture de profil de l'app au premier plan, lue à
 *   chaque décision (une calibration faite pendant la session compte) ; `null` = pas
 *   d'ouverture de profil.
 */
fun sessionSetupFrom(
    saved: Settings,
    state: BehaviorState?,
    random: Random,
    clock: Clock,
    liveProfilePoint: () -> FractionPoint?,
): SessionSetup {
    val settings = saved.orDefaults()
    return SessionSetup(
        engine = BehaviorEngine(settings.toBehaviorConfig(), random, clock, state) { liveProfilePoint() != null },
        picker = DirectionPicker(random, settings.rightRatio),
        isTarget = { pkg -> TargetApps.isTarget(pkg, settings.allowedPackages) },
    )
}
