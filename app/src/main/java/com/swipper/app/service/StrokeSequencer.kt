package com.swipper.app.service

import com.swipper.app.gesture.Point
import com.swipper.app.gesture.Segment
import com.swipper.app.gesture.SwipePlan
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Issue de l'exécution d'un [SwipePlan]. */
enum class DispatchResult {
    /** Tous les segments ont été exécutés. */
    Completed,

    /** Le système a annulé un segment (`onCancelled`). */
    Cancelled,

    /** Le système a refusé un segment (`dispatchGesture` a renvoyé `false`). */
    Failed,
}

/**
 * Destination des traits : la couche Android ([GestureDispatcher]) ou un faux en test.
 *
 * Chaque méthode envoie un trait et renvoie `false` si l'envoi est refusé. Sinon,
 * [onDone] est appelé plus tard avec `true` (trait terminé) ou `false` (trait annulé).
 */
interface StrokeSink {
    /** Premier trait du geste (nouveau trait, sans continuation). */
    fun first(segment: Segment, willContinue: Boolean, onDone: (Boolean) -> Unit): Boolean

    /** Trait suivant, en continuation du précédent. */
    fun next(segment: Segment, willContinue: Boolean, onDone: (Boolean) -> Unit): Boolean

    /** Trait de levée du doigt (1 px, 16 ms, sans continuation) en continuation du précédent. */
    fun lift(at: Point, onDone: (Boolean) -> Unit): Boolean
}

/**
 * Séquencement pur (sans Android) d'un [SwipePlan] : un trait par segment, le suivant
 * n'étant envoyé qu'une fois le précédent terminé.
 */
class StrokeSequencer(private val liftTimeoutMs: Long = LIFT_TIMEOUT_MS) {

    private enum class Outcome { Done, Cancelled, Refused }

    /**
     * Exécute [plan] sur [sink]. Annulable : à l'annulation, plus aucun segment n'est
     * envoyé et, si un trait « à continuer » laisse le doigt posé, un trait de levée
     * est envoyé avant de propager la [CancellationException].
     */
    suspend fun run(plan: SwipePlan, sink: StrokeSink): DispatchResult {
        val segments = plan.segments
        // Fin du dernier trait envoyé avec willContinue = true : le doigt y reste posé.
        var fingerDownAt: Point? = null
        try {
            for ((i, segment) in segments.withIndex()) {
                val willContinue = i != segments.lastIndex
                currentCoroutineContext().ensureActive()
                val outcome = await { onDone ->
                    val sent = if (i == 0) sink.first(segment, willContinue, onDone)
                    else sink.next(segment, willContinue, onDone)
                    // Trait parti : s'il continue, le doigt reste posé à sa fin
                    // (y compris si la coroutine est annulée pendant qu'il s'exécute).
                    if (sent) fingerDownAt = if (willContinue) segment.end else null
                    sent
                }
                when (outcome) {
                    Outcome.Done -> Unit
                    Outcome.Refused -> return DispatchResult.Failed
                    Outcome.Cancelled -> {
                        fingerDownAt = null // le système a déjà levé le doigt
                        return DispatchResult.Cancelled
                    }
                }
            }
            return DispatchResult.Completed
        } catch (e: CancellationException) {
            val at = fingerDownAt
            if (at != null) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(liftTimeoutMs) { await { onDone -> sink.lift(at, onDone) } }
                }
            }
            throw e
        }
    }

    /**
     * Envoie un trait via [send] et suspend jusqu'à son callback. Le callback reçu après
     * l'annulation de la coroutine est ignoré.
     *
     * [send] est appelé avant toute suspension ; s'il réussit, [run] considère le trait
     * comme parti même si la coroutine est annulée pendant l'attente.
     */
    private suspend fun await(send: ((Boolean) -> Unit) -> Boolean): Outcome =
        suspendCancellableCoroutine { cont ->
            val sent = send { completed ->
                if (cont.isActive) cont.resume(if (completed) Outcome.Done else Outcome.Cancelled)
            }
            if (!sent && cont.isActive) cont.resume(Outcome.Refused)
        }

    companion object {
        /** Attente maximale du callback du trait de levée. */
        const val LIFT_TIMEOUT_MS = 500L
    }
}
