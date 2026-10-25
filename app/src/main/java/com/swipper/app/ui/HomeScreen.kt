package com.swipper.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swipper.app.R
import com.swipper.app.control.AutomationNotification
import com.swipper.app.control.AutomationState
import com.swipper.app.gesture.Direction

/** État du service vu par l'utilisateur (CAP-1 et constat HyperOS). */
enum class ServiceStatus {
    /** Réglage désactivé. */
    Inactive,

    /** Réglage activé et instance connectée. */
    Active,

    /** Réglage activé mais aucune instance : le système a arrêté le service. */
    StoppedBySystem;

    companion object {
        fun of(settingEnabled: Boolean, connected: Boolean): ServiceStatus = when {
            !settingEnabled -> Inactive
            connected -> Active
            else -> StoppedBySystem
        }
    }
}

/** Ce que l'écran affiche de la boucle. */
data class LoopUi(
    val state: AutomationState = AutomationState.Idle,
    val userPaused: Boolean = false,
    val swipesToday: Int = 0,
    val error: String? = null,
)

/** Écran d'accueil : état du service, commandes de l'automatisation, onboarding (CAP-1, CAP-5). */
@Composable
fun HomeScreen(
    status: ServiceStatus,
    loop: LoopUi,
    onEnableClick: () -> Unit,
    onOpenAppInfoClick: () -> Unit,
    onTestSwipeClick: (Direction) -> Unit,
    onStartClick: () -> Unit,
    onTogglePauseClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRestrictedHelp: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val colors = MaterialTheme.colorScheme
        val (title, body) = when (status) {
            ServiceStatus.Inactive -> R.string.status_inactive to R.string.status_inactive_body
            ServiceStatus.Active -> R.string.status_active to R.string.status_active_body
            ServiceStatus.StoppedBySystem -> R.string.status_stopped to R.string.status_stopped_body
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (status == ServiceStatus.Active) colors.primaryContainer else colors.errorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (status == ServiceStatus.StoppedBySystem) {
            Button(onClick = onEnableClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_open_settings))
            }
        }

        if (status != ServiceStatus.Inactive) {
            AutomationSection(status == ServiceStatus.Active, loop, onStartClick, onTogglePauseClick, onStopClick)
        }

        if (status == ServiceStatus.Active) {
            // Un swipe de test annulerait les traits de la boucle (et inversement).
            val testEnabled = loop.state == AutomationState.Idle
            Text(stringResource(R.string.test_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.test_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { onTestSwipeClick(Direction.RIGHT) },
                enabled = testEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_test_right))
            }
            OutlinedButton(
                onClick = { onTestSwipeClick(Direction.LEFT) },
                enabled = testEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_test_left))
            }
        }

        if (status == ServiceStatus.Inactive) {
            Button(onClick = onEnableClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_enable))
            }
            if (showRestrictedHelp) {
                Text(stringResource(R.string.restricted_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.restricted_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onOpenAppInfoClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_open_app_info))
                }
            }
        }
    }
}

@Composable
private fun AutomationSection(
    connected: Boolean,
    loop: LoopUi,
    onStartClick: () -> Unit,
    onTogglePauseClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    val idle = loop.state == AutomationState.Idle
    val stateText = when {
        loop.state == AutomationState.Paused && loop.userPaused -> stringResource(R.string.loop_paused_by_user)
        loop.state == AutomationState.Paused -> stringResource(R.string.loop_paused_waiting_target)
        else -> stringResource(AutomationNotification.stateLabel(loop.state))
    }
    Text(stringResource(R.string.automation_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.automation_state, stateText), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.automation_swipes_today, loop.swipesToday), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.automation_hint), style = MaterialTheme.typography.bodySmall)
    loop.error?.let {
        Text(
            stringResource(R.string.automation_error, it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(onClick = onStartClick, enabled = connected && idle, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_start))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onTogglePauseClick, enabled = connected && !idle, modifier = Modifier.weight(1f)) {
            Text(stringResource(if (loop.userPaused) R.string.action_resume else R.string.action_pause))
        }
        Button(onClick = onStopClick, enabled = connected && !idle, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_stop))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenInactivePreview() {
    MaterialTheme {
        HomeScreen(ServiceStatus.Inactive, LoopUi(), {}, {}, {}, {}, {}, {}, showRestrictedHelp = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenActivePreview() {
    MaterialTheme {
        HomeScreen(ServiceStatus.Active, LoopUi(AutomationState.Running, swipesToday = 12), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenStoppedPreview() {
    MaterialTheme { HomeScreen(ServiceStatus.StoppedBySystem, LoopUi(), {}, {}, {}, {}, {}, {}) }
}
