package com.swipper.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swipper.app.R
import com.swipper.app.control.TargetApps
import com.swipper.app.settings.Field
import com.swipper.app.settings.FieldError
import com.swipper.app.settings.ParseResult
import com.swipper.app.settings.Reason
import com.swipper.app.settings.Settings
import com.swipper.app.settings.SettingsForm
import kotlin.math.roundToInt

/**
 * Écran de réglages (CAP-6). Les erreurs de saisie s'affichent sous le champ concerné et
 * bloquent l'enregistrement ; les réglages s'appliquent au prochain Démarrer.
 *
 * @param saved réglages enregistrés, qui pré-remplissent le formulaire.
 * @param sessionRunning une session est en cours : l'écran rappelle que les changements
 *   s'appliqueront au prochain Démarrer.
 * @param onCalibrate lance la calibration de l'ouverture de profil de l'app (nom de paquet).
 */
@Composable
fun SettingsScreen(
    saved: Settings,
    sessionRunning: Boolean,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCalibrate: (String) -> Unit = {},
) {
    // Sans les positions calibrées : une calibration ne reconstruit pas le formulaire (saisies
    // non enregistrées conservées) ; l'état de calibration s'affiche depuis [saved].
    val savedForm = saved.copy(profilePoints = emptyMap())
    var form by remember(savedForm) { mutableStateOf(SettingsForm.of(savedForm)) }
    val result = form.parse()
    val errors = (result as? ParseResult.Invalid)?.errors.orEmpty()
    val dirty = result != ParseResult.Valid(savedForm)

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        if (sessionRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    stringResource(R.string.settings_session_running),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            Text(stringResource(R.string.settings_apply_hint), style = MaterialTheme.typography.bodySmall)
        }

        val ratio = form.rightRatioPercent.toIntOrNull()?.coerceIn(0, 100) ?: saved.rightRatioPercent
        Text(stringResource(R.string.settings_ratio, ratio), style = MaterialTheme.typography.titleSmall)
        Slider(
            value = ratio.toFloat(),
            onValueChange = { form = form.copy(rightRatioPercent = it.roundToInt().toString()) },
            valueRange = 0f..100f,
            steps = 99,
        )

        NumberField(stringResource(R.string.settings_delay), form.delayMedianSeconds, errors, Field.DelayMedian, decimal = true) {
            form = form.copy(delayMedianSeconds = it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(stringResource(R.string.settings_session_min), form.sessionMin, errors, Field.SessionMin, Modifier.weight(1f)) {
                form = form.copy(sessionMin = it)
            }
            NumberField(stringResource(R.string.settings_session_max), form.sessionMax, errors, Field.SessionMax, Modifier.weight(1f)) {
                form = form.copy(sessionMax = it)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(stringResource(R.string.settings_rest_min), form.restMinMinutes, errors, Field.RestMin, Modifier.weight(1f)) {
                form = form.copy(restMinMinutes = it)
            }
            NumberField(stringResource(R.string.settings_rest_max), form.restMaxMinutes, errors, Field.RestMax, Modifier.weight(1f)) {
                form = form.copy(restMaxMinutes = it)
            }
        }
        NumberField(stringResource(R.string.settings_daily_cap), form.dailyCap, errors, Field.DailyCap) {
            form = form.copy(dailyCap = it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField(stringResource(R.string.settings_active_start), form.activeStart, errors, Field.ActiveStart, Modifier.weight(1f)) {
                form = form.copy(activeStart = it)
            }
            TimeField(stringResource(R.string.settings_active_end), form.activeEnd, errors, Field.ActiveEnd, Modifier.weight(1f)) {
                form = form.copy(activeEnd = it)
            }
        }

        Text(stringResource(R.string.settings_targets), style = MaterialTheme.typography.titleSmall)
        for ((pkg, label) in listOf(TargetApps.TINDER to R.string.app_tinder, TargetApps.BUMBLE to R.string.app_bumble)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = pkg in form.targetPackages,
                    onCheckedChange = { checked ->
                        form = form.copy(targetPackages = if (checked) form.targetPackages + pkg else form.targetPackages - pkg)
                    },
                )
                Text(stringResource(label))
            }
        }
        ErrorText(errors, Field.TargetApps)

        Text(stringResource(R.string.settings_profile_title), style = MaterialTheme.typography.titleSmall)
        val profileOpen = form.profileOpenPercent.toIntOrNull()?.coerceIn(Settings.PROFILE_OPEN_RANGE)
            ?: saved.profileOpenPercent
        Text(stringResource(R.string.settings_profile_open, profileOpen), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = profileOpen.toFloat(),
            onValueChange = { form = form.copy(profileOpenPercent = it.roundToInt().toString()) },
            valueRange = Settings.PROFILE_OPEN_RANGE.first.toFloat()..Settings.PROFILE_OPEN_RANGE.last.toFloat(),
            steps = Settings.PROFILE_OPEN_RANGE.last - Settings.PROFILE_OPEN_RANGE.first - 1,
        )
        Text(stringResource(R.string.settings_profile_hint), style = MaterialTheme.typography.bodySmall)
        for ((pkg, button) in listOf(TargetApps.TINDER to R.string.settings_calibrate_tinder)) {
            val point = saved.profilePoint(pkg)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onCalibrate(pkg) }) { Text(stringResource(button)) }
                Text(
                    if (point == null) {
                        stringResource(R.string.settings_not_calibrated)
                    } else {
                        stringResource(R.string.settings_calibrated, (point.x * 100).roundToInt(), (point.y * 100).roundToInt())
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(
            onClick = { (result as? ParseResult.Valid)?.let { onSave(it.settings) } },
            enabled = result is ParseResult.Valid && dirty,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_save)) }
        OutlinedButton(onClick = { form = SettingsForm.of(Settings()) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_reset))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_back))
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    errors: List<FieldError>,
    field: Field,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    onChange: (String) -> Unit,
) {
    val error = errors.firstOrNull { it.field == field }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(errorMessage(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    errors: List<FieldError>,
    field: Field,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val error = errors.firstOrNull { it.field == field }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("09:00") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(errorMessage(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(errors: List<FieldError>, field: Field) {
    errors.firstOrNull { it.field == field }?.let {
        Text(errorMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun errorMessage(error: FieldError): String = when (error.reason) {
    Reason.MinAboveMax -> stringResource(R.string.settings_error_min_above_max)
    Reason.SameStartEnd -> stringResource(R.string.settings_error_same_start_end)
    Reason.NoTargetApp -> stringResource(R.string.settings_error_no_target)
    Reason.OutOfRange -> when (error.field) {
        Field.RightRatio -> stringResource(R.string.settings_error_ratio)
        Field.DelayMedian -> stringResource(R.string.settings_error_delay)
        Field.SessionMin, Field.SessionMax -> stringResource(R.string.settings_error_session)
        Field.RestMin, Field.RestMax -> stringResource(R.string.settings_error_rest)
        Field.DailyCap -> stringResource(R.string.settings_error_cap)
        Field.ActiveStart, Field.ActiveEnd -> stringResource(R.string.settings_error_time)
        Field.TargetApps -> stringResource(R.string.settings_error_no_target)
        Field.ProfileOpen -> stringResource(R.string.settings_error_profile_open)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme { SettingsScreen(Settings(), sessionRunning = true, onSave = {}, onBack = {}) }
}
