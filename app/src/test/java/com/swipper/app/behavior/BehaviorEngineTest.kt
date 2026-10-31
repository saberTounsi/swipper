package com.swipper.app.behavior

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
                is Action.OpenProfile -> error("ouverture de profil inattendue")
                is Action.OutsideHours -> clock.advance(a.waitMs)
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
    fun `pause longue à 0 par défaut - nouvelle session sans attente`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(noCap, Random(7), clock)
        var rests = 0
        repeat(500) {
            when (val a = engine.nextAction()) {
                is Action.Swipe -> {
                    clock.advance(a.delayMs)
                    engine.recordSwipe()
                }
                is Action.Rest -> {
                    assertEquals(0L, a.durationMs)
                    rests++
                }
                else -> error("action inattendue : $a")
            }
        }
        assertTrue(rests > 0, "aucune fin de session")
        assertEquals(null, engine.restUntil?.takeIf { it.isAfter(clock.now()) })
    }

    @Test
    fun `session bornée et pauses longues de 15 à 90 min`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val config = noCap.copy(restMinMs = 15 * 60_000L, restMaxMs = 90 * 60_000L)
        val engine = BehaviorEngine(config, Random(7), clock)
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
                is Action.OpenProfile -> error("ouverture de profil inattendue")
                is Action.OutsideHours -> {
                    // Le moteur clôt la session hors plage : on vérifie au lieu de supposer.
                    assertEquals(0, engine.sessionSwipes)
                    clock.advance(a.waitMs)
                    inSession = engine.sessionSwipes
                }
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
    fun `plafond de 150 swipes renvoie CapReached jusqu'à l'ouverture du lendemain`() {
        val clock = FakeClock(paris(2026, 6, 15, 14, 30))
        val engine = BehaviorEngine(defaults.copy(resumeJitterMinMs = 0, resumeJitterMaxMs = 0), Random(5), clock)
        repeat(150) { engine.recordSwipe() }
        assertEquals(150, engine.swipesToday)
        val a = engine.nextAction()
        // Lendemain 9 h, et non plus minuit.
        assertEquals(Action.CapReached(Duration.ofHours(18).plusMinutes(30).toMillis()), a)
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
                is Action.OpenProfile -> error("ouverture de profil inattendue")
                is Action.OutsideHours -> clock.advance(a.waitMs)
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
        assertEquals(150 to LocalDate.of(2026, 6, 15), engine.state.let { it.swipesToday to it.day })
    }

    @Test
    fun `recordSwipe après minuit compte sur le nouveau jour`() {
        val clock = FakeClock(paris(2026, 6, 15, 23, 59))
        val engine = BehaviorEngine(defaults, Random(5), clock, BehaviorState(10, LocalDate.of(2026, 6, 15)))
        engine.nextAction()
        clock.advance(2 * 60_000L)
        engine.recordSwipe()
        assertEquals(1 to LocalDate.of(2026, 6, 16), engine.state.let { it.swipesToday to it.day })
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
                is Action.OpenProfile -> error("ouverture de profil inattendue")
                is Action.OutsideHours -> error("hors plage inattendu")
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
    fun `plafond - heure d'été jour de 23 h et heure d'hiver jour de 25 h`() {
        // 29/03/2026 : passage à l'heure d'été (jour de 23 h) ; 25/10/2026 : heure d'hiver (25 h).
        // Attente jusqu'au 9 h suivant le prochain minuit, plus un décalage fixe de 5 min.
        val jitter = Duration.ofMinutes(5)
        for ((start, expected) in listOf(
            paris(2026, 3, 29) to Duration.ofHours(23 + 9).plus(jitter),
            paris(2026, 10, 25) to Duration.ofHours(25 + 9).plus(jitter),
            paris(2026, 3, 28, 22) to Duration.ofHours(2 + 8).plus(jitter),
        )) {
            val clock = FakeClock(start)
            val config = BehaviorConfig(dailyCap = 1, resumeJitterMinMs = jitter.toMillis(), resumeJitterMaxMs = jitter.toMillis())
            val engine = BehaviorEngine(config, Random(0), clock)
            engine.recordSwipe()
            assertEquals(Action.CapReached(expected.toMillis()), engine.nextAction(), "départ $start")
        }
    }

    @Test
    fun `état initial du jour est conservé et celui de la veille remis à zéro`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val today = BehaviorEngine(defaults, Random(0), clock, BehaviorState(150, LocalDate.of(2026, 6, 15)))
        assertEquals(150 to LocalDate.of(2026, 6, 15), today.state.let { it.swipesToday to it.day })
        assertTrue(today.nextAction() is Action.CapReached)

        val yesterday = BehaviorEngine(defaults, Random(0), clock, BehaviorState(150, LocalDate.of(2026, 6, 14)))
        assertTrue(yesterday.nextAction() is Action.Swipe)
        assertEquals(0 to LocalDate.of(2026, 6, 15), yesterday.state.let { it.swipesToday to it.day })
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
                        is Action.OpenProfile -> error("ouverture de profil inattendue")
                        is Action.OutsideHours -> clock.advance(a.waitMs)
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
            { BehaviorConfig(restMinMs = -1) },
            { BehaviorConfig(dailyCap = 0) },
            { BehaviorConfig(delayMedianMs = 9_000) },
            { BehaviorConfig(activeStartMinute = -1) },
            { BehaviorConfig(activeStartMinute = 1440) },
            { BehaviorConfig(activeEndMinute = -1) },
            { BehaviorConfig(activeEndMinute = 1440) },
            { BehaviorConfig(activeStartMinute = 600, activeEndMinute = 600) },
            { BehaviorConfig(resumeJitterMinMs = -1) },
            { BehaviorConfig(resumeJitterMinMs = -5, resumeJitterMaxMs = -1) },
            { BehaviorConfig(resumeJitterMinMs = 10 * 60_000L, resumeJitterMaxMs = 5 * 60_000L) },
        )
        for (make in invalid) {
            val e = assertThrows<IllegalArgumentException> { make() }
            assertTrue(!e.message.isNullOrBlank())
        }
    }

    // --- Plage horaire d'activité et reprise aléatoire ---

    private val minute = 60_000L

    /** Vérifie que [a] est un OutsideHours dont l'attente vaut [base] + un décalage de 2–45 min. */
    private fun assertOutsideHours(a: Action, base: Duration) {
        assertTrue(a is Action.OutsideHours, "attendu OutsideHours, reçu $a")
        val wait = (a as Action.OutsideHours).waitMs
        val lo = base.toMillis() + 2 * minute
        val hi = base.toMillis() + 45 * minute
        assertTrue(wait in lo..hi, "attente $wait hors [$lo ; $hi]")
    }

    @Test
    fun `dans la plage - comportement inchangé`() {
        val clock = FakeClock(paris(2026, 6, 15, 14))
        val engine = BehaviorEngine(defaults, Random(3), clock)
        repeat(20) {
            val a = engine.nextAction()
            assertTrue(a is Action.Swipe, "attendu Swipe, reçu $a")
            engine.recordSwipe()
        }
        // Bornes : 9 h incluse, 23 h exclue.
        assertTrue(engine.isInActiveHours(LocalTime.of(9, 0)))
        assertTrue(engine.isInActiveHours(LocalTime.of(22, 59)))
        assertTrue(!engine.isInActiveHours(LocalTime.of(23, 0)))
        assertTrue(!engine.isInActiveHours(LocalTime.of(8, 59)))
    }

    @Test
    fun `avant la plage - OutsideHours jusqu'à 9 h plus 2 à 45 min`() {
        val clock = FakeClock(paris(2026, 6, 15, 7, 30))
        val engine = BehaviorEngine(defaults, Random(3), clock)
        assertOutsideHours(engine.nextAction(), Duration.ofMinutes(90))
        assertEquals(0, engine.swipesToday)
    }

    @Test
    fun `après la plage - OutsideHours jusqu'au lendemain 9 h plus décalage`() {
        val clock = FakeClock(paris(2026, 6, 15, 23, 10))
        val engine = BehaviorEngine(defaults, Random(3), clock)
        val a = engine.nextAction()
        assertOutsideHours(a, Duration.ofHours(9).plusMinutes(50))
        clock.advance((a as Action.OutsideHours).waitMs)
        assertTrue(engine.nextAction() is Action.Swipe)
    }

    @Test
    fun `reprise variée - 100 attentes distinctes, jamais pile à 9 h`() {
        val clock = FakeClock(paris(2026, 6, 15, 7, 30))
        val engine = BehaviorEngine(defaults, Random(21), clock)
        val waits = List(100) { (engine.nextAction() as Action.OutsideHours).waitMs }
        assertTrue(waits.toSet().size >= 50, "seulement ${waits.toSet().size} valeurs distinctes")
        assertTrue(waits.none { it == 90 * minute }, "reprise pile à 9 h 00")
        assertTrue(waits.all { it > 90 * minute })
    }

    @Test
    fun `plage à cheval sur minuit - 1 h dans la plage, 3 h hors plage jusqu'à 20 h`() {
        val config = BehaviorConfig(activeStartMinute = 20 * 60, activeEndMinute = 2 * 60)
        val clock = FakeClock(paris(2026, 6, 15, 1))
        val engine = BehaviorEngine(config, Random(3), clock)
        assertTrue(engine.nextAction() is Action.Swipe)
        clock.instant = paris(2026, 6, 15, 3)
        assertOutsideHours(engine.nextAction(), Duration.ofHours(17))
        clock.instant = paris(2026, 6, 15, 21)
        assertTrue(engine.nextAction() is Action.Swipe)
    }

    @Test
    fun `décalage de reprise borné à 1 min avant la fermeture de la plage`() {
        // Plage de 10 min (9 h–9 h 10), plus courte que le décalage maximal de 45 min :
        // reprise entre 9 h 02 et 9 h 09.
        val config = BehaviorConfig(activeStartMinute = 9 * 60, activeEndMinute = 9 * 60 + 10)
        val clock = FakeClock(paris(2026, 6, 15, 8))
        val engine = BehaviorEngine(config, Random(3), clock)
        repeat(200) {
            val wait = (engine.nextAction() as Action.OutsideHours).waitMs
            assertTrue(wait in 62 * minute..69 * minute, "attente $wait hors de [9 h 02 ; 9 h 09]")
        }
    }

    @Test
    fun `hors plage le même jour - nouvelle session à la reprise, pas de Rest juste après`() {
        val config = BehaviorConfig(
            dailyCap = 1_000, sessionMin = 10, sessionMax = 10,
            activeStartMinute = 20 * 60, activeEndMinute = 2 * 60,
        )
        val clock = FakeClock(paris(2026, 6, 15, 1))
        val engine = BehaviorEngine(config, Random(3), clock)
        repeat(9) {
            assertTrue(engine.nextAction() is Action.Swipe)
            engine.recordSwipe()
        }
        clock.instant = paris(2026, 6, 15, 3)
        val out = engine.nextAction()
        assertTrue(out is Action.OutsideHours, "attendu OutsideHours, reçu $out")
        clock.advance((out as Action.OutsideHours).waitMs) // même jour, vers 20 h
        repeat(10) {
            val a = engine.nextAction()
            assertTrue(a is Action.Swipe, "swipe ${it + 1} de la nouvelle session : $a")
            engine.recordSwipe()
        }
        assertTrue(engine.nextAction() is Action.Rest)
    }

    @Test
    fun `isInActiveHours sur un instant suit le fuseau de l'horloge`() {
        val engine = BehaviorEngine(defaults, Random(3), FakeClock(paris(2026, 6, 15, 12)))
        assertTrue(engine.isInActiveHours(paris(2026, 6, 15, 9)))
        assertTrue(!engine.isInActiveHours(paris(2026, 6, 15, 23)))
    }

    @Test
    fun `plafond atteint à 15 h - CapReached jusqu'au lendemain 9 h plus décalage`() {
        val clock = FakeClock(paris(2026, 6, 15, 15))
        val engine = BehaviorEngine(defaults, Random(3), clock)
        repeat(defaults.dailyCap) { engine.recordSwipe() }
        val a = engine.nextAction()
        assertTrue(a is Action.CapReached, "attendu CapReached, reçu $a")
        val wait = (a as Action.CapReached).waitMs
        val base = Duration.ofHours(18).toMillis()
        assertTrue(wait in base + 2 * minute..base + 45 * minute, "attente $wait")
        // Le plafond est contrôlé avant la plage horaire.
        clock.instant = paris(2026, 6, 15, 23, 30)
        assertTrue(engine.nextAction() is Action.CapReached)
    }

    @Test
    fun `plafond avec plage à cheval sur minuit - reprise à minuit plus décalage`() {
        // 20 h–2 h : minuit est dans la plage et le compteur repart à zéro à minuit.
        val config = BehaviorConfig(dailyCap = 1, activeStartMinute = 20 * 60, activeEndMinute = 2 * 60)
        val clock = FakeClock(paris(2026, 6, 15, 21))
        val engine = BehaviorEngine(config, Random(3), clock)
        engine.recordSwipe()
        val wait = (engine.nextAction() as Action.CapReached).waitMs
        assertTrue(wait in 3 * 60 * minute + 2 * minute..3 * 60 * minute + 45 * minute, "attente $wait")
    }

    @Test
    fun `heure d'été - attente hors plage comptée en durée réelle`() {
        // Veille du passage à l'heure d'été : 23 h 30 → 9 h = 8 h 30 réelles (et non 9 h 30).
        val spring = BehaviorEngine(defaults, Random(3), FakeClock(paris(2026, 3, 28, 23, 30)))
        assertOutsideHours(spring.nextAction(), Duration.ofHours(8).plusMinutes(30))
        // Veille du passage à l'heure d'hiver : 10 h 30 réelles.
        val autumn = BehaviorEngine(defaults, Random(3), FakeClock(paris(2026, 10, 24, 23, 30)))
        assertOutsideHours(autumn.nextAction(), Duration.ofHours(10).plusMinutes(30))
    }

    @Test
    fun `config de plage invalide lève IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { BehaviorConfig(activeStartMinute = 1440) }
        assertThrows<IllegalArgumentException> { BehaviorConfig(activeStartMinute = -1) }
        assertThrows<IllegalArgumentException> { BehaviorConfig(activeEndMinute = -1) }
        assertThrows<IllegalArgumentException> { BehaviorConfig(activeEndMinute = 1440) }
        assertThrows<IllegalArgumentException> { BehaviorConfig(activeStartMinute = 540, activeEndMinute = 540) }
        assertThrows<IllegalArgumentException> { BehaviorConfig(resumeJitterMinMs = -1) }
        assertThrows<IllegalArgumentException> { BehaviorConfig(resumeJitterMinMs = 3, resumeJitterMaxMs = 2) }
        // Plage à cheval sur minuit et décalage nul acceptés.
        BehaviorConfig(activeStartMinute = 20 * 60, activeEndMinute = 2 * 60, resumeJitterMinMs = 0, resumeJitterMaxMs = 0)
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

    @Test
    fun `session restaurée reprend là où elle en était`() {
        val clock = FakeClock(paris(2026, 6, 15, 10))
        val config = BehaviorConfig(sessionMin = 25, sessionMax = 60)
        val saved = BehaviorState(40, LocalDate.of(2026, 6, 15), sessionSwipes = 30, sessionTarget = 32)
        val engine = BehaviorEngine(config, Random(1), clock, saved)
        assertEquals(saved, engine.state)
        repeat(2) {
            assertTrue(engine.nextAction() is Action.Swipe)
            engine.recordSwipe()
        }
        assertEquals(42, engine.swipesToday)
        assertTrue(engine.nextAction() is Action.Rest, "la session restaurée doit se terminer à 32")
    }

    @Test
    fun `cible restaurée hors des nouvelles bornes - retirée et session remise à zéro`() {
        val clock = FakeClock(paris(2026, 6, 15, 10))
        val config = BehaviorConfig(sessionMin = 5, sessionMax = 10)
        val saved = BehaviorState(30, LocalDate.of(2026, 6, 15), sessionSwipes = 30, sessionTarget = 50)
        val engine = BehaviorEngine(config, Random(1), clock, saved)
        assertTrue(engine.sessionTarget in 5..10)
        assertEquals(0, engine.sessionSwipes)
        assertEquals(30, engine.swipesToday)
        assertTrue(engine.nextAction() is Action.Swipe, "pas de pause immédiate")
    }

    @Test
    fun `pause longue persistée et respectée après restauration`() {
        val clock = FakeClock(paris(2026, 6, 15, 10))
        val config = BehaviorConfig(sessionMin = 2, sessionMax = 2, restMinMs = 20 * 60_000L, restMaxMs = 20 * 60_000L)
        val engine = BehaviorEngine(config, Random(1), clock)
        repeat(2) {
            assertTrue(engine.nextAction() is Action.Swipe)
            engine.recordSwipe()
        }
        assertEquals(Action.Rest(20 * 60_000L), engine.nextAction())
        val saved = engine.state
        assertEquals(clock.instant.plusMillis(20 * 60_000L), saved.restUntil)
        assertEquals(0, saved.sessionSwipes)

        // Stop puis Démarrer 5 min plus tard : la pause continue pour le temps restant.
        clock.advance(5 * 60_000L)
        val restored = BehaviorEngine(config, Random(2), clock, saved)
        assertEquals(Action.Rest(15 * 60_000L), restored.nextAction())
        assertEquals(Action.Rest(15 * 60_000L), restored.nextAction(), "tant que la pause n'est pas finie")

        // Pause finie : swipe et restUntil effacé.
        clock.advance(15 * 60_000L)
        assertTrue(restored.nextAction() is Action.Swipe)
        assertEquals(null, restored.state.restUntil)
    }

    @Test
    fun `pause restaurée plus longue que le nouveau maximum - ramenée au maximum ou annulée`() {
        val clock = FakeClock(paris(2026, 6, 15, 14))
        val saved = BehaviorState(
            swipesToday = 48, day = LocalDate.of(2026, 6, 15), sessionSwipes = 0, sessionTarget = 30,
            restUntil = clock.instant.plusMillis(60 * 60_000L),
        )
        // Pause réglée entre-temps à 5–10 min : il reste 10 min, pas 60.
        val shorter = BehaviorConfig(restMinMs = 5 * 60_000L, restMaxMs = 10 * 60_000L)
        val capped = BehaviorEngine(shorter, Random(1), clock, saved)
        assertEquals(Action.Rest(10 * 60_000L), capped.nextAction())
        assertEquals(clock.instant.plusMillis(10 * 60_000L), capped.state.restUntil)
        // Pause réglée à 0 : on swipe tout de suite.
        val none = BehaviorEngine(BehaviorConfig(), Random(1), clock, saved)
        assertTrue(none.nextAction() is Action.Swipe)
        assertEquals(null, none.state.restUntil)
    }

    @Test
    fun `plafond atteint puis redémarrage le même jour donne CapReached immédiatement`() {
        val clock = FakeClock(paris(2026, 6, 15, 14))
        val first = BehaviorEngine(defaults, Random(5), clock)
        repeat(150) { first.recordSwipe() }
        val restarted = BehaviorEngine(defaults, Random(6), clock, first.state)
        assertTrue(restarted.nextAction() is Action.CapReached)
    }

    @Test
    fun `état d'hier avec session en cours repart à zéro`() {
        val clock = FakeClock(paris(2026, 6, 15, 10))
        val saved = BehaviorState(80, LocalDate.of(2026, 6, 14), sessionSwipes = 20, sessionTarget = 30)
        val engine = BehaviorEngine(defaults, Random(2), clock, saved)
        assertTrue(engine.nextAction() is Action.Swipe)
        assertEquals(0, engine.swipesToday)
        assertEquals(0, engine.sessionSwipes)
        assertEquals(LocalDate.of(2026, 6, 15), engine.day)
        assertThrows<IllegalArgumentException> { BehaviorState(0, LocalDate.of(2026, 6, 15), sessionSwipes = -1) }
        assertThrows<IllegalArgumentException> { BehaviorState(0, LocalDate.of(2026, 6, 15), sessionTarget = 0) }
    }

    @Test
    fun `ouverture de profil - 8 % calibré donne 5–11 % d'OpenProfile parmi 1 000 actions de swipe`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        val engine = BehaviorEngine(noCap, Random(3), clock, profileAvailable = { true })
        var swipes = 0
        var opens = 0
        while (swipes + opens < 1_000) {
            when (val a = engine.nextAction()) {
                is Action.Swipe -> {
                    swipes++
                    clock.advance(a.delayMs)
                    engine.recordSwipe()
                }
                is Action.OpenProfile -> {
                    opens++
                    assertTrue(a.readMs in 2_000L..6_000L, "lecture ${a.readMs}")
                    assertTrue(a.backPauseMs in 600L..1_500L, "attente après retour ${a.backPauseMs}")
                    assertTrue(a.delayMs in 800L..40_000L, "délai ${a.delayMs}")
                    clock.advance(a.delayMs + a.readMs + a.backPauseMs)
                    engine.recordSwipe()
                }
                is Action.Rest -> clock.advance(a.durationMs)
                is Action.CapReached -> error("plafond inattendu")
                is Action.OutsideHours -> clock.advance(a.waitMs)
            }
        }
        assertTrue(opens in 50..110, "$opens ouvertures de profil sur 1 000")
    }

    @Test
    fun `ouverture de profil - jamais sans calibration ni à probabilité 0`() {
        for ((config, available) in listOf(noCap to false, noCap.copy(profileOpenProbability = 0.0) to true)) {
            val clock = FakeClock(paris(2026, 6, 15, 9))
            val engine = BehaviorEngine(config, Random(3), clock, profileAvailable = { available })
            repeat(2_000) {
                when (val a = engine.nextAction()) {
                    is Action.Swipe -> {
                        clock.advance(a.delayMs)
                        engine.recordSwipe()
                    }
                    is Action.OpenProfile -> error("OpenProfile avec $config / calibré = $available")
                    is Action.Rest -> clock.advance(a.durationMs)
                    is Action.CapReached -> error("plafond inattendu")
                    is Action.OutsideHours -> clock.advance(a.waitMs)
                }
            }
        }
    }

    @Test
    fun `ouverture de profil - disponibilité réévaluée à chaque décision`() {
        val clock = FakeClock(paris(2026, 6, 15, 9))
        var calibrated = true
        val engine = BehaviorEngine(noCap.copy(profileOpenProbability = 1.0), Random(3), clock) { calibrated }
        assertTrue(engine.nextAction() is Action.OpenProfile)
        calibrated = false // ex. Bumble non calibré au premier plan
        assertTrue(engine.nextAction() is Action.Swipe)
        assertThrows<IllegalArgumentException> { BehaviorConfig(profileOpenProbability = 1.5) }
        assertThrows<IllegalArgumentException> { BehaviorConfig(profileReadMinMs = 7_000) }
    }
}
