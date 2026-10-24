package com.swipper.app.service

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Indique si [component] figure dans la valeur de
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` ([setting]).
 *
 * La valeur est une liste de composants séparés par `:`, au format long
 * (`pkg/pkg.Classe`) ou court (`pkg/.Classe`). Chaque entrée est analysée comme un
 * composant (même règle que `ComponentName.unflattenFromString`) puis comparée
 * au paquet et à la classe attendus : pas de `contains` sur la chaîne brute, donc
 * pas de faux positif sur un service au nom proche. Comparaison sensible à la casse,
 * comme `ComponentName.equals` (les noms de paquet Android le sont).
 * Une valeur nulle, vide ou mal formée donne `false`.
 */
fun isServiceEnabled(setting: String?, component: ComponentName): Boolean =
    isServiceEnabled(setting, component.packageName, component.className)

/** Variante pure (sans classe Android) de [isServiceEnabled], testable en JVM. */
fun isServiceEnabled(setting: String?, packageName: String, className: String): Boolean {
    if (setting.isNullOrBlank()) return false
    return setting.split(':').any { entry ->
        val parsed = parseComponent(entry) ?: return@any false
        parsed.first == packageName && parsed.second == className
    }
}

/**
 * Équivalent pur de `ComponentName.unflattenFromString` : renvoie (paquet, classe
 * complète) ou `null` si l'entrée est mal formée.
 */
internal fun parseComponent(flat: String): Pair<String, String>? {
    val sep = flat.indexOf('/')
    if (sep <= 0 || sep == flat.length - 1) return null
    val pkg = flat.substring(0, sep)
    val cls = flat.substring(sep + 1)
    val fullClass = if (cls.startsWith('.')) pkg + cls else cls
    return pkg to fullClass
}

/** Composant du service de Swipper pour ce [Context]. */
fun swipeServiceComponent(context: Context): ComponentName =
    ComponentName(context, SwipeAccessibilityService::class.java)

/** Lit l'état actuel du service dans les réglages sécurisés. */
fun readServiceEnabled(context: Context): Boolean {
    val setting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )
    return isServiceEnabled(setting, swipeServiceComponent(context))
}

/**
 * Flow de l'état du service : émet la valeur courante, puis une nouvelle valeur à
 * chaque modification de `ENABLED_ACCESSIBILITY_SERVICES` (ContentObserver).
 */
fun accessibilityServiceEnabledFlow(context: Context): Flow<Boolean> = callbackFlow {
    val appContext = context.applicationContext
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(readServiceEnabled(appContext))
        }
    }
    appContext.contentResolver.registerContentObserver(
        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        false,
        observer,
    )
    trySend(readServiceEnabled(appContext))
    awaitClose { appContext.contentResolver.unregisterContentObserver(observer) }
}.conflate().distinctUntilChanged()
