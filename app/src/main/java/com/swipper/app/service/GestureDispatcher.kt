package com.swipper.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import com.swipper.app.gesture.Point
import com.swipper.app.gesture.Segment
import com.swipper.app.gesture.SwipePlan

/**
 * Couche Android fine : convertit chaque segment en `StrokeDescription` et l'envoie
 * avec `dispatchGesture` (un trait par geste). Le séquencement vit dans [StrokeSequencer].
 *
 * Une instance par plan : elle garde le dernier trait pour `continueStroke`.
 */
class GestureDispatcher(private val service: AccessibilityService) : StrokeSink {

    private var last: StrokeDescription? = null

    /** Exécute [plan] (annulable). Callbacks reçus sur le thread principal. */
    suspend fun execute(plan: SwipePlan): DispatchResult = StrokeSequencer().run(plan, this)

    override fun first(segment: Segment, willContinue: Boolean, onDone: (Boolean) -> Unit): Boolean =
        dispatch(StrokeDescription(segment.toPath(), 0, segment.durationMs, willContinue), onDone)

    override fun next(segment: Segment, willContinue: Boolean, onDone: (Boolean) -> Unit): Boolean {
        val previous = last ?: return false
        return dispatch(previous.continueStroke(segment.toPath(), 0, segment.durationMs, willContinue), onDone)
    }

    override fun lift(at: Point, onDone: (Boolean) -> Unit): Boolean {
        val previous = last ?: return false
        val path = Path().apply {
            moveTo(at.x, at.y)
            lineTo(at.x + 1f, at.y)
        }
        return dispatch(previous.continueStroke(path, 0, LIFT_DURATION_MS, false), onDone)
    }

    private fun dispatch(stroke: StrokeDescription, onDone: (Boolean) -> Unit): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }
        // Seul un trait accepté devient la base de la continuation (levée après refus comprise).
        val sent = service.dispatchGesture(gesture, callback, null)
        if (sent) last = stroke
        return sent
    }

    private fun Segment.toPath(): Path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }

    private companion object {
        const val LIFT_DURATION_MS = 16L
    }
}
