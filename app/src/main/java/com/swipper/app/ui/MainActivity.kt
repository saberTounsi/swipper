package com.swipper.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swipper.app.R
import com.swipper.app.control.AutomationController
import com.swipper.app.gesture.Direction
import com.swipper.app.service.SwipeAccessibilityService
import com.swipper.app.service.accessibilityServiceEnabledFlow
import com.swipper.app.service.readServiceEnabled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Réglage d'accessibilité : alimenté par le ContentObserver et relu à chaque onResume. */
    private val serviceEnabled = MutableStateFlow(false)

    /** La permission de notification n'est demandée qu'une fois par lancement de l'écran. */
    private var notificationPermissionAsked = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        SwipeAccessibilityService.instance.value?.refreshNotification()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceEnabled.value = readServiceEnabled(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                accessibilityServiceEnabledFlow(this@MainActivity).collect { serviceEnabled.value = it }
            }
        }

        val loopFlow: Flow<LoopUi> = SwipeAccessibilityService.instance.flatMapLatest { service ->
            val c = service?.controller ?: return@flatMapLatest flowOf(LoopUi())
            combine(c.state, c.userPaused, c.swipesToday, c.error) { st, paused, count, err ->
                LoopUi(st, paused, count, err)
            }
        }

        setContent {
            val enabled by serviceEnabled.collectAsState()
            val instance by SwipeAccessibilityService.instance.collectAsState()
            val loop by loopFlow.collectAsState(LoopUi())
            MaterialTheme {
                Surface {
                    HomeScreen(
                        status = ServiceStatus.of(enabled, instance != null),
                        loop = loop,
                        onEnableClick = ::openAccessibilitySettings,
                        onOpenAppInfoClick = ::openAppDetails,
                        onTestSwipeClick = ::armTestSwipe,
                        onStartClick = ::startAutomation,
                        onTogglePauseClick = { controller()?.togglePause() },
                        onStopClick = { controller()?.stop() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled.value = readServiceEnabled(this)
    }

    private fun controller(): AutomationController? = SwipeAccessibilityService.instance.value?.controller

    /** Démarre la boucle ; au premier Démarrer, demande `POST_NOTIFICATIONS` (Android 13+). */
    private fun startAutomation() {
        val c = controller()
        if (c == null) {
            Toast.makeText(this, R.string.toast_service_inactive, Toast.LENGTH_SHORT).show()
            return
        }
        c.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Arme un swipe de test (story 3) ; « Service inactif » si le service n'est pas connecté. */
    private fun armTestSwipe(direction: Direction) {
        val armed = SwipeAccessibilityService.instance.value?.testSwipe(direction) == true
        val message = when {
            !armed -> R.string.toast_service_inactive
            direction == Direction.RIGHT -> R.string.toast_test_armed_right
            else -> R.string.toast_test_armed_left
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibilitySettings() {
        startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppDetails() {
        startSafely(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}
