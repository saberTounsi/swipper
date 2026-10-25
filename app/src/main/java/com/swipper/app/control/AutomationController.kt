package com.swipper.app.control

import com.swipper.app.behavior.Action
import com.swipper.app.behavior.BehaviorEngine
import com.swipper.app.behavior.BehaviorState
import com.swipper.app.behavior.Clock
import com.swipper.app.decision.DirectionPicker
import com.swipper.app.gesture.Direction
import com.swipper.app.service.DispatchResult
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** État de l'automatisation (cf. `architecture-diagrams.md`). */
enum class AutomationState { Idle, Running, Paused, Resting, CapReached, OutsideHours }

/** Exécute un swipe dans [direction] (plan + envoi) ; annulable. */
fun interface SwipeExecutor {
    suspend fun execute(direction: Direction): DispatchResult
}

/**
 * Tape à la position calibrée d'ouverture de profil de [packageName] (story 7), par le
 * même mécanisme que les swipes ; annulable. [DispatchResult.Failed] si l'app n'est pas
 * calibrée.
 */
fun interface TapExecutor {
    suspend fun tap(packageName: String?): DispatchResult
}

/**
 * Moteur, sélecteur et apps cibles d'une session, construits depuis les réglages à chaque
 * Démarrer (story 6).
 */
class SessionSetup(
    val engine: BehaviorEngine,
    val picker: DirectionPicker,
    val isTarget: (String?) -> Boolean,
)

/**
 * Boucle de la session (CAP-5) : comportement → décision → geste → exécution, seulement
 * quand une app cible est au premier plan, que l'écran est allumé et déverrouillé
 * ([deviceUsable]) et que l'utilisateur n'a pas mis en pause.
 *
 * Kotlin pur : moteur, sélecteur, exécuteur, horloge et flux d'état sont injectés ;
 * les attentes passent par `delay()` dans [scope] (temps virtuel en test). Les pauses
 * longues, le plafond et les heures hors plage attendent une échéance de [clock] par tranches de
 * [WAIT_CHUNK_MS] (le `delay` Android s'allonge en veille profonde).
 * Les méthodes publiques s'appellent depuis un seul thread (le thread principal).
 *
 * Une exception levée par la boucle la ramène à [AutomationState.Idle], renseigne
 * [error] puis est relancée vers le `CoroutineExceptionHandler` de [scope].
 * [MAX_CONSECUTIVE_FAILURES] swipes non aboutis d'affilée arrêtent aussi la boucle.
 *
 * @param setupProvider appelé à chaque [start] : remplace moteur, sélecteur et apps
 *   cibles (réglages et état sauvegardé) ; `null` = garder ceux du constructeur. S'il
 *   renvoie `null` (réglages pas encore lus), le démarrage est différé jusqu'à [setupReady].
 * @param tapExecutor tap d'ouverture de profil ([Action.OpenProfile]).
 * @param goBack retour arrière (`GLOBAL_ACTION_BACK`) après la lecture d'un profil ;
 *   `false` si le système le refuse : la boucle s'arrête alors avec [BACK_REFUSED_ERROR].
 * @param startAllowed `false` = [start] ignoré (ex. calibration en cours).
 * @param onStateChanged appelé avec l'état du moteur après chaque swipe enregistré et
 *   chaque décision qui le modifie (fin de session, changement de jour), pour le persister.
 */
