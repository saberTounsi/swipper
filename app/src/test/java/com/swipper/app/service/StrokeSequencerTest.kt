package com.swipper.app.service

import com.swipper.app.gesture.Point
import com.swipper.app.gesture.Segment
import com.swipper.app.gesture.SwipePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StrokeSequencerTest {

    /** Comportement du faux système pour un trait. */
    private enum class Reply { Complete, Cancel, Refuse, Hang }

    private sealed interface Call {
        data class First(val segment: Segment, val willContinue: Boolean) : Call
        data class Next(val segment: Segment, val willContinue: Boolean) : Call
        data class Lift(val at: Point) : Call
    }

    private class FakeSink(
        private val replyFor: (index: Int) -> Reply = { Reply.Complete },
        private val liftReply: Reply = Reply.Complete,
    ) : StrokeSink {
        val calls = mutableListOf<Call>()
        val pending = mutableListOf<(Boolean) -> Unit>()

        override fun first(segment: Segment, willContinue: Boolean, onDone: (Boolean) -> Unit): Boolean {
            calls += Call.First(segment, willContinue)
            return reply(replyFor(calls.size - 1), onDone)
        }

        override fun next(segment: Segment, willContinue: Boolean, onDone: (Boolean) -> Unit): Boolean {
            calls += Call.Next(segment, willContinue)
            return reply(replyFor(calls.size - 1), onDone)
        }

        override fun lift(at: Point, onDone: (Boolean) -> Unit): Boolean {
            calls += Call.Lift(at)
            return reply(liftReply, onDone)
        }

        private fun reply(reply: Reply, onDone: (Boolean) -> Unit): Boolean = when (reply) {
            Reply.Complete -> { onDone(true); true }
            Reply.Cancel -> { onDone(false); true }
            Reply.Refuse -> false
            Reply.Hang -> { pending += onDone; true }
        }
    }

    /** Plan continu de [n] segments sur une ligne horizontale. */
    private fun plan(n: Int): SwipePlan = SwipePlan(
        segments = (0 until n).map { i ->
            Segment(listOf(Point(i * 10f, 50f), Point(i * 10f + 5f, 51f), Point((i + 1) * 10f, 50f)), 20L + i)
        },
        hesitant = false,
    )

    @Test
    fun `plan normal - N dispatchs dans l'ordre, continuation sauf au dernier, Completed`() = runTest {
        val p = plan(4)
        val sink = FakeSink()

        val result = StrokeSequencer().run(p, sink)

        assertEquals(DispatchResult.Completed, result)
        assertEquals(
            listOf(
                Call.First(p.segments[0], true),
                Call.Next(p.segments[1], true),
                Call.Next(p.segments[2], true),
                Call.Next(p.segments[3], false),
            ),
            sink.calls,
        )
    }

    @Test
    fun `plan d'un seul segment - premier trait sans continuation`() = runTest {
        val p = plan(1)
        val sink = FakeSink()

        assertEquals(DispatchResult.Completed, StrokeSequencer().run(p, sink))
        assertEquals(listOf(Call.First(p.segments[0], false)), sink.calls)
    }

    @Test
    fun `segment annule par le systeme - aucun segment suivant, Cancelled`() = runTest {
        val p = plan(5)
        val sink = FakeSink(replyFor = { if (it == 2) Reply.Cancel else Reply.Complete })

        val result = StrokeSequencer().run(p, sink)

        assertEquals(DispatchResult.Cancelled, result)
        assertEquals(3, sink.calls.size)
        assertTrue(sink.calls.none { it is Call.Lift })
    }

    @Test
    fun `dispatch refuse doigt pose - aucun segment suivant, levee du doigt, Failed`() = runTest {
        val p = plan(5)
        val sink = FakeSink(replyFor = { if (it == 1) Reply.Refuse else Reply.Complete })

        val result = StrokeSequencer().run(p, sink)

        assertEquals(DispatchResult.Failed, result)
        assertEquals(3, sink.calls.size)
        assertEquals(Call.Lift(p.segments[0].end), sink.calls.last())
    }

    @Test
    fun `premier segment refuse - pas de levee, Failed`() = runTest {
        val p = plan(3)
        val sink = FakeSink(replyFor = { Reply.Refuse })

        assertEquals(DispatchResult.Failed, StrokeSequencer().run(p, sink))
        assertEquals(listOf(Call.First(p.segments[0], true)), sink.calls)
    }

    @Test
    fun `refus puis levee sans reponse - Failed apres le delai de levee`() = runTest {
        val p = plan(4)
        val sink = FakeSink(replyFor = { if (it == 2) Reply.Refuse else Reply.Complete }, liftReply = Reply.Hang)

        val result = StrokeSequencer().run(p, sink)

        assertEquals(DispatchResult.Failed, result)
        assertEquals(Call.Lift(p.segments[1].end), sink.calls.last())
        assertTrue(currentTime >= StrokeSequencer.LIFT_TIMEOUT_MS)
    }

    @Test
    fun `annulation coroutine au segment k - levee du doigt puis CancellationException`() = runTest {
        val p = plan(5)
        val k = 2
        val sink = FakeSink(replyFor = { if (it == k) Reply.Hang else Reply.Complete })
        var thrown: Throwable? = null

        val job = launch {
            try {
                StrokeSequencer().run(p, sink)
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        advanceUntilIdle()
        assertEquals(k + 1, sink.calls.size)
        job.cancel()
        job.join()

        assertTrue(thrown is CancellationException)
        assertEquals(k + 2, sink.calls.size)
        assertEquals(Call.Lift(p.segments[k].end), sink.calls.last())
        // Le callback tardif du segment annulé est ignoré et n'envoie rien.
        sink.pending.forEach { it(true) }
        assertEquals(k + 2, sink.calls.size)
    }

    @Test
    fun `annulation pendant le premier segment - levee du doigt`() = runTest {
        val p = plan(3)
        val sink = FakeSink(replyFor = { if (it == 0) Reply.Hang else Reply.Complete })

        val job = launch { StrokeSequencer().run(p, sink) }
        advanceUntilIdle()
        job.cancel()
        job.join()

        assertEquals(listOf(Call.First(p.segments[0], true), Call.Lift(p.segments[0].end)), sink.calls)
    }

    @Test
    fun `annulation au dernier segment - pas de trait de levee`() = runTest {
        val p = plan(4)
        val sink = FakeSink(replyFor = { if (it == 3) Reply.Hang else Reply.Complete })
        var thrown: Throwable? = null

        val job = launch {
            try {
                StrokeSequencer().run(p, sink)
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        advanceUntilIdle()
        job.cancel()
        job.join()

        assertTrue(thrown is CancellationException)
        assertEquals(4, sink.calls.size)
        assertTrue(sink.calls.none { it is Call.Lift })
    }

    @Test
    fun `levee sans reponse du systeme - l'annulation se termine quand meme`() = runTest {
        val p = plan(3)
        val sink = FakeSink(replyFor = { if (it == 1) Reply.Hang else Reply.Complete }, liftReply = Reply.Hang)

        var thrown: Throwable? = null

        val job = launch {
            try {
                StrokeSequencer().run(p, sink)
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        advanceUntilIdle()
        val cancelledAt = currentTime
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertTrue(thrown is CancellationException)
        assertTrue(currentTime - cancelledAt >= StrokeSequencer.LIFT_TIMEOUT_MS)
        assertTrue(sink.calls.last() is Call.Lift)
    }
}
