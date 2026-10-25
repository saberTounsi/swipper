package com.swipper.app.service

/** `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` (constante recopiée pour rester testable en JVM). */
internal const val WINDOW_STATE_CHANGED = 0x00000020

/** Paquet de l'interface système (volet des notifications, écran de verrouillage, dialogues volume/alimentation). */
const val SYSTEM_UI_PACKAGE = "com.android.systemui"

/**
 * Paquet au premier plan à retenir pour un événement d'accessibilité, ou `null` pour
 * l'ignorer (le dernier paquet connu reste valable).
 *
 * Ignorés : tout autre type que [WINDOW_STATE_CHANGED], un paquet absent, les fenêtres
 * transitoires ([transientPackages] : interface système, claviers), qui se superposent à
 * l'app sans qu'elle émette d'événement à leur fermeture, et les fenêtres de Swipper
 * ([ownPackage]) hors de ses activités `<ownPackage>.ui.` (bouton flottant).
 */
fun foregroundFrom(
    eventType: Int,
    packageName: String?,
    className: String?,
    ownPackage: String,
    transientPackages: Set<String>,
): String? {
    if (eventType != WINDOW_STATE_CHANGED) return null
    if (packageName.isNullOrEmpty()) return null
    if (packageName in transientPackages) return null
    if (packageName == ownPackage && className?.startsWith("$ownPackage.ui.") != true) return null
    return packageName
}