class AutomationController(
    private val scope: CoroutineScope,
    engine: BehaviorEngine,
    picker: DirectionPicker,
    private val executor: SwipeExecutor,
    private val foregroundPackage: StateFlow<String?>,
    private val clock: Clock,
    private val deviceUsable: StateFlow<Boolean> = MutableStateFlow(true),
    isTarget: (String?) -> Boolean = TargetApps::isTarget,
    private val setupProvider: (() -> SessionSetup?)? = null,
    private val onStateChanged: (BehaviorState) -> Unit = {},
    private val tapExecutor: TapExecutor = TapExecutor { DispatchResult.Failed },
    private val goBack: () -> Boolean = { false },
    private val startAllowed: () -> Boolean = { true },
) {
    private var engine: BehaviorEngine = engine
    private var picker: DirectionPicker = picker
    private var isTarget: (String?) -> Boolean = isTarget
    private var lastReportedState: BehaviorState = engine.state

    private val _state = MutableStateFlow(AutomationState.Idle)

    /** État courant de la boucle. */
    val state: StateFlow<AutomationState> = _state.asStateFlow()

    private val _userPaused = MutableStateFlow(false)

    /** `true` si l'utilisateur a mis en pause (indépendamment de l'app au premier plan). */
    val userPaused: StateFlow<Boolean> = _userPaused.asStateFlow()

    private val _swipesToday = MutableStateFlow(engine.swipesToday)

    /** Swipes réussis du jour (persisté via [onStateChanged]). */
    val swipesToday: StateFlow<Int> = _swipesToday.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Message de la dernière erreur ayant arrêté la boucle ; remis à `null` au démarrage. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _resumeAt = MutableStateFlow<Instant?>(null)

    /**
     * Échéance de l'attente en [AutomationState.OutsideHours] ou [AutomationState.CapReached]
     * (reprise possible) ; `null` sinon.
     */
    val resumeAt: StateFlow<Instant?> = _resumeAt.asStateFlow()

    /** Fuseau de l'horloge de la boucle, pour afficher [resumeAt]. */
    val zone: ZoneId get() = clock.zone

    /** Vrai quand un geste peut partir : app cible, écran utilisable, pas de pause manuelle. */
    private val gate: Flow<Boolean> =
        combine(foregroundPackage, deviceUsable, _userPaused) { pkg, usable, paused ->
            // Propriété (remplacée à chaque Démarrer), pas le paramètre du constructeur.
            !paused && usable && this@AutomationController.isTarget(pkg)
        }.distinctUntilChanged()

    private fun gateOpen(): Boolean =
        !_userPaused.value && deviceUsable.value && isTarget(foregroundPackage.value)

    private var job: Job? = null
    private var consecutiveFailures = 0

    /** Démarrer demandé avant que [setupProvider] ne puisse fournir une session. */
    private var startPending = false

    /**
     * Paquet dont un profil a été ouvert puis laissé ouvert (lecture interrompue, Stop compris) ;
     * `null` sinon. Conservé d'une session à l'autre pendant toute la vie du contrôleur.
     */
    private var profileOpenIn: String? = null

    /** Démarre la boucle (sans effet si elle tourne déjà). Paused tant que la porte est fermée. */
    fun start() {
        if (job?.isActive == true) return
        if (!startAllowed()) return
        if (setupProvider != null) {
            val setup = setupProvider.invoke()
            if (setup == null) {
                startPending = true
                return
            }
            engine = setup.engine
            picker = setup.picker
            isTarget = setup.isTarget
            lastReportedState = engine.state
        }
        startPending = false
        _error.value = null
        _userPaused.value = false
        consecutiveFailures = 0
        syncSwipesToday()
        _state.value = if (gateOpen()) AutomationState.Running else AutomationState.Paused
        lateinit var self: Job
        self = scope.launch(start = CoroutineStart.LAZY) {
            try {
                loop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Une ancienne boucle déjà remplacée ne touche pas à la nouvelle session.
                if (job === self) {
                    toIdle()
                    _error.value = e.message ?: e::class.java.simpleName
                }
                throw e
            }
        }
        job = self
        self.start()
    }

    /** À appeler quand [setupProvider] peut fournir une session : lance un Démarrer différé. */
    fun setupReady() {
        if (startPending) start()
    }

    /** Pause manuelle ; dans Resting/CapReached/OutsideHours l'attente continue, la pause s'applique ensuite. */
    fun pause() {
        if (job == null) return
        _userPaused.value = true
        if (_state.value == AutomationState.Running) _state.value = AutomationState.Paused
    }

    /** Lève la pause manuelle ; la boucle reprend si la porte est ouverte. */
    fun resume() {
        if (job == null) return
        _userPaused.value = false
    }

    /** Pause ou reprise selon l'état de la pause manuelle. */
    fun togglePause() = if (_userPaused.value) resume() else pause()

    /** Arrête tout : annule la boucle et le geste en cours, retour immédiat à Idle. */
    fun stop() {
        startPending = false
        job?.cancel()
        toIdle()
    }

    private fun toIdle() {
        job = null
        _userPaused.value = false
        _resumeAt.value = null
        _state.value = AutomationState.Idle
    }

    /** Compteur affiché : 0 si le jour du moteur est passé (le moteur ne bascule qu'à son prochain appel). */
    private fun syncSwipesToday() {
        val today = clock.now().atZone(clock.zone).toLocalDate()
        _swipesToday.value = if (today.isAfter(engine.day)) 0 else engine.swipesToday
    }

    private suspend fun loop() {
        while (true) {
            awaitGate()
            if (!closeLeftProfile()) return
            val action = engine.nextAction()
            syncSwipesToday() // bascule de jour éventuelle
            reportState() // fin de session ou changement de jour
            when (action) {
                is Action.Swipe -> {
                    val result = whileGateOpen {
                        delay(action.delayMs)
                        // Plage fermée pendant le délai : pas de swipe ; le prochain
                        // nextAction() renverra OutsideHours.
                        if (canSwipeNow()) executor.execute(picker.pick()) else null
                    }
                    if (!handleSwipeResult(result)) return
                }
                is Action.OpenProfile -> {
                    // Tap → lecture → Retour → attente → swipe normal, tout sous la porte : app
                    // cible quittée (ou écran éteint, pause) pendant la lecture = pause, sans Retour
                    // ([profileOpenIn] le garde en mémoire pour la reprise).
                    val pkg = foregroundPackage.value
                    var backRefused = false
                    val result = whileGateOpen {
                        delay(action.delayMs)
                        if (!engine.isInActiveHours(clock.now())) return@whileGateOpen null
                        // App changée pendant le délai : pas de tap, swipe normal.
                        // Tap non abouti (ex. non calibré) : swipe normal, sans compter d'échec.
                        if (foregroundPackage.value == pkg &&
                            tapExecutor.tap(pkg) == DispatchResult.Completed
                        ) {
                            profileOpenIn = pkg
                            delay(action.readMs)
                            // Autre app cible au premier plan : pas de Retour chez elle (interrompu).
                            if (foregroundPackage.value != pkg) return@whileGateOpen null
                            if (!goBack()) {
                                backRefused = true
                                return@whileGateOpen null
                            }
                            profileOpenIn = null
                            delay(action.backPauseMs)
                        }
                        if (canSwipeNow()) executor.execute(picker.pick()) else null
                    }
                    if (backRefused) {
                        stopOnBackRefused()
                        return
                    }
                    if (!handleSwipeResult(result)) return
                }
                is Action.Rest -> {
                    _state.value = AutomationState.Resting
                    waitWallClock(action.durationMs)
                }
                is Action.CapReached -> {
                    _resumeAt.value = clock.now().plusMillis(action.waitMs)
                    _state.value = AutomationState.CapReached
                    waitWallClock(action.waitMs)
                    // Diagramme d'états : CapReached → Idle à minuit.
                    toIdle()
                    syncSwipesToday()
                    return
                }
                is Action.OutsideHours -> {
                    // Attente temporelle comme Resting ; la boucle reprend seule ensuite
                    // (Running, ou Paused si la porte est fermée).
                    _resumeAt.value = clock.now().plusMillis(action.waitMs)
                    _state.value = AutomationState.OutsideHours
                    waitWallClock(action.waitMs)
                    // Stop ou exception : remis à null par toIdle() (pas de finally, qui
                    // pourrait effacer l'échéance d'une nouvelle session).
                    _resumeAt.value = null
                    syncSwipesToday()
                }
            }
        }
    }

    /**
     * Profil laissé ouvert par une lecture interrompue ([profileOpenIn]) et app au premier
     * plan : Retour puis l'attente habituelle, avant toute nouvelle décision. Jamais en pause
     * (appelé porte ouverte, et annulé si elle se ferme). `false` si le Retour est refusé
     * (boucle arrêtée).
     */
    private suspend fun closeLeftProfile(): Boolean {
        val pkg = profileOpenIn ?: return true
        if (foregroundPackage.value != pkg) return true
        var refused = false
        whileGateOpen {
            if (!goBack()) {
                refused = true
                return@whileGateOpen
            }
            profileOpenIn = null
            delay(engine.drawProfileBackPauseMs())
        }
        if (refused) stopOnBackRefused()
        return !refused
    }

    /**
     * Swipe possible maintenant : dans la plage, et pas sur un profil laissé ouvert dans l'app
     * au premier plan (retour dans cette app pendant le délai : le tour suivant enverra Retour).
     */
    private fun canSwipeNow(): Boolean =
        engine.isInActiveHours(clock.now()) && (profileOpenIn == null || foregroundPackage.value != profileOpenIn)

    private fun stopOnBackRefused() {
        toIdle()
        _error.value = BACK_REFUSED_ERROR
    }

    /**
     * Compte un swipe abouti, ou un échec ; `false` si la boucle doit s'arrêter
     * ([MAX_CONSECUTIVE_FAILURES] échecs d'affilée). `null` = interrompu par la porte ou la
     * plage : ni compté ni échec.
     */
    private fun handleSwipeResult(result: DispatchResult?): Boolean {
        when (result) {
            null -> Unit
            DispatchResult.Completed -> {
                consecutiveFailures = 0
                engine.recordSwipe()
                syncSwipesToday()
                reportState()
            }
            DispatchResult.Failed, DispatchResult.Cancelled -> {
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    toIdle()
                    _error.value = "$MAX_CONSECUTIVE_FAILURES swipes refusés ou annulés d'affilée"
                    return false
                }
            }
        }
        return true
    }

    /** Signale l'état du moteur s'il a changé depuis le dernier signalement. */
    private fun reportState() {
        val state = engine.state
        if (state != lastReportedState) {
            lastReportedState = state
            onStateChanged(state)
        }
    }

    /** Attend [ms] selon [clock] (heure murale), par tranches d'au plus [WAIT_CHUNK_MS]. */
    private suspend fun waitWallClock(ms: Long) {
        val deadline: Instant = clock.now().plusMillis(ms)
        while (true) {
            val remaining = deadline.toEpochMilli() - clock.now().toEpochMilli()
            if (remaining <= 0) return
            delay(minOf(remaining, WAIT_CHUNK_MS))
        }
    }

    /** Suspend en Paused tant que la porte est fermée, puis passe en Running. */
    private suspend fun awaitGate() {
        if (!gateOpen()) {
            _state.value = AutomationState.Paused
            gate.first { it }
        }
        _state.value = AutomationState.Running
    }

    /**
     * Exécute [block] et l'annule dès que la porte se ferme (pause, app cible quittée,
     * écran éteint ou verrouillé) : renvoie alors `null`. Une annulation de la boucle
     * elle-même est propagée.
     */
    private suspend fun <T> whileGateOpen(block: suspend () -> T): T? = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            gate.first { !it }
            _state.value = AutomationState.Paused
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            watcher.cancel()
        }
    }

    companion object {
        /** Swipes non aboutis (Failed/Cancelled) d'affilée avant l'arrêt de la boucle. */
        const val MAX_CONSECUTIVE_FAILURES = 5

        /** Erreur d'arrêt quand le Retour après une ouverture de profil est refusé. */
        const val BACK_REFUSED_ERROR = "retour arrière refusé : profil peut-être resté ouvert"

        /** Tranche maximale d'attente des pauses longues, du plafond et des heures hors plage. */
        const val WAIT_CHUNK_MS = 30_000L
    }
}
