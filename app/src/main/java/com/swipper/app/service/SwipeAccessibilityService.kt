package com.swipper.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.swipper.app.R
import com.swipper.app.gesture.Direction
import com.swipper.app.gesture.GestureGenerator
import com.swipper.app.gesture.ScreenSize
import com.swipper.app.gesture.SwipeZone
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Service d'accessibilité de Swipper.
 *
 * Il ne lit pas l'écran. Story 3 : expose son instance connectée et exécute des
 * swipes de test ([testSwipe]) via [GestureDispatcher].
 */
class SwipeAccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var testJob: Job? = null
    private val generator = GestureGenerator(Random.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        connected.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Volontairement vide.
    }

    override fun onInterrupt() {
        // Volontairement vide.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        scope?.cancel()
        scope = null
        testJob = null
        if (connected.value === this) connected.value = null
    }

    /**
     * Arme un swipe de test dans [direction], exécuté après [delayMs] (le temps de
     * basculer sur l'app cible). Un seul swipe de test à la fois : un nouvel appel
     * remplace celui en attente. Renvoie `false` si le service n'est pas connecté.
     */
    fun testSwipe(direction: Direction, delayMs: Long = 5000): Boolean {
        val s = scope ?: return false
        testJob?.cancel()
        testJob = s.launch {
            delay(delayMs)
            val plan = generator.plan(screenSize(), SwipeZone.DEFAULT, direction)
            val result = GestureDispatcher(this@SwipeAccessibilityService).execute(plan)
            Log.d(TAG, "Swipe test $direction : $result (${plan.segments.size} segments, ${plan.totalDurationMs} ms)")
            val outcome = getString(
                when (result) {
                    DispatchResult.Completed -> R.string.test_result_completed
                    DispatchResult.Cancelled -> R.string.test_result_cancelled
                    DispatchResult.Failed -> R.string.test_result_failed
                },
            )
            val arrow = if (direction == Direction.RIGHT) "→" else "←"
            Toast.makeText(
                this@SwipeAccessibilityService,
                getString(R.string.toast_test_result, arrow, outcome),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }

    /** Taille réelle de l'écran en pixels. */
    private fun screenSize(): ScreenSize {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            ScreenSize(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            ScreenSize(metrics.widthPixels, metrics.heightPixels)
        }
    }

    companion object {
        private const val TAG = "SwipeService"

        private val connected = MutableStateFlow<SwipeAccessibilityService?>(null)

        /** Instance connectée du service, `null` hors connexion. */
        val instance: StateFlow<SwipeAccessibilityService?> = connected.asStateFlow()
    }
}
