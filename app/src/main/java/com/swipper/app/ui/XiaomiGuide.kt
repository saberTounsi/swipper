package com.swipper.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swipper.app.R

/** Fabricants dont la surcouche (MIUI / HyperOS) tue les services en arrière-plan. */
private val XIAOMI_FAMILY = setOf("xiaomi", "redmi", "poco")

/** `true` si [manufacturer] (`Build.MANUFACTURER`) est Xiaomi, Redmi ou POCO, sans tenir compte de la casse. */
fun isXiaomiFamily(manufacturer: String?): Boolean =
    manufacturer != null && manufacturer.trim().lowercase() in XIAOMI_FAMILY

/** Écrans Xiaomi visés par le guide. */
enum class XiaomiScreen { Autostart, Battery }

/** Carte « Réglages Xiaomi recommandés » : trois étapes, avec un bouton quand un écran existe. */
@Composable
fun XiaomiGuideCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.xiaomi_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.xiaomi_intro), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.xiaomi_step_autostart), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { openXiaomiScreen(context, XiaomiScreen.Autostart) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.xiaomi_open_autostart)) }
            Text(stringResource(R.string.xiaomi_step_battery), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { openXiaomiScreen(context, XiaomiScreen.Battery) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.xiaomi_open_battery)) }
            Text(stringResource(R.string.xiaomi_step_recents), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Ouvre l'écran Xiaomi [screen] ; s'il n'existe pas (ou n'est pas accessible), ouvre les
 * infos de l'appli, puis à défaut les réglages.
 */
fun openXiaomiScreen(context: Context, screen: XiaomiScreen) {
    val pkg = context.packageName
    val candidates = when (screen) {
        XiaomiScreen.Autostart -> listOf(
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ),
        )
        XiaomiScreen.Battery -> listOf(
            Intent().setComponent(
                ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            ).putExtra("package_name", pkg).putExtra("package_label", context.getString(R.string.app_name)),
        )
    } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)) +
        Intent(Settings.ACTION_SETTINGS)
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Écran absent : candidat suivant.
        } catch (_: SecurityException) {
            // Activité non exportée sur cette version : candidat suivant.
        }
    }
}
