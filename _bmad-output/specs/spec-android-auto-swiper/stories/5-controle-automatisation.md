---
title: 'Story 5 — Boucle automatique et contrôle'
type: 'feature'
created: '2026-10-25'
status: 'draft'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture-diagrams.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Les briques existent (geste, exécution, décision, comportement) mais rien ne les enchaîne : l'utilisateur ne peut pas lancer une session automatique, la mettre en pause ou l'arrêter, et sur HyperOS le service peut être tué sans que l'app le signale (CAP-5).

**Approach:** Un `AutomationController` fait tourner la boucle (comportement → décision → geste → exécution) uniquement quand Tinder ou Bumble est au premier plan. L'utilisateur le pilote depuis l'app, un bouton flottant et une notification persistante, avec un Stop immédiat. L'écran d'accueil distingue « service actif » de « service activé mais arrêté par le système ».

## Boundaries & Constraints

**Always:**
- **Boucle :** à chaque tour, `BehaviorEngine.nextAction()` puis : `Swipe(d)` → attendre d → si une app cible est au premier plan, `DirectionPicker.pick()` → plan (`GestureGenerator`, `SwipeZone.DEFAULT`) → exécution (`GestureDispatcher`) → `recordSwipe()` seulement si `Completed` ; `Rest` → état Resting pendant la durée ; `CapReached` → état CapReached pendant l'attente. Chaque action renvoyée est exécutée (contrat de `nextAction()`).
- **États** (cf. `architecture-diagrams.md`) : Idle, Running, Paused, Resting, CapReached ; exposés en `StateFlow`.
- **Apps cibles :** `com.tinder` et `com.bumble.app` (liste dans une constante). Le service suit le paquet au premier plan via `TYPE_WINDOW_STATE_CHANGED` ; dès qu'il n'est plus une app cible, la boucle passe en Paused et le geste en cours est annulé (doigt levé) ; elle reprend seule quand une app cible revient. « Démarrer » hors app cible donne Paused jusqu'à l'ouverture de Tinder ou Bumble.
- **Stop :** annule la boucle et tout geste en cours en moins de 500 ms ; retour à Idle.
- **Commandes :** boutons Démarrer / Pause-Reprendre / Stop dans l'écran d'accueil ; bouton flottant (`TYPE_ACCESSIBILITY_OVERLAY`, aucune permission « superposition ») Play-Pause + Stop, placé dans le haut de l'écran (au-dessus de 25 % de la hauteur), hors de la bande où passent les swipes ; notification persistante tant que la boucle n'est pas Idle, avec l'état, le nombre de swipes du jour et les actions Pause/Reprendre et Stop. Demande de `POST_NOTIFICATIONS` (Android 13+) au premier Démarrer ; sans elle, la boucle fonctionne quand même.
- **Robustesse (points reportés story 3) :** `CoroutineExceptionHandler` sur le scope du service (une exception arrête la boucle en Idle et s'affiche, sans planter l'app) ; après un segment refusé alors que le doigt est posé, tentative de levée du doigt.
- **Service tué (constat HyperOS) :** l'écran d'accueil affiche trois états : inactif (réglage désactivé), actif (réglage activé et instance connectée), « activé mais arrêté par le système » (réglage activé, instance nulle) avec la consigne de le désactiver puis le réactiver.
- La logique de boucle est testable en JVM avec des faux (exécuteur de swipe, flux d'app au premier plan, temps virtuel `runTest`).

**Never:** pas de réglages modifiables ni de persistance du compteur (story 6) ; pas d'ouverture de profil (story 7) ; pas de plage horaire (lot suivant) ; pas de guide Xiaomi (story 6) ; pas de lecture du contenu de l'écran ; pas de permission `INTERNET` ni `SYSTEM_ALERT_WINDOW`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Démarrer dans Tinder | Idle, Tinder au premier plan, Démarrer | Running ; swipes exécutés au rythme du moteur ; `recordSwipe` après chaque `Completed` | N/A |
| Démarrer hors cible | Idle, Swipper au premier plan | Paused ; aucun swipe ; passe à Running quand Tinder arrive au premier plan | N/A |
| Quitter l'app cible | Running, l'utilisateur ouvre une autre app | Paused ; geste en cours annulé ; aucun swipe tant que l'app cible n'est pas revenue | N/A |
| Stop | n'importe quel état sauf Idle | Idle en < 500 ms ; plus aucun geste ; notification retirée | N/A |
| Pause manuelle | Running → Pause puis Reprendre | aucun swipe pendant la pause ; reprise ensuite | N/A |
| Fin de session | moteur renvoie `Rest` | Resting pendant la durée, puis Running | N/A |
| Plafond | moteur renvoie `CapReached` | CapReached pendant l'attente ; aucun swipe | N/A |
| Swipe refusé ou annulé | exécution `Failed` ou `Cancelled` | pas de `recordSwipe` ; la boucle continue | N/A |
| Exception | l'exécution lève une exception | boucle arrêtée en Idle, message d'erreur, app non plantée | `CoroutineExceptionHandler` |
| Service arrêté par le système | réglage activé, instance nulle | écran « activé mais arrêté par le système » ; Démarrer désactivé | N/A |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt` -- instance connectée, scope Main, `testSwipe`, `screenSize()` ; y ajouter le suivi du paquet au premier plan (`onAccessibilityEvent`), l'overlay, le contrôleur, le gestionnaire d'exceptions. Garder `testSwipe`.
- `app/src/main/java/com/swipper/app/service/StrokeSequencer.kt` -- branche `Outcome.Refused` : y tenter la levée du doigt quand `fingerDownAt` est posé (test à adapter : `dispatch refuse…` attend aujourd'hui l'absence de levée).
- `app/src/main/java/com/swipper/app/service/GestureDispatcher.kt` -- une instance par plan ; `execute(plan)`.
- `app/src/main/java/com/swipper/app/behavior/BehaviorEngine.kt`, `BehaviorConfig.kt`, `Clock.kt` (`SystemClock`) ; `decision/DirectionPicker.kt` ; `gesture/GestureGenerator.kt` -- à consommer, ne pas modifier.
- `app/src/main/java/com/swipper/app/service/AccessibilityStatus.kt` -- réglage observé ; combiner avec `SwipeAccessibilityService.instance` pour l'état « arrêté par le système ».
- `app/src/main/java/com/swipper/app/ui/HomeScreen.kt`, `MainActivity.kt`, `res/values/strings.xml` -- UI en français.
- `app/src/main/res/xml/accessibility_service_config.xml` -- déjà `typeWindowStateChanged`, `canRetrieveWindowContent=false`.
- Build : `JAVA_HOME` = JDK 21.

## Tasks & Acceptance

**Execution:**
- [ ] `app/src/main/java/com/swipper/app/control/AutomationController.kt` -- boucle, états `StateFlow`, `start()/pause()/resume()/stop()`, dépendances injectées (`SwipeExecutor` suspend, `StateFlow<String?>` du paquet au premier plan, moteur, sélecteur) -- cœur testable
- [ ] `app/src/main/java/com/swipper/app/control/TargetApps.kt` -- `com.tinder`, `com.bumble.app` -- apps cibles
- [ ] `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt` -- paquet au premier plan, contrôleur, `CoroutineExceptionHandler`, overlay -- intégration
- [ ] `app/src/main/java/com/swipper/app/control/FloatingControls.kt` -- overlay Play-Pause / Stop, dans le haut de l'écran, déplaçable verticalement en option -- commande flottante
- [ ] `app/src/main/java/com/swipper/app/control/AutomationNotification.kt` + `BroadcastReceiver` non exporté pour les actions + canal de notification -- commande par notification
- [ ] `app/src/main/java/com/swipper/app/service/StrokeSequencer.kt` + test -- levée du doigt après refus -- report story 3
- [ ] `app/src/main/java/com/swipper/app/ui/HomeScreen.kt`, `MainActivity.kt`, `strings.xml`, `AndroidManifest.xml` -- trois états du service, boutons Démarrer/Pause/Stop, état de la boucle, `POST_NOTIFICATIONS` -- UI
- [ ] `app/src/test/java/com/swipper/app/control/AutomationControllerTest.kt` -- une méthode par ligne de la matrice concernant la boucle (8 premières + Exception) -- matrice I/O

**Acceptance Criteria:**
- Given Tinder ouvert et Démarrer, when on observe 2 minutes, then des swipes partent à intervalles irréguliers et le compteur de la notification augmente.
- Given une session en cours, when on touche Stop sur le bouton flottant, then plus aucun geste n'est envoyé.
- Given un balayage depuis le bouton flottant vers le bas, when un swipe passe, then le bouton n'intercepte jamais le geste.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

**Pause et contrat de `nextAction()` :** l'action en cours n'est jamais rejouée. Si la pause survient pendant l'attente d'un `Swipe`, à la reprise on attend un nouveau délai court (tiré par le moteur via `nextAction()`, qui sera de toute façon un nouvel appel) plutôt que de swiper immédiatement. Un swipe interrompu par une pause n'est pas compté.

**Temps virtuel :** le contrôleur attend avec `delay()` ; en test, `runTest` avance le temps et une `Clock` dérivée de `testScheduler.currentTime` alimente le moteur, pour que délais, pauses et plafond restent cohérents.

**Overlay :** `WindowManager.LayoutParams(TYPE_ACCESSIBILITY_OVERLAY, FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN, …)` ajouté depuis le service, retiré dans `onUnbind`/`onDestroy`. Taille ≈ 48 dp par bouton.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent, APK produit

**Manual checks (if no CLI):**
- Émulateur : Démarrer avec le carrousel des applis récentes ne doit rien faire (pas une app cible) → Paused ; trace des appuis, bouton flottant visible en haut.
- Téléphone (pause de fin de story) : Tinder, Démarrer, 2–3 minutes, Pause/Reprendre, Stop depuis le bouton flottant et depuis la notification ; quitter Tinder → pause.
