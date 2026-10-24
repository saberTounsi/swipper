package com.swipper.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Service d'accessibilité de Swipper.
 *
 * Story 1 : squelette minimal. Il ne lit pas l'écran et n'envoie aucun geste ;
 * il sert de point d'ancrage pour le suivi de l'app au premier plan et l'exécution
 * des gestes (stories ultérieures).
 */
class SwipeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Volontairement vide.
    }

    override fun onInterrupt() {
        // Volontairement vide.
    }
}
