package com.swipper.app.control

/** Apps cibles (v1) : les seules au premier plan desquelles un geste peut partir. */
object TargetApps {
    /** Noms de paquet de Tinder et Bumble. */
    val PACKAGES: Set<String> = setOf("com.tinder", "com.bumble.app")

    /** `true` si [packageName] est une app cible. */
    fun isTarget(packageName: String?): Boolean = packageName != null && packageName in PACKAGES
}
