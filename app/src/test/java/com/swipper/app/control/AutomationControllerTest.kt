package com.swipper.app.control

import com.swipper.app.behavior.BehaviorConfig
import com.swipper.app.behavior.BehaviorEngine
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.behavior.Clock
import com.swipper.app.decision.DirectionPicker
import com.swipper.app.gesture.Direction
import com.swipper.app.service.DispatchResult
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val TINDER = "com.tinder"
private const val SWIPPER = "com.swipper.app"
private const val OTHER = "com.android.chrome"
private const val SWIPE_MS = 300L

/** Une minute de temps virtuel. */
private const val MINUTE = 60_000L

/** Faux exécuteur : chaque swipe dure [SWIPE_MS] ms de temps virtuel puis renvoie [resultFor] (n° d'appel). */
private class FakeExecutor(private val now: () -> Long) : SwipeExecutor {
    var resultFor: (Int) -> DispatchResult = { DispatchResult.Completed }
    var failure: Throwable? = null
    val starts = mutableListOf<Long>()
    var completed = 0
    var cancelled = 0

    override suspend fun execute(direction: Direction): DispatchResult {
        starts += now()
        failure?.let { throw it }
        try {
            delay(SWIPE_MS)
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
        completed++
        return resultFor(starts.size - 1)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationControllerTest {

    /** Banc de test : contrôleur branché sur le temps virtuel de [test]. */
    private class Rig(test: TestScope, config: BehaviorConfig, initialForeground: String?, start: String) {
        val foreground = MutableStateFlow(initialForeground)
        val deviceUsable = MutableStateFlow(true)
        val executor = FakeExecutor { test.currentTime }
        val errors = mutableListOf<Throwable>()

        // Clock dérivée du temps virtuel : [start] (UTC) + currentTime.
        private val base = Instant.parse(start)
        private val clock = object : Clock {
            override fun now(): Instant = base.plusMillis(test.currentTime)
            override val zone: ZoneId = ZoneOffset.UTC
        }
        val engine = BehaviorEngine(config, Random(7), clock)
        // Rattaché à backgroundScope : annulé en fin de test même si une assertion échoue
        // (sinon le advanceUntilIdle final de runTest tournerait sans fin sur la boucle).
        val scope = CoroutineScope(
            test.backgroundScope.coroutineContext + SupervisorJob(test.backgroundScope.coroutineContext[Job]) +
                StandardTestDispatcher(test.testScheduler) +
                CoroutineExceptionHandler { _, e -> errors += e },
        )
        val controller = AutomationController(
            scope, engine, DirectionPicker(Random(11)), executor, foreground, clock, deviceUsable,
        )
    }

    private val noCap = BehaviorConfig(dailyCap = 1_000_000, sessionMin = 1_000, sessionMax = 1_000)

    private val withRest = BehaviorConfig(
        dailyCap = 1_000, sessionMin = 3, sessionMax = 3, restMinMs = 20 * MINUTE, restMaxMs = 20 * MINUTE,
    )

    private fun TestScope.rig(
        config: BehaviorConfig = noCap,
        foreground: String? = TINDER,
        start: String = "2026-06-15T10:00:00Z",
    ) = Rig(this, config, foreground, start)

    @Test
    fun `Demarrer dans Tinder - Running, swipes irreguliers, recordSwipe apres chaque Completed`() = runTest {
        val r = rig()
        r.controller.start()
        runCurrent()
        assertEquals(AutomationState.Running, r.controller.state.value)

        advanceTimeBy(2 * MINUTE)
        runCurrent()

        val starts = r.executor.starts
        assertTrue(starts.size >= 10, "seulement ${starts.size} swipes en 2 min")
        val gaps = starts.zipWithNext { a, b -> b - a }.toSet()
        assertTrue(gaps.size > 1, "intervalles constants : $gaps")
        assertEquals(r.executor.completed, r.engine.swipesToday)
        assertEquals(r.engine.swipesToday, r.controller.swipesToday.value)
        r.scope.cancel()
    }

    @Test
    fun `Demarrer hors cible - Paused sans swipe, puis Running quand Tinder arrive`() = runTest {
        val r = rig(foreground = SWIPPER)
        r.controller.start()
        assertEquals(AutomationState.Paused, r.controller.state.value)

        advanceTimeBy(5 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        assertTrue(r.executor.starts.isEmpty())

        r.foreground.value = TINDER
        runCurrent()
        assertEquals(AutomationState.Running, r.controller.state.value)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(r.executor.starts.isNotEmpty())
        r.scope.cancel()
    }

    @Test
    fun `Quitter l'app cible - Paused, geste en cours annule, aucun swipe avant le retour`() = runTest {
        val r = rig()
        r.controller.start()
        while (r.executor.starts.isEmpty()) advanceTimeBy(100)
        runCurrent()
        val countBefore = r.engine.swipesToday

        r.foreground.value = OTHER // pendant l'exécution du geste
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        assertEquals(1, r.executor.cancelled)
        assertEquals(countBefore, r.engine.swipesToday)

        val started = r.executor.starts.size
        advanceTimeBy(10 * MINUTE)
        runCurrent()
        assertEquals(started, r.executor.starts.size)

        r.foreground.value = TINDER
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(r.executor.starts.size > started)
        r.scope.cancel()
    }

    @Test
    fun `Stop - Idle immediatement, geste annule en moins de 500 ms, plus aucun geste`() = runTest {
        val r = rig()
        r.controller.start()
        while (r.executor.starts.isEmpty()) advanceTimeBy(100)
        runCurrent()

        val stoppedAt = currentTime
        r.controller.stop()
        assertEquals(AutomationState.Idle, r.controller.state.value)
        runCurrent() // aucun temps écoulé : l'annulation est immédiate
        assertEquals(stoppedAt, currentTime)
        assertEquals(1, r.executor.cancelled)

        val started = r.executor.starts.size
        advanceTimeBy(30 * MINUTE)
        runCurrent()
        assertEquals(started, r.executor.starts.size)
        assertEquals(AutomationState.Idle, r.controller.state.value)
        r.scope.cancel()
    }

    @Test
    fun `Pause manuelle puis Reprendre - aucun swipe pendant la pause, reprise ensuite`() = runTest {
        val r = rig()
        r.controller.start()
        advanceTimeBy(MINUTE)
        runCurrent()
        r.controller.pause()
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        assertTrue(r.controller.userPaused.value)

        val started = r.executor.starts.size
        advanceTimeBy(10 * MINUTE)
        runCurrent()
        assertEquals(started, r.executor.starts.size)
        // Revenir dans Tinder ne lève pas une pause manuelle.
        r.foreground.value = OTHER
        r.foreground.value = TINDER
        advanceTimeBy(MINUTE)
        runCurrent()
        assertEquals(started, r.executor.starts.size)

        r.controller.resume()
        runCurrent()
        assertEquals(AutomationState.Running, r.controller.state.value)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(r.executor.starts.size > started)
        r.scope.cancel()
    }

    @Test
    fun `Fin de session - Resting pendant la duree puis Running`() = runTest {
        val r = rig(withRest)
        r.controller.start()
        while (r.controller.state.value != AutomationState.Resting) advanceTimeBy(100)
        assertEquals(3, r.engine.swipesToday)
        val restStart = currentTime
        val started = r.executor.starts.size

        advanceTimeBy(20 * MINUTE - 200)
        runCurrent()
        assertEquals(AutomationState.Resting, r.controller.state.value)
        assertEquals(started, r.executor.starts.size)

        advanceTimeBy(300)
        runCurrent()
        assertEquals(AutomationState.Running, r.controller.state.value)
        assertTrue(currentTime - restStart >= 20 * MINUTE)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(r.executor.starts.size > started)
        r.scope.cancel()
    }

    @Test
    fun `Plafond - CapReached pendant l'attente, aucun swipe, puis Idle a l'ouverture du lendemain`() = runTest {
        val r = rig(BehaviorConfig(dailyCap = 2, sessionMin = 1_000, sessionMax = 1_000))
        r.controller.start()
        while (r.controller.state.value != AutomationState.CapReached) advanceTimeBy(100)
        assertEquals(2, r.engine.swipesToday)
        val started = r.executor.starts.size

        // Échéance affichée : lendemain 9 h + 2–45 min.
        val resumeAt = r.controller.resumeAt.value
        assertNotNull(resumeAt)
        assertTrue(
            resumeAt!! >= Instant.parse("2026-06-16T09:02:00Z") && resumeAt <= Instant.parse("2026-06-16T09:45:00Z"),
            "reprise à $resumeAt",
        )
        // 10:00 UTC + quelques secondes : reprise le lendemain à 9 h + 2–45 min, soit ≥ 23 h.
        advanceTimeBy(23 * 60 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.CapReached, r.controller.state.value)
        assertEquals(started, r.executor.starts.size)

        advanceTimeBy(46 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.Idle, r.controller.state.value)
        assertEquals(null, r.controller.resumeAt.value)
        assertEquals(started, r.executor.starts.size)
        r.scope.cancel()
    }

    @Test
    fun `Swipe refuse ou annule - pas de recordSwipe, la boucle continue`() = runTest {
        for (outcome in listOf(DispatchResult.Failed, DispatchResult.Cancelled)) {
            val r = rig()
            // 4 échecs, un succès (remise à zéro du compte), puis 4 échecs : jamais 5 d'affilée.
            r.executor.resultFor = { if (it == 4 || it >= 9) DispatchResult.Completed else outcome }
            r.controller.start()
            advanceTimeBy(2 * MINUTE)
            runCurrent()
            assertTrue(r.executor.completed >= 12, "$outcome : ${r.executor.completed} exécutions")
            assertEquals(r.executor.completed - 8, r.engine.swipesToday)
            assertEquals(r.engine.swipesToday, r.controller.swipesToday.value)
            assertEquals(AutomationState.Running, r.controller.state.value)
            assertEquals(null, r.controller.error.value)
            r.scope.cancel()
        }
    }

    @Test
    fun `5 swipes non aboutis d'affilee - Idle avec erreur`() = runTest {
        val r = rig()
        r.executor.resultFor = { DispatchResult.Failed }
        r.controller.start()
        advanceTimeBy(5 * MINUTE)
        runCurrent()

        assertEquals(AutomationState.Idle, r.controller.state.value)
        assertEquals(AutomationController.MAX_CONSECUTIVE_FAILURES, r.executor.starts.size)
        assertNotNull(r.controller.error.value)
        assertEquals(0, r.engine.swipesToday)
        assertTrue(r.errors.isEmpty())
        r.scope.cancel()
    }

    @Test
    fun `Ecran eteint ou verrouille - Paused, geste annule, reprise au deverrouillage`() = runTest {
        val r = rig()
        r.controller.start()
        while (r.executor.starts.isEmpty()) advanceTimeBy(100)
        runCurrent()

        r.deviceUsable.value = false
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        assertEquals(1, r.executor.cancelled)
        val started = r.executor.starts.size
        advanceTimeBy(10 * MINUTE)
        runCurrent()
        assertEquals(started, r.executor.starts.size)

        r.deviceUsable.value = true
        runCurrent()
        assertEquals(AutomationState.Running, r.controller.state.value)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(r.executor.starts.size > started)
        r.scope.cancel()
    }

    @Test
    fun `pause pendant Resting - reste Resting puis Paused a la fin de la pause longue`() = runTest {
        val r = rig(withRest)
        r.controller.start()
        while (r.controller.state.value != AutomationState.Resting) advanceTimeBy(100)
        val started = r.executor.starts.size

        r.controller.pause()
        runCurrent()
        assertEquals(AutomationState.Resting, r.controller.state.value)

        advanceTimeBy(21 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        assertTrue(r.controller.userPaused.value)
        assertEquals(started, r.executor.starts.size)
        r.scope.cancel()
    }

    @Test
    fun `stop pendant Resting - Idle, plus aucun geste`() = runTest {
        val r = rig(withRest)
        r.controller.start()
        while (r.controller.state.value != AutomationState.Resting) advanceTimeBy(100)
        val started = r.executor.starts.size

        r.controller.stop()
        assertEquals(AutomationState.Idle, r.controller.state.value)
        advanceTimeBy(60 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.Idle, r.controller.state.value)
        assertEquals(started, r.executor.starts.size)
        r.scope.cancel()
    }

    @Test
    fun `start pendant que la boucle tourne - sans effet`() = runTest {
        val r = rig()
        r.controller.start()
        while (r.executor.starts.isEmpty()) advanceTimeBy(100)
        runCurrent()

        r.controller.start()
        runCurrent()
        // Pas de seconde boucle : le geste en cours n'est ni annulé ni doublé.
        assertEquals(AutomationState.Running, r.controller.state.value)
        assertEquals(1, r.executor.starts.size)
        assertEquals(0, r.executor.cancelled)
        advanceTimeBy(SWIPE_MS)
        runCurrent()
        assertEquals(1, r.executor.completed)
        assertEquals(1, r.engine.swipesToday)
        r.scope.cancel()
    }

    @Test
    fun `minuit pendant Running sans plafond - swipesToday repasse a 0`() = runTest {
        // Plage 20 h–2 h : minuit est dans la plage horaire.
        val r = rig(noCap.copy(activeStartMinute = 20 * 60, activeEndMinute = 2 * 60), start = "2026-06-15T23:58:00Z")
        val values = mutableListOf<Int>()
        r.scope.launch { r.controller.swipesToday.collect { values += it } }
        r.controller.start()
        advanceTimeBy(2 * MINUTE - 1_000)
        runCurrent()
        val beforeMidnight = r.controller.swipesToday.value
        assertTrue(beforeMidnight > 0)

        advanceTimeBy(MINUTE)
        runCurrent()
        // Le compteur redescend : 0 au premier nextAction() du jour, ou directement 1 si le
        // swipe tiré avant minuit se termine après (recordSwipe bascule alors le jour).
        val drop = (1 until values.size).firstOrNull { values[it] < values[it - 1] }
        assertNotNull(drop, "pas de remise à zéro après minuit : $values")
        assertTrue(values[drop!!] <= 1, "reprise à ${values[drop]} au lieu de 0 : $values")
        assertEquals(r.engine.swipesToday, r.controller.swipesToday.value)
        r.scope.cancel()
    }

    @Test
    fun `Boucle hors plage - OutsideHours sans swipe, puis reprise seule a la fin de l'attente`() = runTest {
        val r = rig(start = "2026-06-15T06:00:00Z")
        r.controller.start()
        runCurrent()
        assertEquals(AutomationState.OutsideHours, r.controller.state.value)
        // Reprise vers 9 h + 2–45 min.
        val resumeAt = r.controller.resumeAt.value
        assertNotNull(resumeAt)
        assertTrue(
            resumeAt!! >= Instant.parse("2026-06-15T09:02:00Z") && resumeAt <= Instant.parse("2026-06-15T09:45:00Z"),
            "reprise à $resumeAt",
        )

        advanceTimeBy(3 * 60 * MINUTE + MINUTE)
        runCurrent()
        assertEquals(AutomationState.OutsideHours, r.controller.state.value)
        assertTrue(r.executor.starts.isEmpty())

        advanceTimeBy(45 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.Running, r.controller.state.value)
        assertEquals(null, r.controller.resumeAt.value)
        assertTrue(r.executor.starts.isNotEmpty())
        assertTrue(r.executor.starts.first() >= resumeAt.toEpochMilli() - Instant.parse("2026-06-15T06:00:00Z").toEpochMilli())
        r.scope.cancel()
    }

    @Test
    fun `delai de Swipe qui deborde la fermeture de la plage - aucun swipe, puis OutsideHours`() = runTest {
        // Délai fixe de 30 s lancé à 22:59:45 : il se termine après 23 h, fermeture de la plage.
        val config = noCap.copy(delayMinMs = 30_000, delayMedianMs = 30_000, delayMaxMs = 30_000, distractionProbability = 0.0)
        val r = rig(config, start = "2026-06-15T22:59:45Z")
        r.controller.start()
        runCurrent()
        assertEquals(AutomationState.Running, r.controller.state.value)

        advanceTimeBy(30_000 + 1)
        runCurrent()
        assertTrue(r.executor.starts.isEmpty(), "swipe exécuté après la fermeture : ${r.executor.starts}")
        assertEquals(AutomationState.OutsideHours, r.controller.state.value)
        assertEquals(0, r.engine.swipesToday)
        r.scope.cancel()
    }

    @Test
    fun `fin de OutsideHours hors app cible - Paused`() = runTest {
        val r = rig(start = "2026-06-15T06:00:00Z")
        r.controller.start()
        runCurrent()
        assertEquals(AutomationState.OutsideHours, r.controller.state.value)
        r.foreground.value = OTHER
        runCurrent()
        assertEquals(AutomationState.OutsideHours, r.controller.state.value)

        advanceTimeBy(4 * 60 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        assertTrue(r.executor.starts.isEmpty())

        r.foreground.value = TINDER
        advanceTimeBy(MINUTE)
        runCurrent()
        assertEquals(AutomationState.Running, r.controller.state.value)
        assertTrue(r.executor.starts.isNotEmpty())
        r.scope.cancel()
    }

    @Test
    fun `pause pendant OutsideHours - reste OutsideHours puis Paused a la fin de l'attente`() = runTest {
        val r = rig(start = "2026-06-15T06:00:00Z")
        r.controller.start()
        runCurrent()
        r.controller.pause()
        runCurrent()
        assertEquals(AutomationState.OutsideHours, r.controller.state.value)

        advanceTimeBy(4 * 60 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        assertTrue(r.controller.userPaused.value)
        assertTrue(r.executor.starts.isEmpty())

        r.controller.resume()
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(r.executor.starts.isNotEmpty())
        r.scope.cancel()
    }

    @Test
    fun `stop pendant OutsideHours - Idle, echeance effacee, plus aucun geste`() = runTest {
        val r = rig(start = "2026-06-15T06:00:00Z")
        r.controller.start()
        runCurrent()
        assertEquals(AutomationState.OutsideHours, r.controller.state.value)

        r.controller.stop()
        assertEquals(AutomationState.Idle, r.controller.state.value)
        assertEquals(null, r.controller.resumeAt.value)
        advanceTimeBy(5 * 60 * MINUTE)
        runCurrent()
        assertEquals(AutomationState.Idle, r.controller.state.value)
        assertTrue(r.executor.starts.isEmpty())
        r.scope.cancel()
    }

    @Test
    fun `Exception - boucle arretee en Idle, erreur exposee, gestionnaire appele`() = runTest {
        val r = rig()
        r.executor.failure = IllegalStateException("boom")
        r.controller.start()
        advanceTimeBy(MINUTE)
        runCurrent()

        assertEquals(AutomationState.Idle, r.controller.state.value)
        assertEquals("boom", r.controller.error.value)
        assertEquals(1, r.errors.size)
        assertTrue(r.errors.single() is IllegalStateException)
        assertEquals(1, r.executor.starts.size)
        assertFalse(r.controller.userPaused.value)

        // Le contrôleur reste utilisable : un nouveau Démarrer efface l'erreur.
        r.executor.failure = null
        r.controller.start()
        assertEquals(null, r.controller.error.value)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertNotNull(r.executor.starts.getOrNull(1))
        r.scope.cancel()
    }

    /** Horloge UTC dérivée du temps virtuel, à partir de 2026-06-15T10:00Z. */
    private fun TestScope.virtualClock() = object : Clock {
        override fun now(): Instant = Instant.parse("2026-06-15T10:00:00Z").plusMillis(currentTime)
        override val zone: ZoneId = ZoneOffset.UTC
    }

    private fun TestScope.childScope() = CoroutineScope(
        backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]) +
            StandardTestDispatcher(testScheduler),
    )

    /** Contrôleur dont chaque Démarrer construit un moteur [config] depuis [saved()] et signale l'état dans [reported]. */
    private fun TestScope.persistingController(
        config: BehaviorConfig,
        scope: CoroutineScope,
        executor: FakeExecutor,
        foreground: MutableStateFlow<String?>,
        saved: () -> BehaviorState?,
        reported: MutableList<BehaviorState>,
        allowed: () -> Set<String> = { setOf(TINDER) },
        ready: () -> Boolean = { true },
    ): AutomationController {
        val clock = virtualClock()
        return AutomationController(
            scope = scope,
            engine = BehaviorEngine(BehaviorConfig(), Random(1), clock),
            picker = DirectionPicker(Random(2)),
            executor = executor,
            foregroundPackage = foreground,
            clock = clock,
            setupProvider = {
                if (!ready()) {
                    null
                } else {
                    val targets = allowed()
                    SessionSetup(
                        BehaviorEngine(config, Random(3), clock, saved()),
                        DirectionPicker(Random(4), 0.6),
                        { pkg -> pkg in targets },
                    )
                }
            },
            onStateChanged = { reported += it },
        )
    }

    @Test
    fun `setupProvider appliqué à chaque Démarrer et état signalé après chaque swipe`() = runTest {
        val foreground = MutableStateFlow<String?>("com.bumble.app")
        val executor = FakeExecutor { currentTime }
        val scope = childScope()
        // État sauvegardé : 40 swipes aujourd'hui, session à 10 / 1000.
        val reported = mutableListOf<BehaviorState>()
        var saved: BehaviorState? =
            BehaviorState(40, java.time.LocalDate.of(2026, 6, 15), sessionSwipes = 10, sessionTarget = 1_000)
        var allowed = setOf(TINDER)
        val controller = persistingController(
            noCap, scope, executor, foreground, { reported.lastOrNull() ?: saved }, reported, { allowed },
        )

        // Bumble au premier plan mais seul Tinder est autorisé : Paused, aucun geste.
        controller.start()
        runCurrent()
        assertEquals(40, controller.swipesToday.value)
        assertEquals(AutomationState.Paused, controller.state.value)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(executor.starts.isEmpty())
        controller.stop()

        // Réglages changés (Bumble autorisé) : pris en compte au Démarrer suivant.
        allowed = setOf("com.bumble.app")
        controller.start()
        runCurrent()
        assertEquals(AutomationState.Running, controller.state.value)
        advanceTimeBy(MINUTE)
        runCurrent()
        val done = executor.completed
        assertTrue(done > 0)
        assertEquals(40 + done, controller.swipesToday.value)
        assertEquals(40 + done, reported.last().swipesToday)
        assertEquals(10 + done, reported.last().sessionSwipes)
        assertEquals(1_000, reported.last().sessionTarget)
        controller.stop()
        scope.cancel()

        // « Service tué puis relancé » : nouveau contrôleur, moteur construit depuis l'état sauvegardé seul.
        saved = reported.last()
        val scope2 = childScope()
        val executor2 = FakeExecutor { currentTime }
        val reported2 = mutableListOf<BehaviorState>()
        val restarted = persistingController(noCap, scope2, executor2, foreground, { saved }, reported2, { allowed })
        restarted.start()
        runCurrent()
        assertEquals(40 + done, restarted.swipesToday.value)
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(executor2.completed > 0)
        assertEquals(10 + done + executor2.completed, reported2.last().sessionSwipes, "session reprise")
        assertEquals(40 + done + executor2.completed, reported2.last().swipesToday)
        scope2.cancel()
    }

    @Test
    fun `fin de session signalée puis pause respectée par un contrôleur relancé`() = runTest {
        val config = BehaviorConfig(
            dailyCap = 1_000, sessionMin = 2, sessionMax = 2, restMinMs = 20 * MINUTE, restMaxMs = 20 * MINUTE,
        )
        val foreground = MutableStateFlow<String?>(TINDER)
        val scope = childScope()
        val executor = FakeExecutor { currentTime }
        val reported = mutableListOf<BehaviorState>()
        val controller = persistingController(config, scope, executor, foreground, { null }, reported)
        controller.start()
        var guard = 0
        while (controller.state.value != AutomationState.Resting) {
            advanceTimeBy(1_000)
            runCurrent()
            check(++guard < 600) { "jamais en pause" }
        }
        val atRest = reported.last()
        assertEquals(2, executor.completed)
        assertEquals(0, atRest.sessionSwipes)
        assertEquals(2, atRest.sessionTarget)
        assertEquals(2, atRest.swipesToday)
        assertNotNull(atRest.restUntil)
        controller.stop()
        scope.cancel()

        // Stop puis Démarrer 5 min plus tard (nouveau contrôleur) : toujours en pause.
        advanceTimeBy(5 * MINUTE)
        val scope2 = childScope()
        val executor2 = FakeExecutor { currentTime }
        val restarted = persistingController(config, scope2, executor2, foreground, { atRest }, mutableListOf())
        restarted.start()
        runCurrent()
        assertEquals(AutomationState.Resting, restarted.state.value)
        advanceTimeBy(14 * MINUTE)
        runCurrent()
        assertTrue(executor2.starts.isEmpty(), "swipe pendant la pause restaurée")
        advanceTimeBy(2 * MINUTE)
        runCurrent()
        assertTrue(executor2.starts.isNotEmpty(), "pas de reprise après la pause")
        scope2.cancel()
    }

    @Test
    fun `Démarrer avant la lecture des réglages - différé jusqu'à setupReady`() = runTest {
        val foreground = MutableStateFlow<String?>(TINDER)
        val scope = childScope()
        val executor = FakeExecutor { currentTime }
        var ready = false
        val controller = persistingController(noCap, scope, executor, foreground, { null }, mutableListOf(), ready = { ready })
        controller.start()
        runCurrent()
        assertEquals(AutomationState.Idle, controller.state.value)
        ready = true
        controller.setupReady()
        runCurrent()
        assertEquals(AutomationState.Running, controller.state.value)

        // Stop annule un Démarrer différé.
        controller.stop()
        ready = false
        controller.start()
        controller.stop()
        ready = true
        controller.setupReady()
        assertEquals(AutomationState.Idle, controller.state.value)
        scope.cancel()
    }

    /**
     * Contrôleur dont le moteur ouvre toujours le profil (lecture fixe de [readMs]) tant que
     * [calibrated] ; résultats du tap ([tapResult]) et du Retour ([backResult]) injectables.
     */
    private class ProfileRig(test: TestScope, readMs: Long, start: String = "2026-06-15T10:00:00Z") {
        var calibrated = true
        var tapResult = DispatchResult.Completed
        var backResult = true

        /** App mise au premier plan par le Retour (sortie de l'app) ; `null` = on reste. */
        var exitTo: String? = null
        val exits = mutableListOf<String>()
        val foreground = MutableStateFlow<String?>(TINDER)
        val executor = FakeExecutor { test.currentTime }
        val taps = mutableListOf<Pair<Long, String?>>()
        val backs = mutableListOf<Long>()
        private val clock = object : Clock {
            override fun now(): Instant = Instant.parse(start).plusMillis(test.currentTime)
            override val zone: ZoneId = ZoneOffset.UTC
        }
        val engine = BehaviorEngine(
            BehaviorConfig(
                dailyCap = 1_000_000, sessionMin = 1_000, sessionMax = 1_000,
                profileOpenProbability = 1.0, profileReadMinMs = readMs, profileReadMaxMs = readMs,
            ),
            Random(5), clock, profileAvailable = { calibrated },
        )
        val scope = CoroutineScope(
            test.backgroundScope.coroutineContext + SupervisorJob(test.backgroundScope.coroutineContext[Job]) +
                StandardTestDispatcher(test.testScheduler),
        )
        val controller = AutomationController(
            scope, engine, DirectionPicker(Random(11)), executor, foreground, clock,
            tapExecutor = { pkg ->
                taps += test.currentTime to pkg
                delay(TAP_MS)
                tapResult
            },
            goBack = {
                backs += test.currentTime
                exitTo?.let { foreground.value = it }
                backResult
            },
            onProfileBackExits = { exits += it },
        )
    }

    /** Laisse la boucle envoyer un Retour de plus, qui sort vers [to] si [exit]. */
    private fun TestScope.nextBack(r: ProfileRig, exit: Boolean, to: String = OTHER) {
        r.exitTo = if (exit) to else null
        val before = r.backs.size
        r.foreground.value = TINDER
        advanceUntil { r.backs.size > before }
    }

    /** Laisse la boucle finir le swipe qui suit le dernier Retour. */
    private fun TestScope.nextSwipe(r: ProfileRig) {
        val before = r.executor.completed
        advanceUntil { r.executor.completed > before }
    }

    @Test
    fun `sortie apres Retour - une seule - Paused, calibration gardee`() = runTest {
        val r = ProfileRig(this, readMs = 1_000)
        r.controller.start()
        nextBack(r, exit = true)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        assertTrue(r.exits.isEmpty())
        r.scope.cancel()
    }

    @Test
    fun `sortie apres Retour - deux de suite - calibration effacee une fois`() = runTest {
        val r = ProfileRig(this, readMs = 1_000)
        r.controller.start()
        nextBack(r, exit = true)
        nextBack(r, exit = true)
        runCurrent()
        assertEquals(listOf(TINDER), r.exits)
        // Compteur remis à 0 : une troisième sortie seule n'efface rien de plus.
        nextBack(r, exit = true)
        runCurrent()
        assertEquals(listOf(TINDER), r.exits)
        r.scope.cancel()
    }

    @Test
    fun `sortie apres Retour - non consecutives - rien d'efface`() = runTest {
        val r = ProfileRig(this, readMs = 1_000)
        r.controller.start()
        nextBack(r, exit = true)
        nextBack(r, exit = false)
        nextSwipe(r)
        nextBack(r, exit = true)
        runCurrent()
        assertTrue(r.exits.isEmpty(), "effacé malgré un Retour normal entre les sorties")
        r.scope.cancel()
    }

    @Test
    fun `sortie apres Retour - vers une autre app cible - pas de swipe, sortie comptee`() = runTest {
        val r = ProfileRig(this, readMs = 1_000)
        r.controller.start()
        nextBack(r, exit = true, to = BUMBLE)
        advanceTimeBy(1_600) // fin de l'attente après le Retour, Bumble au premier plan
        runCurrent()
        // Le cycle suivant (tap, lecture 1 s, Retour, attente) ne peut pas swiper avant 1,7 s.
        assertTrue(r.executor.starts.isEmpty(), "swipe dans Bumble après le Retour de Tinder")
        r.exitTo = null
        assertTrue(r.exits.isEmpty())
        nextBack(r, exit = true)
        runCurrent()
        assertEquals(listOf(TINDER), r.exits)
        r.scope.cancel()
    }

    @Test
    fun `sortie apres Retour - compteur vide a chaque Demarrer`() = runTest {
        val r = ProfileRig(this, readMs = 1_000)
        r.controller.start()
        nextBack(r, exit = true)
        r.controller.stop() // ex. recalibration
        r.controller.start()
        nextBack(r, exit = true)
        runCurrent()
        assertTrue(r.exits.isEmpty(), "sortie d'une session précédente comptée")
        r.scope.cancel()
    }

    @Test
    fun `sortie apres Retour - pause pendant l'attente - compteur inchange`() = runTest {
        val r = ProfileRig(this, readMs = 1_000)
        r.controller.start()
        nextBack(r, exit = true)
        nextBack(r, exit = false)
        r.controller.togglePause() // pendant l'attente de 0,6–1,5 s après le Retour
        runCurrent()
        val swipes = r.executor.completed
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(swipes, r.executor.completed, "swipe malgré la pause")
        r.controller.togglePause()
        runCurrent()
        // La pause n'a pas remis le compteur à 0 : la sortie suivante est la 2e de suite.
        nextBack(r, exit = true)
        runCurrent()
        assertEquals(listOf(TINDER), r.exits)
        r.scope.cancel()
    }

    @Test
    fun `OpenProfile - tap, lecture 3 s, Retour, 0,6–1,5 s, swipe, un seul recordSwipe`() = runTest {
        val r = ProfileRig(this, readMs = 3_000)
        r.controller.start()
        while (r.executor.completed == 0) {
            advanceTimeBy(10)
            runCurrent()
        }
        val (tapAt, pkg) = r.taps.first()
        assertEquals(TINDER, pkg)
        assertEquals(1, r.backs.size)
        assertEquals(tapAt + TAP_MS + 3_000, r.backs.single())
        val swipeAt = r.executor.starts.single()
        assertTrue(swipeAt - r.backs.single() in 600L..1_500L, "attente après Retour ${swipeAt - r.backs.single()}")
        assertEquals(1, r.engine.swipesToday)
        assertEquals(1, r.controller.swipesToday.value)
        r.scope.cancel()
    }

    @Test
    fun `OpenProfile - quitter l'app pendant la lecture - Paused, pas de Retour, pas de swipe`() = runTest {
        val r = ProfileRig(this, readMs = 3_000)
        r.controller.start()
        while (r.taps.isEmpty()) advanceTimeBy(10)
        advanceTimeBy(TAP_MS + 1_000) // en pleine lecture
        runCurrent()
        r.foreground.value = OTHER
        runCurrent()
        assertEquals(AutomationState.Paused, r.controller.state.value)
        advanceTimeBy(5 * MINUTE)
        runCurrent()
        assertTrue(r.backs.isEmpty(), "Retour envoyé hors de l'app cible")
        assertTrue(r.executor.starts.isEmpty(), "swipe hors de l'app cible")
        assertEquals(0, r.engine.swipesToday)
        r.scope.cancel()
    }

    @Test
    fun `OpenProfile non disponible - uniquement des swipes, jamais de tap`() = runTest {
        val r = ProfileRig(this, readMs = 3_000)
        r.calibrated = false
        r.controller.start()
        advanceTimeBy(2 * MINUTE)
        runCurrent()
        assertTrue(r.executor.completed > 5)
        assertTrue(r.taps.isEmpty())
        assertTrue(r.backs.isEmpty())
        r.scope.cancel()
    }

    /** Avance par pas de 10 ms jusqu'à [cond] (au plus 10 min). */
    private fun TestScope.advanceUntil(cond: () -> Boolean) {
        var guard = 0
        while (!cond()) {
            advanceTimeBy(10)
            runCurrent()
            check(++guard < 60_000) { "condition jamais atteinte" }
        }
    }

    @Test
    fun `OpenProfile - pause pendant la lecture puis reprise - Retour d'abord, puis swipe`() = runTest {
        val r = ProfileRig(this, readMs = 3_000)
        r.controller.start()
        advanceUntil { r.taps.isNotEmpty() }
        r.calibrated = false // la suite : swipes seulement
        advanceTimeBy(TAP_MS + 1_000)
        runCurrent()
        r.controller.pause()
        runCurrent()
        advanceTimeBy(5 * MINUTE)
        runCurrent()
        assertTrue(r.backs.isEmpty(), "Retour envoyé en pause")
        assertTrue(r.executor.starts.isEmpty())

        r.controller.resume()
        runCurrent()
        assertEquals(1, r.backs.size, "Retour dès la reprise")
        advanceUntil { r.executor.completed > 0 }
        val firstSwipe = r.executor.starts.first()
        assertTrue(firstSwipe - r.backs.single() >= 600, "swipe ${firstSwipe - r.backs.single()} ms après le Retour")
        assertEquals(1, r.taps.size)
        r.scope.cancel()
    }

    @Test
    fun `OpenProfile - Retour refusé - Idle avec erreur, plus aucun geste`() = runTest {
        val r = ProfileRig(this, readMs = 3_000)
        r.backResult = false
        r.controller.start()
        advanceUntil { r.backs.isNotEmpty() }
        runCurrent()
        assertEquals(AutomationState.Idle, r.controller.state.value)
        assertEquals(AutomationController.BACK_REFUSED_ERROR, r.controller.error.value)
        advanceTimeBy(5 * MINUTE)
        runCurrent()
        assertEquals(1, r.taps.size)
        assertEquals(1, r.backs.size)
        assertTrue(r.executor.starts.isEmpty())
        r.scope.cancel()
    }

    @Test
    fun `OpenProfile - passage à Bumble pendant le délai - pas de tap, swipe normal`() = runTest {
        val r = ProfileRig(this, readMs = 3_000)
        r.controller.start()
        runCurrent() // décision prise pour Tinder, délai en cours
        r.foreground.value = BUMBLE
        r.calibrated = false
        advanceUntil { r.executor.completed > 0 }
        assertTrue(r.taps.isEmpty(), "tap après changement d'app : ${r.taps}")
        assertTrue(r.backs.isEmpty())
        assertEquals(1, r.engine.swipesToday)
        r.scope.cancel()
    }

    @Test
    fun `OpenProfile - passage à Bumble pendant la lecture - pas de Retour dans Bumble, Retour au retour dans Tinder`() =
        runTest {
            val r = ProfileRig(this, readMs = 3_000)
            r.controller.start()
            advanceUntil { r.taps.isNotEmpty() }
            r.calibrated = false
            advanceTimeBy(TAP_MS + 1_000)
            runCurrent()
            r.foreground.value = BUMBLE
            advanceTimeBy(2 * MINUTE)
            runCurrent()
            assertTrue(r.backs.isEmpty(), "Retour envoyé dans Bumble")
            assertTrue(r.executor.completed > 0, "la boucle continue dans Bumble")

            r.foreground.value = TINDER
            runCurrent()
            val startsAtReturn = r.executor.starts.size
            advanceUntil { r.backs.isNotEmpty() }
            assertEquals(startsAtReturn, r.executor.starts.size, "geste dans Tinder avant le Retour")
            val back = r.backs.single()
            advanceUntil { r.executor.starts.any { it > back } }
            assertTrue(r.executor.starts.first { it > back } - back >= 600)
            r.scope.cancel()
        }

    @Test
    fun `OpenProfile - tap non abouti - swipe normal, sans arrêt pour échecs`() = runTest {
        val r = ProfileRig(this, readMs = 3_000)
        r.tapResult = DispatchResult.Failed
        r.controller.start()
        advanceTimeBy(3 * MINUTE)
        runCurrent()
        assertTrue(r.taps.size > AutomationController.MAX_CONSECUTIVE_FAILURES, "${r.taps.size} taps")
        assertTrue(r.backs.isEmpty())
        assertEquals(AutomationState.Running, r.controller.state.value)
        assertEquals(null, r.controller.error.value)
        assertEquals(r.executor.completed, r.engine.swipesToday)
        assertTrue(r.executor.completed >= r.taps.size - 1)
        r.scope.cancel()
    }

    @Test
    fun `OpenProfile qui déborde la fin de plage - aucun tap ni swipe après la fermeture, puis OutsideHours`() = runTest {
        // Plage 9 h–23 h (UTC) ; 23:00 = 2 000 ms de temps virtuel.
        val r = ProfileRig(this, readMs = 3_000, start = "2026-06-15T22:59:58Z")
        r.controller.start()
        advanceUntil { r.controller.state.value == AutomationState.OutsideHours }
        assertTrue(r.taps.all { it.first < 2_000 }, "tap après la fermeture : ${r.taps}")
        assertTrue(r.executor.starts.isEmpty(), "swipe après la fermeture : ${r.executor.starts}")
        r.scope.cancel()
    }

    @Test
    fun `OpenProfile - Stop pendant la lecture puis Démarrer dans la même app - Retour d'abord, puis swipe`() = runTest {
        val r = ProfileRig(this, readMs = 3_000)
        r.controller.start()
        advanceUntil { r.taps.isNotEmpty() }
        r.calibrated = false
        advanceTimeBy(TAP_MS + 1_000)
        runCurrent()
        r.controller.stop()
        advanceTimeBy(MINUTE)
        runCurrent()
        assertTrue(r.backs.isEmpty(), "Retour envoyé après Stop")

        r.controller.start()
        runCurrent()
        assertEquals(1, r.backs.size, "Retour dès le Démarrer")
        assertTrue(r.executor.starts.isEmpty())
        advanceUntil { r.executor.completed > 0 }
        assertTrue(r.executor.starts.first() - r.backs.single() >= 600)
        r.scope.cancel()
    }
}

/** Paquet de Bumble. */
private const val BUMBLE = "com.bumble.app"

/** Durée d'un tap du faux exécuteur. */
private const val TAP_MS = 100L
