package com.swipper.app.behavior

/**
 * Paramètres du moteur de comportement (CAP-4). Les valeurs par défaut sont celles
 * de `architecture.md` ; toutes les durées sont en millisecondes.
 *
 * @property delayMedianMs médiane du délai log-normal entre deux swipes.
 * @property delaySigma écart-type de ln(délai).
 * @property delayMinMs borne basse du délai (hors distraction).
 * @property delayMaxMs borne haute du délai (hors distraction).
 * @property distractionProbability probabilité qu'une « distraction » remplace le délai.
 * @property distractionMinMs durée minimale d'une distraction.
 * @property distractionMaxMs durée maximale d'une distraction.
 * @property sessionMin nombre minimal de swipes par session.
 * @property sessionMax nombre maximal de swipes par session.
 * @property restMinMs durée minimale de la pause longue entre deux sessions.
 * @property restMaxMs durée maximale de la pause longue.
 * @property dailyCap nombre maximal de swipes par jour local.
 * @property activeStartMinute début de la plage horaire d'activité, en minutes depuis
 *   minuit local, dans [0 ; 1440).
 * @property activeEndMinute fin (exclue) de la plage, en minutes depuis minuit local,
 *   dans [0 ; 1440) et ≠ [activeStartMinute]. Une fin inférieure au début désigne une
 *   plage qui traverse minuit (ex. 20 h–2 h).
 * @property resumeJitterMinMs décalage aléatoire minimal ajouté à l'ouverture de la plage
 *   lors d'une reprise (hors plage ou plafond).
 * @property resumeJitterMaxMs décalage aléatoire maximal de la reprise.
 * @property profileOpenProbability probabilité qu'une ouverture de profil remplace un swipe
 *   (story 7), dans [0 ; 1] ; seulement si l'app au premier plan a une position calibrée.
 * @property profileReadMinMs durée minimale de « lecture » d'un profil ouvert.
 * @property profileReadMaxMs durée maximale de lecture.
 * @property profileBackMinMs attente minimale entre le retour arrière et le swipe.
 * @property profileBackMaxMs attente maximale entre le retour arrière et le swipe.
 *
 * @throws IllegalArgumentException si une valeur est ≤ 0, si un min dépasse son max,
 *   si la probabilité n'est pas dans [0 ; 1], si la médiane sort de [delayMinMs ; delayMaxMs],
 *   si une borne de la plage sort de [0 ; 1440) ou si début = fin, ou si un décalage de
 *   reprise est < 0.
 */
data class BehaviorConfig(
    val delayMedianMs: Long = 1_800,
    val delaySigma: Double = 0.45,
    val delayMinMs: Long = 800,
    val delayMaxMs: Long = 8_000,
    val distractionProbability: Double = 0.05,
    val distractionMinMs: Long = 10_000,
    val distractionMaxMs: Long = 40_000,
    val sessionMin: Int = 25,
    val sessionMax: Int = 60,
    val restMinMs: Long = 15 * MINUTE_MS,
    val restMaxMs: Long = 90 * MINUTE_MS,
    val dailyCap: Int = 150,
    val activeStartMinute: Int = 9 * 60,
    val activeEndMinute: Int = 23 * 60,
    val resumeJitterMinMs: Long = 2 * MINUTE_MS,
    val resumeJitterMaxMs: Long = 45 * MINUTE_MS,
    val profileOpenProbability: Double = 0.08,
    val profileReadMinMs: Long = 2_000,
    val profileReadMaxMs: Long = 6_000,
    val profileBackMinMs: Long = 600,
    val profileBackMaxMs: Long = 1_500,
) {
    init {
        require(delayMedianMs > 0) { "delayMedianMs doit être > 0 (reçu $delayMedianMs)" }
        require(delaySigma > 0.0 && delaySigma.isFinite()) { "delaySigma doit être > 0 et fini (reçu $delaySigma)" }
        require(delayMinMs > 0) { "delayMinMs doit être > 0 (reçu $delayMinMs)" }
        require(delayMaxMs > 0) { "delayMaxMs doit être > 0 (reçu $delayMaxMs)" }
        require(delayMinMs <= delayMaxMs) { "delayMinMs ($delayMinMs) doit être ≤ delayMaxMs ($delayMaxMs)" }
        require(delayMedianMs in delayMinMs..delayMaxMs) {
            "delayMedianMs ($delayMedianMs) doit être dans [$delayMinMs ; $delayMaxMs]"
        }
        require(distractionProbability in 0.0..1.0) {
            "distractionProbability doit être dans [0 ; 1] (reçu $distractionProbability)"
        }
        require(distractionMinMs > 0) { "distractionMinMs doit être > 0 (reçu $distractionMinMs)" }
        require(distractionMaxMs > 0) { "distractionMaxMs doit être > 0 (reçu $distractionMaxMs)" }
        require(distractionMinMs <= distractionMaxMs) {
            "distractionMinMs ($distractionMinMs) doit être ≤ distractionMaxMs ($distractionMaxMs)"
        }
        require(sessionMin > 0) { "sessionMin doit être > 0 (reçu $sessionMin)" }
        require(sessionMax > 0) { "sessionMax doit être > 0 (reçu $sessionMax)" }
        require(sessionMin <= sessionMax) { "sessionMin ($sessionMin) doit être ≤ sessionMax ($sessionMax)" }
        require(restMinMs > 0) { "restMinMs doit être > 0 (reçu $restMinMs)" }
        require(restMaxMs > 0) { "restMaxMs doit être > 0 (reçu $restMaxMs)" }
        require(restMinMs <= restMaxMs) { "restMinMs ($restMinMs) doit être ≤ restMaxMs ($restMaxMs)" }
        require(dailyCap >= 1) { "dailyCap doit être ≥ 1 (reçu $dailyCap)" }
        require(activeStartMinute in 0 until MINUTES_PER_DAY) {
            "activeStartMinute doit être dans [0 ; $MINUTES_PER_DAY) (reçu $activeStartMinute)"
        }
        require(activeEndMinute in 0 until MINUTES_PER_DAY) {
            "activeEndMinute doit être dans [0 ; $MINUTES_PER_DAY) (reçu $activeEndMinute)"
        }
        require(activeStartMinute != activeEndMinute) {
            "activeStartMinute et activeEndMinute doivent différer (reçu $activeStartMinute)"
        }
        require(resumeJitterMinMs >= 0) { "resumeJitterMinMs doit être ≥ 0 (reçu $resumeJitterMinMs)" }
        require(resumeJitterMaxMs >= 0) { "resumeJitterMaxMs doit être ≥ 0 (reçu $resumeJitterMaxMs)" }
        require(resumeJitterMinMs <= resumeJitterMaxMs) {
            "resumeJitterMinMs ($resumeJitterMinMs) doit être ≤ resumeJitterMaxMs ($resumeJitterMaxMs)"
        }
        require(profileOpenProbability in 0.0..1.0) {
            "profileOpenProbability doit être dans [0 ; 1] (reçu $profileOpenProbability)"
        }
        require(profileReadMinMs > 0 && profileReadMinMs <= profileReadMaxMs) {
            "profileReadMinMs ($profileReadMinMs) doit être > 0 et ≤ profileReadMaxMs ($profileReadMaxMs)"
        }
        require(profileBackMinMs > 0 && profileBackMinMs <= profileBackMaxMs) {
            "profileBackMinMs ($profileBackMinMs) doit être > 0 et ≤ profileBackMaxMs ($profileBackMaxMs)"
        }
    }

    companion object {
        internal const val MINUTE_MS = 60_000L
        internal const val MINUTES_PER_DAY = 24 * 60
    }
}
