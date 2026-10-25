package com.swipper.app.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.swipper.app.R
import com.swipper.app.behavior.BehaviorEngine
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.behavior.SystemClock
import com.swipper.app.control.AutomationController
import com.swipper.app.control.AutomationNotification
import com.swipper.app.control.AutomationState
import com.swipper.app.control.CalibrationOverlay
import com.swipper.app.control.FloatingControls
import com.swipper.app.control.SessionSetup
import com.swipper.app.control.TargetApps
import com.swipper.app.decision.DirectionPicker
import com.swipper.app.gesture.Direction
import com.swipper.app.gesture.FractionPoint
import com.swipper.app.gesture.GestureGenerator
import com.swipper.app.gesture.ScreenSize
import com.swipper.app.gesture.SwipeZone
import com.swipper.app.gesture.TapGenerator
import com.swipper.app.settings.Settings as UserSettings
import com.swipper.app.settings.SettingsRepository
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service d'accessibilité de Swipper.
 *
 * Il ne lit pas l'écran. Il suit le paquet au premier plan (`TYPE_WINDOW_STATE_CHANGED`),
 * porte l'[AutomationController] de la session (story 5), le bouton flottant et la
 * notification, et exécute des swipes de test ([testSwipe]) via [GestureDispatcher]. Il
 * porte aussi la calibration de l'ouverture de profil ([startCalibration], story 7).
 */
class SwipeAccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var testJob: Job? = null
    private val generator = GestureGenerator(Random.Default)
    private val tapGenerator = TapGenerator(Random.Default)
    private var calibration: CalibrationOverlay? = null
    private var calibrationJob: Job? = null
    private val foreground = MutableStateFlow<String?>(null)
    private val deviceUsable = MutableStateFlow(true)
    private var screenReceiver: BroadcastReceiver? = null
    private var notification: AutomationNotification? = null
    private var overlay: FloatingControls? = null

    /** Contrôleur de la boucle, non nul tant que le service est connecté. */
    var controller: AutomationController? = null
        private set

    /** Réglages et état du moteur lus dans DataStore ; `null` tant que la lecture n'est pas finie. */
    private var loaded: Loaded? = null

    private class Loaded(var settings: UserSettings, var state: BehaviorState?)

    /** Paquet au premier plan, selon le dernier `TYPE_WINDOW_STATE_CHANGED` reçu. */
    val foregroundPackage: StateFlow<String?> get() = foreground

    override fun onServiceConnected() {
        super.onServiceConnected()
        release()
        // Une exception non rattrapée arrête la boucle (le contrôleur repasse en Idle)
        // et s'affiche, sans faire planter l'app.
        val handler = CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Erreur dans la boucle", e)
            Toast.makeText(
                this,
                getString(R.string.toast_loop_error, e.message ?: e::class.java.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main + handler)
        scope = s
        foreground.value = null
        registerScreenReceiver()
        val repository = SettingsRepository.get(this)
        loaded = null
        val c = AutomationController(
            scope = s,
            engine = BehaviorEngine(UserSettings().toBehaviorConfig(), Random.Default, SystemClock),
            picker = DirectionPicker(Random.Default),
            executor = { direction ->
                if (calibrating()) {
                    DispatchResult.Failed // le geste toucherait le calque de calibration
                } else {
                    val plan = generator.plan(screenSize(), SwipeZone.DEFAULT, direction)
                    GestureDispatcher(this).execute(plan)
                }
            },
            foregroundPackage = foreground,
            clock = SystemClock,
            deviceUsable = deviceUsable,
            isTarget = { pkg -> TargetApps.isTarget(pkg, currentSettings().allowedPackages) },
            setupProvider = ::sessionSetup,
            onStateChanged = { state ->
                loaded?.state = state
                repository.saveBehaviorStateAsync(state)
            },
            tapExecutor = { pkg ->
                val point = currentSettings().profilePoint(pkg)
                if (point == null || calibrating()) {
                    DispatchResult.Failed
                } else {
                    GestureDispatcher(this).execute(tapGenerator.plan(screenSize(), point))
                }
            },
            goBack = { performGlobalAction(GLOBAL_ACTION_BACK) },
            // Démarrer (notification, bouton flottant) pendant une calibration : refusé, sinon
            // chaque geste échouerait sur le calque.
            startAllowed = {
                val ok = !calibrating()
                if (!ok) toast(R.string.toast_calibration_in_progress)
                ok
            },
        )
        controller = c
        // Les réglages suivent DataStore (appliqués au prochain Démarrer) ; l'état sauvegardé
        // n'est lu qu'une fois, ensuite le contrôleur en est la source.
        s.launch {
            val state = repository.currentBehaviorState()
            repository.settings.collect { settings ->
                val l = loaded
                if (l == null) {
                    loaded = Loaded(settings, state)
                    c.setupReady() // Démarrer demandé pendant la lecture
                } else {
                    l.settings = settings
                }
            }
        }
        val n = AutomationNotification(this)
        notification = n
        val o = FloatingControls(this, c)
        overlay = o
        calibration = CalibrationOverlay(this, ::screenSize)
        s.launch {
            combine(c.state, c.swipesToday, c.userPaused, c.resumeAt) { st, count, paused, resume ->
                n.update(st, count, paused, resume, c.zone)
            }.collect {}
        }
        s.launch {
            combine(foreground, c.state, c.userPaused) { pkg, st, paused -> Triple(pkg, st, paused) }
                .collect { (pkg, st, paused) ->
                    // Visible dans une app cible (pour démarrer) ou tant qu'une session existe.
                    o.render(
                        TargetApps.isTarget(pkg, currentSettings().allowedPackages) || st != AutomationState.Idle,
                        st,
                        paused,
                    )
                }
        }
        connected.value = this
    }

    private fun currentSettings(): UserSettings = loaded?.settings ?: UserSettings()

    /**
     * Moteur, sélecteur et apps cibles d'une nouvelle session, depuis les réglages et l'état
     * sauvegardé ; `null` tant que la lecture initiale (asynchrone) n'est pas finie : le
     * contrôleur diffère alors le Démarrer jusqu'à `setupReady()`.
     */
    private fun sessionSetup(): SessionSetup? {
        val l = loaded ?: return null
        val settings = l.settings.orDefaults()
        return SessionSetup(
            // Ouverture de profil possible seulement si l'app au premier plan est calibrée
            // (calibration lue en direct : une calibration faite entre-temps compte).
            engine = BehaviorEngine(settings.toBehaviorConfig(), Random.Default, SystemClock, l.state) {
                currentSettings().profilePoint(foreground.value) != null
            },
            picker = DirectionPicker(Random.Default, settings.rightRatio),
            isTarget = { pkg -> TargetApps.isTarget(pkg, settings.allowedPackages) },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = foregroundFrom(
            eventType = event.eventType,
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            ownPackage = packageName,
            transientPackages = transientPackages(),
        ) ?: return
        foreground.value = pkg
    }

    /** Fenêtres superposées sans changement d'app : interface système et claviers (actif et activés). */
    private fun transientPackages(): Set<String> {
        val result = mutableSetOf(SYSTEM_UI_PACKAGE)
        runCatching {
            getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach {
                result += it.packageName
            }
        }
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { parseComponent(it) }
            ?.let { result += it.first }
        return result
    }

    /** Écran allumé et déverrouillé, réévalué sur SCREEN_OFF / SCREEN_ON / USER_PRESENT. */
    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = updateDeviceUsable()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
        updateDeviceUsable()
    }

    private fun updateDeviceUsable() {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        deviceUsable.value = interactive && !locked
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
        if (connected.value === this) connected.value = null
        controller?.stop()
        controller = null
        overlay?.hide()
        overlay = null
        calibrationJob?.cancel()
        calibrationJob = null
        calibration?.hide()
        calibration = null
        notification?.cancel()
        notification = null
        scope?.cancel()
        scope = null
        loaded = null
        testJob = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    private fun calibrating(): Boolean = calibrationJob?.isActive == true || calibration?.isShowing == true

    /**
     * Calibration de l'ouverture de profil de [packageName] : arrête la session en cours (ses
     * gestes toucheraient le calque), attend que l'utilisateur ouvre l'app (au plus
     * [CALIBRATION_WAIT_MS]), affiche le calque, puis enregistre la position touchée pour
     * cette app. Retour, « Annuler » ou un changement d'app annulent sans rien enregistrer.
     * Un nouvel appel remplace la calibration en cours. Renvoie `false` si le service n'est
     * pas connecté ou si [packageName] n'est pas une app cible connue.
     */
    fun startCalibration(packageName: String, appLabel: String): Boolean {
        val s = scope ?: return false
        val overlayView = calibration ?: return false
        if (packageName !in TargetApps.PACKAGES) return false
        controller?.stop()
        calibrationJob?.cancel()
        overlayView.hide()
        val repository = SettingsRepository.get(this)
        calibrationJob = s.launch {
            val opened = withTimeoutOrNull(CALIBRATION_WAIT_MS) { foreground.first { it == packageName } }
            if (opened == null) {
                toast(R.string.toast_calibration_expired)
                return@launch
            }
            controller?.stop() // Démarrer pendant l'attente
            val result = CompletableDeferred<FractionPoint?>()
            val shown = overlayView.show(appLabel, onPoint = { result.complete(it) }, onCancel = { result.complete(null) })
            if (!shown) {
                toast(R.string.toast_calibration_failed)
                return@launch
            }
            try {
                // Une autre app au premier plan (Accueil, récentes) annule la calibration.
                val left = launch {
                    foreground.first { it != packageName }
                    result.complete(null)
                }
                val point = result.await()
                left.cancel()
                if (point == null) {
                    toast(R.string.toast_calibration_cancelled)
                    return@launch
                }
                try {
                    repository.saveProfilePoint(packageName, point)
                    toast(R.string.toast_calibration_saved)
                } catch (e: IOException) {
                    Log.w(TAG, "Enregistrement de la calibration impossible", e)
                    toast(R.string.toast_calibration_failed)
                }
            } finally {
                overlayView.hide()
            }
        }
        return true
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Republie la notification (par exemple après l'octroi de `POST_NOTIFICATIONS`). */
    fun refreshNotification() {
        val c = controller ?: return
        notification?.update(c.state.value, c.swipesToday.value, c.userPaused.value, c.resumeAt.value, c.zone)
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

        /** Attente maximale de l'ouverture de l'app à calibrer. */
        private const val CALIBRATION_WAIT_MS = 120_000L

        private val connected = MutableStateFlow<SwipeAccessibilityService?>(null)

        /** Instance connectée du service, `null` hors connexion. */
        val instance: StateFlow<SwipeAccessibilityService?> = connected.asStateFlow()
    }
}
