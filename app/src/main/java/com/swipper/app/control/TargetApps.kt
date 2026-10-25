package com.swipper.app.control

/**
 * Apps cibles connues : les seules au premier plan desquelles un geste peut partir. Le
 * sous-ensemble autorisé vient des réglages (story 6).
 */
object TargetApps {
    /** Nom de paquet de Tinder. */
    const val TINDER = "com.tinder"

    /** Nom de paquet de Bumble. */
    const val BUMBLE = "com.bumble.app"

    /** Noms de paquet de toutes les apps cibles connues (Tinder et Bumble). */
    val PACKAGES: Set<String> = setOf(TINDER, BUMBLE)

    /** Nom affiché des apps cibles connues. */
    private val LABELS: Map<String, String> = mapOf(TINDER to "Tinder", BUMBLE to "Bumble")

    /** Nom affiché de [packageName] ; le nom de paquet lui-même s'il est inconnu. */
    fun label(packageName: String): String = LABELS[packageName] ?: packageName

    /** `true` si [packageName] est une app cible connue. */
    fun isTarget(packageName: String?): Boolean = isTarget(packageName, PACKAGES)

    /** `true` si [packageName] fait partie de [allowed]. */
    fun isTarget(packageName: String?, allowed: Set<String>): Boolean = packageName != null && packageName in allowed
}
