package com.swipper.app.control

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.swipper.app.R
import com.swipper.app.gesture.FractionPoint
import com.swipper.app.gesture.ScreenSize
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Calque de calibration de l'ouverture de profil (story 7), en `TYPE_ACCESSIBILITY_OVERLAY`
 * plein écran et semi-transparent, avec une consigne : il capte un seul toucher, le
 * convertit en fractions de l'écran ([FractionPoint.of]) et disparaît. Annulable par le
 * bouton « Annuler » ou la touche Retour (le calque prend le focus pour la recevoir).
 * Il ne lit pas le contenu de l'écran.
 *
 * À utiliser depuis le thread principal. Une seule issue par [show] : [onPoint] ou [onCancel].
 */
class CalibrationOverlay(
    private val service: AccessibilityService,
    private val screenSize: () -> ScreenSize,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null

    /** `true` tant que le calque est affiché. */
    val isShowing: Boolean get() = root != null

    /**
     * Affiche le calque pour l'app [appLabel]. Sans effet s'il est déjà affiché.
     *
     * @return `false` si le système refuse d'afficher le calque.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(appLabel: String, onPoint: (FractionPoint) -> Unit, onCancel: () -> Unit): Boolean {
        if (root != null) return true
        var done = false
        fun finish(action: () -> Unit) {
            if (done) return
            done = true
            hide()
            action()
        }

        val layout = object : FrameLayout(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) finish(onCancel)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val instruction = TextView(service).apply {
            text = service.getString(R.string.calibration_instruction, appLabel)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        layout.addView(
            instruction,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ).apply { topMargin = (screenSize().heightPx * INSTRUCTION_TOP_FRACTION).roundToInt() },
        )
        val cancel = Button(service).apply {
            text = service.getString(R.string.calibration_cancel)
            setOnClickListener { finish(onCancel) }
        }
        layout.addView(
            cancel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = (screenSize().heightPx * CANCEL_TOP_FRACTION).roundToInt() },
        )
        // Le bouton consomme ses touches ; tout autre toucher à un doigt, presque immobile
        // (moins que le seuil de glissement), est la position calibrée. Sinon on attend le suivant.
        val slop = ViewConfiguration.get(service).scaledTouchSlop.toFloat()
        var tracking = false
        var downX = 0f
        var downY = 0f
        fun moved(event: MotionEvent) = hypot(event.rawX - downX, event.rawY - downY) > slop
        layout.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
                MotionEvent.ACTION_MOVE -> if (tracking && moved(event)) tracking = false
                MotionEvent.ACTION_UP -> {
                    if (tracking && event.pointerCount == 1 && !moved(event)) {
                        val point = FractionPoint.of(downX, downY, screenSize())
                        finish { onPoint(point) }
                    }
                    tracking = false
                }
            }
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        return try {
            wm.addView(layout, params)
            root = layout
            layout.requestFocus()
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Impossible d'afficher le calque de calibration", e)
            false
        }
    }

    /** Retire le calque s'il est affiché (sans appeler les rappels de [show]). */
    fun hide() {
        val view = root ?: return
        root = null
        runCatching { wm.removeView(view) }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "CalibrationOverlay"

        /** Consigne et bouton dans le haut de l'écran : la zone du profil est plutôt en bas. */
        const val INSTRUCTION_TOP_FRACTION = 0.10
        const val CANCEL_TOP_FRACTION = 0.25
    }
}
