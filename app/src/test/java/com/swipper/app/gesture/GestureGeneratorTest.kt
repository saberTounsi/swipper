package com.swipper.app.gesture

import com.swipper.app.gesture.GestureGenerator.Companion.DRIFT_MAX
import com.swipper.app.gesture.GestureGenerator.Companion.DRIFT_MIN
import com.swipper.app.gesture.GestureGenerator.Companion.HESITANT_PROBABILITY
import com.swipper.app.gesture.GestureGenerator.Companion.LEFT_BAND_MAX
import com.swipper.app.gesture.GestureGenerator.Companion.LEFT_BAND_MIN
import com.swipper.app.gesture.GestureGenerator.Companion.LEFT_START_MIN
import com.swipper.app.gesture.GestureGenerator.Companion.JITTER_MAX_PX
import com.swipper.app.gesture.GestureGenerator.Companion.JITTER_MIN_PX
import com.swipper.app.gesture.GestureGenerator.Companion.MAX_INTERMEDIATE_POINTS
import com.swipper.app.gesture.GestureGenerator.Companion.MAX_SEGMENTS
import com.swipper.app.gesture.GestureGenerator.Companion.MIN_INTERMEDIATE_POINTS
import com.swipper.app.gesture.GestureGenerator.Companion.MAX_TOTAL_MS
import com.swipper.app.gesture.GestureGenerator.Companion.MIN_SEGMENTS
import com.swipper.app.gesture.GestureGenerator.Companion.MIN_SEGMENT_MS
import com.swipper.app.gesture.GestureGenerator.Companion.MIN_TOTAL_MS
import com.swipper.app.gesture.GestureGenerator.Companion.MIN_TRAVEL
import com.swipper.app.gesture.GestureGenerator.Companion.PEAK_MAX
import com.swipper.app.gesture.GestureGenerator.Companion.PEAK_MIN
import com.swipper.app.gesture.GestureGenerator.Companion.RELEASE_MAX
import com.swipper.app.gesture.GestureGenerator.Companion.RELEASE_MIN
import com.swipper.app.gesture.GestureGenerator.Companion.RIGHT_BAND_MAX
import com.swipper.app.gesture.GestureGenerator.Companion.RIGHT_BAND_MIN
import com.swipper.app.gesture.GestureGenerator.Companion.RIGHT_START_MAX
import com.swipper.app.gesture.GestureGenerator.Companion.ease
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

class GestureGeneratorTest {

    private val phone = ScreenSize(1080, 2400)
    private val small = ScreenSize(480, 800)
    private val zone = SwipeZone.DEFAULT

    /** Tolérance d'arrondi Float (px ou fraction). */
    private val eps = 1e-3

    private fun plans(
        count: Int = 1000,
        screen: ScreenSize = phone,
        seed: Int = 42,
        direction: (Int) -> Direction = { if (it % 2 == 0) Direction.RIGHT else Direction.LEFT },
    ): List<Pair<Direction, SwipePlan>> {
        val gen = GestureGenerator(Random(seed))
        return List(count) { i -> direction(i).let { it to gen.plan(screen, zone, it) } }
    }

