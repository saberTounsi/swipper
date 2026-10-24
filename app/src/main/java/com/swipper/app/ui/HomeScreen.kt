package com.swipper.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.swipper.app.gesture.Direction

/** Écran d'accueil : état du service + onboarding d'activation (CAP-1). */
@Composable
fun HomeScreen(
    serviceEnabled: Boolean,
    onEnableClick: () -> Unit,
    onOpenAppInfoClick: () -> Unit,
    onTestSwipeClick: (Direction) -> Unit,
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (serviceEnabled) colors.primaryContainer else colors.errorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(if (serviceEnabled) R.string.status_active else R.string.status_inactive),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(
                        if (serviceEnabled) R.string.status_active_body else R.string.status_inactive_body,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (serviceEnabled) {
            Text(stringResource(R.string.test_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.test_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onTestSwipeClick(Direction.RIGHT) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_test_right))
            }
            OutlinedButton(onClick = { onTestSwipeClick(Direction.LEFT) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_test_left))
            }
        }

        if (!serviceEnabled) {
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

@Preview(showBackground = true)
@Composable
private fun HomeScreenInactivePreview() {
    MaterialTheme {
        HomeScreen(serviceEnabled = false, onEnableClick = {}, onOpenAppInfoClick = {}, onTestSwipeClick = {}, showRestrictedHelp = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenActivePreview() {
    MaterialTheme { HomeScreen(serviceEnabled = true, onEnableClick = {}, onOpenAppInfoClick = {}, onTestSwipeClick = {}) }
}
