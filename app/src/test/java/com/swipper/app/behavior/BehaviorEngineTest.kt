package com.swipper.app.behavior

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Horloge simulée : avance seulement quand le test le demande. */
private class FakeClock(var instant: Instant, override val zone: ZoneId = PARIS) : Clock {
    override fun now(): Instant = instant
    fun advance(ms: Long) {
        instant = instant.plusMillis(ms)
    }
}

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

private fun paris(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Instant =
    LocalDateTime.of(y, mo, d, h, mi).atZone(PARIS).toInstant()

class BehaviorEngineTest {

    private val defaults = BehaviorConfig()

    /** Config sans plafond gênant, pour observer délais et sessions. */
    private val noCap = BehaviorConfig(dailyCap = 1_000_000)

    @Test
    fun `délais irréguliers sur 1 000 actions Swipe`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(noCap, Random(42), clock)
        val delays = mutableListOf<Long>()
        while (delays.size < 1000) {
            when (val a = engine.nextAction()) {
                is Action.Swipe -> {
                    delays += a.delayMs
                    clock.advance(a.delayMs)
                    engine.recordSwipe()
                }
                is Action.Rest -> clock.advance(a.durationMs)
                is Action.CapReached -> error("plafond inattendu")
            }
        }
        assertTrue(delays.all { it in 800L..40_000L }, "délai hors [800 ; 40 000] : ${delays.filter { it !in 800L..40_000L }}")
        assertTrue(delays.toSet().size >= 100, "seulement ${delays.toSet().size} valeurs distinctes")
        val normal = delays.filter { it < 10_000 }.sorted()
        val median = normal[normal.size / 2]
        assertTrue(median in 1_400L..2_300L, "médiane hors distractions = $median")
        val distractions = delays.count { it >= 10_000 }
        assertTrue(distractions in 20..80, "distractions = $distractions / 1000")
        assertTrue(normal.all { it in 800L..8_000L })
    }

    @Test
    fun `session bornée et pauses longues de 15 à 90 min`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(noCap, Random(7), clock)
        var inSession = 0
        var rests = 0
        repeat(5_000) {
            when (val a = engine.nextAction()) {
                is Action.Swipe -> {
                    clock.advance(a.delayMs)
                    engine.recordSwipe()
                    inSession++
                    assertTrue(inSession <= noCap.sessionMax, "session de $inSession swipes")
                }
                is Action.Rest -> {
                    assertTrue(inSession in noCap.sessionMin..noCap.sessionMax, "session de $inSession swipes")
                    assertTrue(a.durationMs in 15 * 60_000L..90 * 60_000L, "pause de ${a.durationMs} ms")
                    clock.advance(a.durationMs)
                    inSession = 0
                    rests++
                }
                is Action.CapReached -> error("plafond inattendu")
            }
        }
        assertTrue(rests >= 20, "seulement $rests pauses")
    }

    @Test
    fun `session coupée par le plafond peut être plus courte que sessionMin`() {
        val config = BehaviorConfig(dailyCap = 10)
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(config, Random(1), clock)
        var swipes = 0
        while (true) {
            val a = engine.nextAction()
            if (a is Action.CapReached) break
            assertTrue(a is Action.Swipe, "action inattendue $a")
            engine.recordSwipe()
            swipes++
        }
        assertEquals(10, swipes)
    }

    @Test
    fun `plafond de 150 swipes renvoie CapReached jusqu'au prochain minuit local`() {
        val clock = FakeClock(paris(2026, 6, 15, 14, 30))
        val engine = BehaviorEngine(defaults, Random(5), clock)
        repeat(150) { engine.recordSwipe() }
        assertEquals(150, engine.swipesToday)
        val a = engine.nextAction()
        assertEquals(Action.CapReached(Duration.ofHours(9).plusMinutes(30).toMillis()), a)
        // nextAction ne change pas le compteur et reste au plafond.
        assertEquals(150, engine.swipesToday)
        assertTrue(engine.nextAction() is Action.CapReached)
    }

    @Test
    fun `nextAction ne modifie pas le compteur de swipes`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(defaults, Random(5), clock)
        repeat(100) { engine.nextAction() }
        assertEquals(0, engine.swipesToday)
    }

    @Test
    fun `nouveau jour remet le compteur à 0 et autorise de nouveau un Swipe`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(defaults, Random(11), clock)
        var cap: Action.CapReached? = null
        while (cap == null) {
            when (val a = engine.nextAction()) {
                is Action.Swipe -> {
                    clock.advance(a.delayMs)
                    engine.recordSwipe()
                }
                is Action.Rest -> clock.advance(a.durationMs)
                is Action.CapReached -> cap = a
            }
        }
        assertEquals(150, engine.swipesToday)
        assertEquals(LocalDate.of(2026, 6, 15), engine.day)
        clock.advance(cap.waitMs)
        val next = engine.nextAction()
        assertEquals(0, engine.swipesToday)
        assertEquals(LocalDate.of(2026, 6, 16), engine.day)
        assertTrue(next is Action.Swipe, "attendu Swipe, reçu $next")
        engine.recordSwipe()
        assertEquals(1, engine.swipesToday)
    }

    @Test
    fun `horloge reculée d'un jour après le plafond garde le compteur`() {
        val clock = FakeClock(paris(2026, 6, 15, 14))
        val engine = BehaviorEngine(defaults, Random(5), clock)
        repeat(150) { engine.recordSwipe() }
        clock.instant = paris(2026, 6, 14, 14)
        assertTrue(engine.nextAction() is Action.CapReached)
        assertEquals(BehaviorState(150, LocalDate.of(2026, 6, 15)), engine.state)
    }

    @Test
    fun `recordSwipe après minuit compte sur le nouveau jour`() {
        val clock = FakeClock(paris(2026, 6, 15, 23, 59))
        val engine = BehaviorEngine(defaults, Random(5), clock, BehaviorState(10, LocalDate.of(2026, 6, 15)))
        engine.nextAction()
        clock.advance(2 * 60_000L)
        engine.recordSwipe()
        assertEquals(BehaviorState(1, LocalDate.of(2026, 6, 16)), engine.state)
    }

    @Test
    fun `aucune valeur de délai ne dépasse 1 % des 1 000 swipes`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(noCap, Random(42), clock)
        val delays = mutableListOf<Long>()
        while (delays.size < 1000) {
            when (val a = engine.nextAction()) {
                is Action.Swipe -> {
                    delays += a.delayMs
                    engine.recordSwipe()
                }
                is Action.Rest -> Unit
                is Action.CapReached -> error("plafond inattendu")
            }
        }
        val worst = delays.groupingBy { it }.eachCount().maxBy { it.value }
        assertTrue(worst.value <= 10, "délai ${worst.key} répété ${worst.value} fois")
    }

    @Test
    fun `sigma élevé garde tous les délais dans les bornes`() {
        val config = BehaviorConfig(delaySigma = 3.0, distractionProbability = 0.0, dailyCap = 1_000_000)
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(config, Random(3), clock)
        var n = 0
        while (n < 1000) {
            val a = engine.nextAction()
            if (a is Action.Swipe) {
                assertTrue(a.delayMs in config.delayMinMs..config.delayMaxMs, "délai ${a.delayMs}")
                engine.recordSwipe()
                n++
            }
        }
    }

    @Test
    fun `heure d'été jour de 23 h et heure d'hiver jour de 25 h`() {
        // 29/03/2026 : passage à l'heure d'été (jour de 23 h) ; 25/10/2026 : heure d'hiver (25 h).
        for ((start, expected) in listOf(
            paris(2026, 3, 29) to Duration.ofHours(23),
            paris(2026, 10, 25) to Duration.ofHours(25),
            paris(2026, 3, 28, 22) to Duration.ofHours(2),
        )) {
            val clock = FakeClock(start)
            val engine = BehaviorEngine(BehaviorConfig(dailyCap = 1), Random(0), clock)
            engine.recordSwipe()
            assertEquals(Action.CapReached(expected.toMillis()), engine.nextAction(), "départ $start")
        }
    }

    @Test
    fun `état initial du jour est conservé et celui de la veille remis à zéro`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val today = BehaviorEngine(defaults, Random(0), clock, BehaviorState(150, LocalDate.of(2026, 6, 15)))
        assertEquals(BehaviorState(150, LocalDate.of(2026, 6, 15)), today.state)
        assertTrue(today.nextAction() is Action.CapReached)

        val yesterday = BehaviorEngine(defaults, Random(0), clock, BehaviorState(150, LocalDate.of(2026, 6, 14)))
        assertTrue(yesterday.nextAction() is Action.Swipe)
        assertEquals(BehaviorState(0, LocalDate.of(2026, 6, 15)), yesterday.state)
        assertThrows<IllegalArgumentException> { BehaviorState(-1, LocalDate.of(2026, 6, 15)) }
    }

    @Test
    fun `même graine et même horloge donnent les mêmes actions`() {
        fun run(): List<Action> {
            val clock = FakeClock(paris(2026, 6, 15, 9))
            val engine = BehaviorEngine(defaults, Random(99), clock)
            return List(400) {
                engine.nextAction().also { a ->
                    when (a) {
                        is Action.Swipe -> {
                            clock.advance(a.delayMs)
                            engine.recordSwipe()
                        }
                        is Action.Rest -> clock.advance(a.durationMs)
                        is Action.CapReached -> clock.advance(a.waitMs)
                    }
                }
            }
        }
        assertEquals(run(), run())
    }

    @Test
    fun `config invalide lève IllegalArgumentException`() {
        val invalid = listOf<() -> BehaviorConfig>(
            { BehaviorConfig(distractionProbability = -0.1) },
            { BehaviorConfig(distractionProbability = 1.1) },
            { BehaviorConfig(distractionProbability = Double.NaN) },
            { BehaviorConfig(delayMinMs = 9_000, delayMaxMs = 8_000) },
            { BehaviorConfig(distractionMinMs = 50_000, distractionMaxMs = 40_000) },
            { BehaviorConfig(sessionMin = 61, sessionMax = 60) },
            { BehaviorConfig(restMinMs = 100 * 60_000L, restMaxMs = 90 * 60_000L) },
            { BehaviorConfig(delayMedianMs = 0) },
            { BehaviorConfig(delaySigma = 0.0) },
            { BehaviorConfig(delaySigma = Double.NaN) },
            { BehaviorConfig(delaySigma = Double.POSITIVE_INFINITY) },
            { BehaviorConfig(delayMedianMs = 500) },
            { BehaviorConfig(delayMinMs = 0) },
            { BehaviorConfig(distractionMinMs = -1) },
            { BehaviorConfig(sessionMin = 0) },
            { BehaviorConfig(restMinMs = 0) },
            { BehaviorConfig(dailyCap = 0) },
            { BehaviorConfig(delayMedianMs = 9_000) },
        )
        for (make in invalid) {
            val e = assertThrows<IllegalArgumentException> { make() }
            assertTrue(!e.message.isNullOrBlank())
        }
    }

    @Test
    fun `packages decision et behavior sans import Android`() {
        val root = File("src/main/java/com/swipper/app")
        val files = listOf("decision", "behavior").flatMap { File(root, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue(files.isNotEmpty(), "aucun fichier trouvé sous ${root.absolutePath}")
        val android = Regex("""\bandroidx?\.""")
        for (f in files) {
            val code = f.readLines().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }
            assertTrue(code.none { android.containsMatchIn(it) }, "${f.name} référence Android")
        }
    }
}
