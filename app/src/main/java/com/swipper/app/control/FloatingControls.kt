package com.swipper.app.control

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.swipper.app.R
import kotlin.math.roundToInt

/**
 * Bouton flottant Play-Pause / Stop (CAP-5), en `TYPE_ACCESSIBILITY_OVERLAY` : aucune
 * permission « superposition ». Placé dans le haut de l'écran, entièrement au-dessus de
 * [MAX_BOTTOM_FRACTION] de la hauteur, donc hors de la bande où passent les swipes ;
 * la fenêtre n'occupe que la taille des boutons (`WRAP_CONTENT`), le reste de l'écran
 * reçoit les touches normalement. Déplaçable verticalement par la poignée, dans cette bande.
 *
 * À utiliser depuis le thread principal.
 */
class FloatingControls(
    private val service: AccessibilityService,
    private val controller: AutomationController,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var playPause: TextView? = null
    private var stop: TextView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(8)
        y = (screenHeight() * INITIAL_TOP_FRACTION).roundToInt()
    }

    /** Affiche ou masque le bouton et met à jour ses libellés. */
    fun render(visible: Boolean, state: AutomationState, userPaused: Boolean) {
        if (!visible) {
            hide()
            return
        }
        if (root == null) show()
        val running = state != AutomationState.Idle && !userPaused
        playPause?.apply {
            text = if (running) PAUSE_GLYPH else PLAY_GLYPH
            contentDescription = service.getString(
                when {
                    state == AutomationState.Idle -> R.string.action_start
                    userPaused -> R.string.action_resume
                    else -> R.string.action_pause
                },
            )
        }
        stop?.apply {
            isEnabled = state != AutomationState.Idle
            alpha = if (isEnabled) 1f else 0.4f
        }
    }

    /** Retire le bouton s'il est affiché. */
    fun hide() {
        val view = root ?: return
        root = null
        playPause = null
        stop = null
        runCatching { wm.removeView(view) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val grip = button(GRIP_GLYPH, R.string.overlay_move).apply { alpha = 0.7f }
        val pp = button(PLAY_GLYPH, R.string.action_start).apply {
            setOnClickListener {
                if (controller.state.value == AutomationState.Idle) controller.start() else controller.togglePause()
            }
        }
        val st = button(STOP_GLYPH, R.string.action_stop).apply { setOnClickListener { controller.stop() } }
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.argb(200, 30, 30, 30))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(grip)
            addView(pp)
            addView(st)
        }
        var startRawY = 0f
        var startY = 0
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxY = (screenHeight() * MAX_BOTTOM_FRACTION).roundToInt() - layout.height
                    params.y = (startY + (event.rawY - startRawY).roundToInt()).coerceIn(0, maxOf(0, maxY))
                    runCatching { wm.updateViewLayout(layout, params) }
                }
            }
            true
        }
        try {
            wm.addView(layout, params)
            root = layout
            playPause = pp
            stop = st
        } catch (e: RuntimeException) {
            Log.e(TAG, "Impossible d'afficher le bouton flottant", e)
        }
    }

    private fun button(glyph: String, description: Int): TextView = TextView(service).apply {
        text = glyph
        contentDescription = service.getString(description)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP))
    }

    private fun screenHeight(): Int = service.resources.displayMetrics.heightPixels

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "FloatingControls"
        const val BUTTON_DP = 48
        const val INITIAL_TOP_FRACTION = 0.08
        /** Le bas du bouton reste au-dessus de 25 % de la hauteur (swipes : y ≥ ~33 %). */
        const val MAX_BOTTOM_FRACTION = 0.25
        const val PLAY_GLYPH = "▶"
        const val PAUSE_GLYPH = "❚❚"
        const val STOP_GLYPH = "■"
        const val GRIP_GLYPH = "↕"
    }
}
