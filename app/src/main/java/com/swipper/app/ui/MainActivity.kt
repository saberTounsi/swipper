package com.swipper.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swipper.app.R
import com.swipper.app.gesture.Direction
import com.swipper.app.service.SwipeAccessibilityService
import com.swipper.app.service.accessibilityServiceEnabledFlow
import com.swipper.app.service.readServiceEnabled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** État affiché : alimenté par le ContentObserver et relu à chaque onResume. */
    private val serviceEnabled = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceEnabled.value = readServiceEnabled(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                accessibilityServiceEnabledFlow(this@MainActivity).collect { serviceEnabled.value = it }
            }
        }

        setContent {
            val enabled by serviceEnabled.collectAsState()
            MaterialTheme {
                Surface {
                    HomeScreen(
                        serviceEnabled = enabled,
                        onEnableClick = ::openAccessibilitySettings,
                        onOpenAppInfoClick = ::openAppDetails,
                        onTestSwipeClick = ::armTestSwipe,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled.value = readServiceEnabled(this)
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