    private fun dist(a: Point, b: Point) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())

    /** Vitesse d'un segment mesurée entre ses extrémités (px/ms). */
    private fun speed(s: Segment) = dist(s.start, s.end) / s.durationMs

    private fun allPoints(p: SwipePlan) = listOf(p.start) + p.segments.flatMap { it.points.drop(1) }

    private fun checkPlanShape(p: SwipePlan) {
        assertTrue(p.segments.size in MIN_SEGMENTS..MAX_SEGMENTS, "segments = ${p.segments.size}")
        assertTrue(p.totalDurationMs in MIN_TOTAL_MS..MAX_TOTAL_MS, "durée = ${p.totalDurationMs}")
    }

    @Test
    fun `swipe droite`() {
        val gen = GestureGenerator(Random(1))
        repeat(200) {
            val p = gen.plan(phone, zone, Direction.RIGHT)
            assertTrue(p.end.x > p.start.x)
            checkPlanShape(p)
        }
    }

    @Test
    fun `swipe gauche`() {
        val gen = GestureGenerator(Random(2))
        repeat(200) {
            val p = gen.plan(phone, zone, Direction.LEFT)
            assertTrue(p.end.x < p.start.x)
            checkPlanShape(p)
        }
    }

    @Test
    fun `variete - aucun plan identique`() {
        val all = plans().map { it.second }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `bornes sur petit ecran`() {
        val all = plans(screen = small)
        assertTrue(all.isNotEmpty())
        for ((_, p) in all) {
            checkPlanShape(p)
            val fx = p.start.x / small.widthPx
            val fy = p.start.y / small.heightPx
            assertTrue(fx >= zone.xMin && fx <= zone.xMax, "départ x hors zone : $fx")
            assertTrue(fy >= zone.yMin && fy <= zone.yMax, "départ y hors zone : $fy")
            for (pt in allPoints(p)) {
                assertTrue(pt.x >= 0f && pt.x <= small.widthPx - 1f, "x hors écran : $pt")
                assertTrue(pt.y >= 0f && pt.y <= small.heightPx - 1f, "y hors écran : $pt")
            }
            for (s in p.segments) assertTrue(s.durationMs >= MIN_SEGMENT_MS, "segment ${s.durationMs} ms")
        }
    }

    @Test
    fun `profil de vitesse - milieu plus rapide que le depart`() {
        // Le profil « lancer » garde de la vitesse à la levée : seul le départ doit
        // être plus lent que le milieu (la fin n'a plus à ralentir sous le milieu).
        val all = plans()
        assertTrue(all.isNotEmpty())
        for ((_, p) in all) {
            val speeds = p.segments.map(::speed)
            val middle = speeds.subList(1, speeds.size - 1)
            assertTrue(middle.isNotEmpty())
            val mean = middle.average()
            assertTrue(mean > speeds.first(), "milieu $mean ≤ premier ${speeds.first()} (hésitant=${p.hesitant})")
        }
    }

    @Test
    fun `lancer - vitesse conservee a la levee`() {
        val all = plans()
        assertTrue(all.isNotEmpty())
        assertTrue(all.any { it.second.hesitant }, "aucun hésitant dans l'échantillon")
        for ((_, p) in all) {
            val speeds = p.segments.map(::speed)
            val fastest = speeds.max()
            assertTrue(
                speeds.last() >= RELEASE_MIN * fastest,
                "dernier ${speeds.last()} < $RELEASE_MIN × pic $fastest (hésitant=${p.hesitant})",
            )
            assertTrue(speeds.first() < fastest, "premier segment ${speeds.first()} = le plus rapide (hésitant=${p.hesitant})")
        }
    }

    @Test
    fun `lancer - pic et vitesse de levee tires par geste`() {
        val normal = plans().map { it.second }.filter { !it.hesitant }
        assertTrue(normal.isNotEmpty())
        val ratios = normal.map { p ->
            val speeds = p.segments.map(::speed)
            val k = speeds.indices.maxBy { speeds[it] }
            val total = p.totalDurationMs.toDouble()
            val before = p.segments.take(k).sumOf { it.durationMs }
            val mid = (before + p.segments[k].durationMs / 2.0) / total
            val share = p.segments.maxOf { it.durationMs } / total
            assertTrue(
                mid >= PEAK_MIN - share && mid <= PEAK_MAX + share,
                "segment le plus rapide centré en $mid hors [$PEAK_MIN ; $PEAK_MAX] ± $share",
            )
            speeds.last() / speeds[k]
        }
        assertTrue(ratios.max() - ratios.min() > 0.1, "rapport levée/pic peu varié : ${ratios.min()}..${ratios.max()}")
    }

    @Test
    fun `lancer - fonction E monotone de 0 a 1 avec pic et vitesse de levee`() {
        val steps = 1000
        for (hesitant in listOf(false, true)) {
            for (peak in listOf(PEAK_MIN, (PEAK_MIN + PEAK_MAX) / 2, PEAK_MAX)) {
                for (release in listOf(RELEASE_MIN, RELEASE_MAX)) {
                    assertEquals(0.0, ease(0.0, hesitant, peak, release), 1e-12)
                    assertEquals(1.0, ease(1.0, hesitant, peak, release), 1e-12)
                    val e = DoubleArray(steps + 1) { ease(it.toDouble() / steps, hesitant, peak, release) }
                    val v = DoubleArray(steps) { e[it + 1] - e[it] }
                    for (i in 0 until steps) assertTrue(v[i] >= 0.0, "E décroît en ${i.toDouble() / steps}")
                    if (!hesitant) {
                        val peakAt = v.indices.maxBy { v[it] }.toDouble() / steps
                        assertEquals(peak, peakAt, 2.0 / steps, "pic mal placé")
                        assertEquals(release, v.last() / v.max(), 0.01, "vitesse de levée")
                    }
                }
            }
        }
    }

    @Test
    fun `hesitants - premier segment plus lent`() {
        val all = plans().map { it.second }
        // Vitesse du 1er segment rapportée à la vitesse moyenne du plan (indépendante
        // de la longueur et de la durée du geste).
        fun relativeFirst(p: SwipePlan): Double {
            val length = p.segments.sumOf { dist(it.start, it.end) }
            return speed(p.segments.first()) / (length / p.totalDurationMs)
        }
        val (hesitant, normal) = all.partition { it.hesitant }
        assertTrue(hesitant.isNotEmpty(), "aucun geste hésitant")
        assertTrue(normal.isNotEmpty(), "aucun geste normal")
        val normalMean = normal.map(::relativeFirst).average()
        for (p in hesitant) {
            val r = relativeFirst(p)
            assertTrue(r < normalMean, "hésitant au 1er segment $r ≥ moyenne des normaux $normalMean")
        }
    }

    @Test
    fun `hesitants - proportion`() {
        val all = plans().map { it.second }
        val share = all.count { it.hesitant }.toDouble() / all.size
        val low = HESITANT_PROBABILITY / 2
        val high = HESITANT_PROBABILITY * 1.5
        assertTrue(share in low..high, "proportion d'hésitants = $share (attendu $low..$high)")
    }

    @Test
    fun `tremblement perpendiculaire sur les points intermediaires`() {
        val seed = 11
        val count = 300
        val noisyGen = GestureGenerator(Random(seed))
        val cleanGen = GestureGenerator(Random(seed)).apply { jitterEnabled = false }
        var displaced = 0
        var inRange = 0
        repeat(count) { i ->
            val dir = if (i % 2 == 0) Direction.RIGHT else Direction.LEFT
            val noisy = noisyGen.plan(phone, zone, dir)
            val clean = cleanGen.plan(phone, zone, dir)
            assertEquals(clean.segments.size, noisy.segments.size)
            val cdx = (clean.end.x - clean.start.x).toDouble()
            val cdy = (clean.end.y - clean.start.y).toDouble()
            val clen = hypot(cdx, cdy)
            for ((sn, sc) in noisy.segments.zip(clean.segments)) {
                assertTrue(
                    sn.points.size - 2 in MIN_INTERMEDIATE_POINTS..MAX_INTERMEDIATE_POINTS,
                    "intermédiaires = ${sn.points.size - 2}",
                )
                assertEquals(sc.points.size, sn.points.size)
                assertEquals(sc.start, sn.start, "frontière bruitée")
                assertEquals(sc.end, sn.end, "frontière bruitée")
                for (j in 1 until sn.points.size - 1) {
                    val c = sc.points[j]
                    val ddx = (sn.points[j].x - c.x).toDouble()
                    val ddy = (sn.points[j].y - c.y).toDouble()
                    val d = hypot(ddx, ddy)
                    val gap = min(dist(sc.points[j - 1], c), dist(c, sc.points[j + 1]))
                    assertTrue(d <= min(JITTER_MAX_PX, gap / 4) + eps, "déplacement $d > borne (écart $gap)")
                    assertTrue(abs((ddx * cdx + ddy * cdy) / clen) <= eps, "bruit le long de la corde : $ddx, $ddy")
                    if (d > eps) displaced++
                    if (d >= JITTER_MIN_PX - eps) inRange++
                }
            }
        }
        assertTrue(displaced > 0, "aucun point intermédiaire bruité")
        assertTrue(inRange > 0, "aucun déplacement dans [$JITTER_MIN_PX ; $JITTER_MAX_PX] px")
    }

    @Test
    fun `continuite des segments`() {
        for ((_, p) in plans(screen = small)) {
            for (i in 1 until p.segments.size) {
                assertEquals(p.segments[i - 1].end, p.segments[i].start)
            }
        }
    }

    @Test
    fun `amplitude et derive`() {
        val all = plans()
        val (right, left) = all.partition { it.first == Direction.RIGHT }
        assertTrue(right.isNotEmpty() && left.isNotEmpty())
        val w = phone.widthPx.toDouble()
        val h = phone.heightPx.toDouble()
        for ((dir, p) in all) {
            val sx = p.start.x / w
            val ex = p.end.x / w
            when (dir) {
                Direction.RIGHT -> {
                    assertTrue(ex >= max(RIGHT_BAND_MIN, sx + MIN_TRAVEL) - eps && ex <= RIGHT_BAND_MAX + eps, "arrivée $ex")
                    assertTrue(ex - sx >= MIN_TRAVEL - eps, "trajet ${ex - sx}")
                }
                Direction.LEFT -> {
                    assertTrue(ex >= LEFT_BAND_MIN - eps && ex <= min(LEFT_BAND_MAX, sx - MIN_TRAVEL) + eps, "arrivée $ex")
                    assertTrue(sx - ex >= MIN_TRAVEL - eps, "trajet ${sx - ex}")
                }
            }
            val dy = abs(p.end.y - p.start.y) / h
            assertTrue(dy >= DRIFT_MIN - eps && dy <= DRIFT_MAX + eps, "dérive $dy")
        }
        assertEquals(0.35, MIN_TRAVEL)
        for (part in listOf(right, left)) {
            val distinct = part.map { it.second.end.x }.toSet().size
            assertTrue(distinct >= 100, "seulement $distinct arrivées distinctes")
        }
    }

    @Test
    fun `depart selon le sens`() {
        // Vers la droite : départ dans le tiers gauche ; vers la gauche : dans le tiers droit.
        assertEquals(0.35, RIGHT_START_MAX)
        assertEquals(0.65, LEFT_START_MIN)
        val w = phone.widthPx.toDouble()
        val right = plans { Direction.RIGHT }.map { it.second }
        val left = plans { Direction.LEFT }.map { it.second }
        assertTrue(right.isNotEmpty() && left.isNotEmpty())
        for (p in right) {
            val fx = p.start.x / w
            assertTrue(fx <= RIGHT_START_MAX + eps, "départ RIGHT $fx > $RIGHT_START_MAX")
            assertTrue(fx >= zone.xMin - eps && fx <= zone.xMax + eps, "départ RIGHT hors zone : $fx")
        }
        for (p in left) {
            val fx = p.start.x / w
            assertTrue(fx >= LEFT_START_MIN - eps, "départ LEFT $fx < $LEFT_START_MIN")
            assertTrue(fx >= zone.xMin - eps && fx <= zone.xMax + eps, "départ LEFT hors zone : $fx")
        }
    }

    @Test
    fun `duree totale`() {
        assertEquals(180L, MIN_TOTAL_MS)
        assertEquals(450L, MAX_TOTAL_MS)
        val all = plans().map { it.second }
        assertTrue(all.isNotEmpty())
        for (p in all) assertTrue(p.totalDurationMs in MIN_TOTAL_MS..MAX_TOTAL_MS, "durée = ${p.totalDurationMs}")
        val durations = all.map { it.totalDurationMs }
        assertTrue(durations.min() < 250 && durations.max() > 400, "durées peu étalées : ${durations.min()}..${durations.max()}")
    }

    @Test
    fun `biais vers la droite`() {
        val all = plans()
        assertTrue(all.isNotEmpty())
        // Mesuré par sens, dans l'intervalle de départ réellement utilisé pour ce sens.
        for (dir in Direction.values()) {
            val (lo, hi) = when (dir) {
                Direction.RIGHT -> zone.xMin.toDouble() to min(zone.xMax.toDouble(), RIGHT_START_MAX)
                Direction.LEFT -> max(zone.xMin.toDouble(), LEFT_START_MIN) to zone.xMax.toDouble()
            }
            val part = all.filter { it.first == dir }
            assertTrue(part.isNotEmpty())
            val mean = part.map { (_, p) -> (p.start.x / phone.widthPx - lo) / (hi - lo) }.average()
            assertTrue(mean > 0.5, "position moyenne du départ $dir = $mean")
        }
    }

    @Test
    fun `courbure`() {
        val all = plans()
        assertTrue(all.isNotEmpty())
        val threshold = 0.005 * phone.widthPx
        val curved = all.count { (_, p) ->
            val ax = p.start.x.toDouble()
            val ay = p.start.y.toDouble()
            val dx = p.end.x - ax
            val dy = p.end.y - ay
            val len = hypot(dx, dy)
            p.segments.drop(1).any { s ->
                abs((s.start.x - ax) * dy - (s.start.y - ay) * dx) / len >= threshold
            }
        }
        assertTrue(curved >= 0.9 * all.size, "plans courbes : $curved / ${all.size}")
    }

    @Test
    fun `depart a dominante horizontale`() {
        // Android donne le geste au premier axe qui dépasse le seuil de toucher (~22 px) :
        // un départ trop vertical fait défiler la carte de Bumble au lieu de la swiper.
        // Plusieurs graines : le cas limite (petit écran, dérive et décalage max) est rare.
        for (screen in listOf(phone, small)) {
            for (seed in 100..119) {
                for ((dir, p) in plans(count = 2000, screen = screen, seed = seed)) {
                    var checked = 0
                    for (pt in allPoints(p).drop(1)) {
                        if (dist(p.start, pt) > 80.0) break
                        checked++
                        val ax = abs(pt.x - p.start.x)
                        val ay = abs(pt.y - p.start.y)
                        assertTrue(ay <= 0.6 * ax + eps, "$dir sur $screen, graine $seed : Δy=$ay, Δx=$ax")
                    }
                    assertTrue(checked > 0, "aucun point à moins de 80 px du départ")
                }
            }
        }
    }

    @Test
    fun `jamais a reculons`() {
        val all = plans(screen = small)
        assertTrue(all.any { it.second.hesitant }, "aucun hésitant dans l'échantillon")
        for ((_, p) in all) {
            val dx = (p.end.x - p.start.x).toDouble()
            val dy = (p.end.y - p.start.y).toDouble()
            val len = hypot(dx, dy)
            val proj = allPoints(p).map { ((it.x - p.start.x) * dx + (it.y - p.start.y) * dy) / len }
            for (i in 1 until proj.size) {
                assertTrue(proj[i] >= proj[i - 1] - eps, "recul au point $i : ${proj[i - 1]} → ${proj[i]}")
            }
        }
    }

    @Test
    fun `entree invalide`() {
        val gen = GestureGenerator(Random(0))
        assertThrows<IllegalArgumentException> { ScreenSize(0, 100) }
        assertThrows<IllegalArgumentException> { ScreenSize(100, -1) }
        assertThrows<IllegalArgumentException> { SwipeZone(-0.1f, 0.5f, 0.4f, 0.6f) }
        assertThrows<IllegalArgumentException> { SwipeZone(0.3f, 0.5f, 0.4f, 1.1f) }
        assertThrows<IllegalArgumentException> { SwipeZone(0.6f, 0.4f, 0.4f, 0.6f) }
        assertThrows<IllegalArgumentException> { SwipeZone(0.3f, 0.5f, 0.7f, 0.6f) }
        assertThrows<IllegalArgumentException> { SwipeZone(Float.NaN, 0.5f, 0.4f, 0.6f) }
        assertThrows<IllegalArgumentException> { SwipeZone(0.3f, 0.5f, 0.4f, Float.NaN) }
        // Zone trop à droite (xMin > 0,35) ou trop à gauche (xMax < 0,65) : un des deux
        // sens serait impossible, la zone est refusée quel que soit le sens demandé.
        val tooRight = SwipeZone(0.40f, 0.90f, 0.4f, 0.6f)
        val tooLeft = SwipeZone(0.10f, 0.60f, 0.4f, 0.6f)
        for (dir in Direction.values()) {
            assertThrows<IllegalArgumentException> { gen.plan(phone, tooRight, dir) }
            assertThrows<IllegalArgumentException> { gen.plan(phone, tooLeft, dir) }
        }
        assertThrows<IllegalArgumentException> { gen.splitDuration(MIN_SEGMENT_MS * 3 - 1, 3) }
    }

    @Test
    fun `zones aux limites 0,35 et 0,65`() {
        val gen = GestureGenerator(Random(5))
        val w = phone.widthPx.toDouble()
        val atRightLimit = SwipeZone(0.35f, 1f, 0.4f, 0.6f)
        val atLeftLimit = SwipeZone(0f, 0.65f, 0.4f, 0.6f)
        for (z in listOf(atRightLimit, atLeftLimit)) {
            for (dir in Direction.values()) {
                repeat(50) {
                    val p = gen.plan(phone, z, dir)
                    val travel = (p.end.x - p.start.x) / w * (if (dir == Direction.RIGHT) 1 else -1)
                    assertTrue(travel >= MIN_TRAVEL - eps, "trajet $travel ($dir, $z)")
                }
            }
        }
        for (dir in Direction.values()) {
            assertThrows<IllegalArgumentException> { gen.plan(phone, SwipeZone(0.36f, 1f, 0.4f, 0.6f), dir) }
            assertThrows<IllegalArgumentException> { gen.plan(phone, SwipeZone(0f, 0.64f, 0.4f, 0.6f), dir) }
        }
    }

    @Test
    fun `zone limite et bords haut bas restent valides`() {
        val gen = GestureGenerator(Random(9))
        // Toute la largeur est désormais acceptée : le départ est restreint selon le sens.
        val edge = SwipeZone(0f, 1f, 0f, 1f)
        val w = small.widthPx.toDouble()
        val h = small.heightPx.toDouble()
        var up = 0
        var down = 0
        var edgeStarts = 0
        repeat(1000) { i ->
            val dir = if (i % 2 == 0) Direction.RIGHT else Direction.LEFT
            val p = gen.plan(small, edge, dir)
            if (dir == Direction.RIGHT) assertTrue(p.end.x > p.start.x) else assertTrue(p.end.x < p.start.x)
            val travel = abs(p.end.x - p.start.x) / w
            assertTrue(travel >= MIN_TRAVEL - eps, "trajet $travel en zone limite")
            for (pt in allPoints(p)) {
                assertTrue(pt.x in 0f..(small.widthPx - 1f) && pt.y in 0f..(small.heightPx - 1f), "hors écran : $pt")
            }
            val dy = (p.end.y - p.start.y) / h
            assertTrue(abs(dy) >= DRIFT_MIN - eps && abs(dy) <= DRIFT_MAX + eps, "dérive $dy près du bord")
            if (dy > 0) down++ else up++
            // Départ assez près d'un bord pour qu'un des deux sens de dérive sorte de l'écran.
            if (p.start.y / h < DRIFT_MAX || p.start.y / h > 1 - DRIFT_MAX) edgeStarts++
        }
        assertTrue(up > 0 && down > 0, "les deux sens de dérive doivent apparaître (haut=$up, bas=$down)")
        assertTrue(edgeStarts > 0, "aucun départ près du haut ou du bas : l'inversion n'est pas exercée")
    }

    @Test
    fun `splitDuration respecte somme et plancher`() {
        val gen = GestureGenerator(Random(3))
        for (n in MIN_SEGMENTS..MAX_SEGMENTS) {
            for (total in listOf(MIN_SEGMENT_MS * n, MIN_TOTAL_MS, MAX_TOTAL_MS)) {
                if (total < MIN_SEGMENT_MS * n) continue
                val d = gen.splitDuration(total, n)
                assertEquals(n, d.size)
                assertEquals(total, d.sum())
                assertTrue(d.all { it >= MIN_SEGMENT_MS })
            }
        }
    }

    @Test
    fun `meme graine donne les memes plans`() {
        assertEquals(plans(seed = 7), plans(seed = 7))
    }

    @Test
    fun `aucune dependance android dans le module gesture`() {
        val rel = "src/main/java/com/swipper/app/gesture"
        val dir = listOf(File(rel), File("app/$rel")).firstOrNull { it.isDirectory }
            ?: fail("Dossier du module gesture introuvable depuis ${File(".").absolutePath} (cherché : $rel, app/$rel)")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "aucun fichier .kt dans $dir")
        val pattern = Regex("""\bandroidx?\.""")
        for (f in files) {
            val code = f.readText()
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")
                .replace(Regex("""//[^\n]*"""), "")
            val hit = pattern.find(code)
            assertTrue(hit == null, "${f.name} référence Android : « ${hit?.value} »")
        }
    }
}
