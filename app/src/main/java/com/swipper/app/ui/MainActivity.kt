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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swipper.app.R
import com.swipper.app.control.AutomationController
import com.swipper.app.control.AutomationState
import com.swipper.app.control.TargetApps
import com.swipper.app.gesture.Direction
import com.swipper.app.service.SwipeAccessibilityService
import com.swipper.app.service.accessibilityServiceEnabledFlow
import com.swipper.app.service.readServiceEnabled
import com.swipper.app.settings.SettingsRepository
import com.swipper.app.settings.swipesForDay
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
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

        val repository = SettingsRepository.get(this)

        val controllerLoop: Flow<LoopUi> = SwipeAccessibilityService.instance.flatMapLatest { service ->
            val c = service?.controller ?: return@flatMapLatest flowOf(LoopUi())
            combine(c.state, c.userPaused, c.swipesToday, c.error, c.resumeAt) { st, paused, count, err, resume ->
                LoopUi(st, paused, count, err, resume, c.zone)
            }
        }
        // Jour local, réévalué régulièrement pour remettre le compteur à zéro à minuit.
        val today: Flow<LocalDate> = flow {
            while (true) {
                emit(LocalDate.now())
                delay(DAY_TICK_MS)
            }
        }.distinctUntilChanged()
        // Session en cours : compteur du contrôleur (celui de la notification) ; sinon l'état sauvegardé.
        val loopFlow: Flow<LoopUi> = combine(controllerLoop, repository.behaviorState, today) { loop, saved, day ->
            if (loop.state == AutomationState.Idle) loop.copy(swipesToday = swipesForDay(saved, day)) else loop
        }

        setContent {
            val enabled by serviceEnabled.collectAsState()
            val instance by SwipeAccessibilityService.instance.collectAsState()
            val loop by loopFlow.collectAsStateWithLifecycle(LoopUi())
            val settings by repository.settings.collectAsStateWithLifecycle(null)
            var showSettings by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showSettings) { showSettings = false }
            MaterialTheme {
                Surface {
                    val saved = settings
                    if (showSettings && saved != null) {
                        SettingsScreen(
                            saved = saved,
                            sessionRunning = loop.state != AutomationState.Idle,
                            onSave = { newSettings ->
                                lifecycleScope.launch {
                                    val message = try {
                                        repository.save(newSettings)
                                        R.string.toast_settings_saved
                                    } catch (_: IOException) {
                                        R.string.toast_settings_error
                                    }
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onBack = { showSettings = false },
                            onCalibrate = ::startCalibration,
                        )
                    } else {
                        HomeScreen(
                            status = ServiceStatus.of(enabled, instance != null),
                            loop = loop,
                            onEnableClick = ::openAccessibilitySettings,
                            onOpenAppInfoClick = ::openAppDetails,
                            onTestSwipeClick = ::armTestSwipe,
                            onStartClick = ::startAutomation,
                            onTogglePauseClick = { controller()?.togglePause() },
                            onStopClick = { controller()?.stop() },
                            onSettingsClick = { showSettings = true },
                        )
                    }
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

    /**
     * Calibration de l'ouverture de profil (story 7) : le service attend que l'utilisateur
     * ouvre l'app, puis affiche le calque.
     */
    private fun startCalibration(packageName: String) {
        val label = TargetApps.label(packageName)
        val armed = SwipeAccessibilityService.instance.value?.startCalibration(packageName, label) == true
        if (armed) {
            Toast.makeText(this, getString(R.string.toast_calibration_armed, label), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.toast_service_inactive, Toast.LENGTH_SHORT).show()
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

    private companion object {
        /** Période de réévaluation du jour local pour le compteur. */
        const val DAY_TICK_MS = 30_000L
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}
