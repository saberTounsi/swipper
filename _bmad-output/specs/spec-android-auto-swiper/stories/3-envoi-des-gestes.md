---
title: 'Story 3 — Envoi des gestes sur l''écran'
type: 'feature'
created: '2026-10-24'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'a9b4dbeaf2e6f5e63129517afc0ab2e4dda05f6e'
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Les `SwipePlan` de la story 2 restent théoriques : rien ne les exécute à l'écran, donc impossible de vérifier qu'une app comme Tinder reconnaît le swipe (CAP-2, critère « sur appareil »).

**Approach:** Un `GestureDispatcher` exécute un `SwipePlan` segment par segment via `dispatchGesture` + `StrokeDescription.continueStroke`, depuis le service d'accessibilité. Un bouton de test sur l'écran d'accueil arme un swipe réel (droite ou gauche) déclenché 5 s plus tard, le temps de basculer sur l'app cible.

## Boundaries & Constraints

**Always:** Un segment = un `dispatchGesture` à un seul trait ; le 1er trait est `StrokeDescription(path, 0, durée, willContinue = !dernier)`, les suivants `précédent.continueStroke(path, 0, durée, willContinue = !dernier)` ; le segment suivant n'est envoyé que dans `onCompleted` du précédent. `onCancelled` ou un échec de `dispatchGesture` (retour `false`) termine l'exécution avec un résultat d'échec, sans envoyer la suite. L'exécution est une fonction `suspend` annulable : à l'annulation, plus aucun segment n'est envoyé et, si un trait « à continuer » est en cours, un dernier trait de 1 px / 16 ms avec `willContinue = false` lève le doigt. Le séquencement (ordre, arrêt, levée du doigt) vit dans une classe pure testable en JVM ; seule une fine couche construit `Path`/`StrokeDescription`. Le service expose son instance connectée (nulle hors connexion) pour que l'UI puisse l'appeler. Taille d'écran réelle lue via `WindowManager` (`currentWindowMetrics` API 30+, `getRealMetrics` avant).

**Never:** pas de boucle automatique, de ratio, de pauses, de bouton flottant ni de filtrage d'app cible (stories 4 et 5) ; le bouton de test ne fait qu'un swipe à la fois ; aucune modification de `gesture/` (story 2) hormis l'usage de son API.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Plan normal | plan de N segments, chaque dispatch réussit | N dispatchs dans l'ordre, 1er sans continuation, suivants en continuation ; `willContinue` vrai sauf au dernier ; résultat `Completed` | N/A |
| Segment annulé par le système | `onCancelled` au segment k | aucun segment > k envoyé ; résultat `Cancelled` | N/A |
| Dispatch refusé | `dispatchGesture` renvoie `false` au segment k | aucun segment > k ; résultat `Failed` | N/A |
| Annulation coroutine | job annulé pendant le segment k (k < dernier) | aucun segment > k ; un trait de levée (1 px, 16 ms, `willContinue = false`) envoyé en continuation ; `CancellationException` propagée | N/A |
| Annulation au dernier segment | job annulé pendant le dernier segment | pas de trait de levée (le doigt se lève déjà) | N/A |
| Service non connecté | bouton de test pressé, instance nulle | message « Service inactif », rien n'est armé | N/A |
| Bouton de test | service actif, « → dans 5 s » | message de confirmation ; 5 s plus tard, un swipe RIGHT avec un plan neuf de `GestureGenerator` sur la zone par défaut | N/A |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/gesture/SwipeModel.kt`, `GestureGenerator.kt` -- API à consommer : `GestureGenerator(Random).plan(ScreenSize, SwipeZone.DEFAULT, Direction)` ; `Segment.points`, `durationMs` ; continuité garantie. Ne pas modifier.
- `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt` -- squelette vide de la story 1 ; y ajouter l'instance connectée, un `CoroutineScope` (Main + SupervisorJob, annulé dans `onDestroy`/`onUnbind`) et `testSwipe(direction)`.
- `app/src/main/java/com/swipper/app/ui/HomeScreen.kt`, `MainActivity.kt` -- l'état actif/inactif existe déjà ; ajouter les deux boutons de test quand le service est actif.
- `app/src/main/res/values/strings.xml` -- textes en français.
- `_bmad-output/implementation-artifacts/deferred-work.md` -- point reporté de la story 2 : le geste finit presque à l'arrêt ; à observer lors de la vérification sur appareil (ne pas corriger ici).
- Build : `JAVA_HOME` = JDK 21.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/swipper/app/service/StrokeSequencer.kt` -- classe pure : `suspend fun run(plan, sink: StrokeSink): DispatchResult` avec `interface StrokeSink { fun first(segment, willContinue, onDone: (Boolean) -> Unit): Boolean; fun next(segment, willContinue, onDone): Boolean; fun lift(at: Point, onDone): Boolean }` et `DispatchResult { Completed, Cancelled, Failed }` -- séquencement testable sans Android
- [x] `app/src/main/java/com/swipper/app/service/GestureDispatcher.kt` -- `StrokeSink` Android : construit `Path` (moveTo + lineTo des points), garde le dernier `StrokeDescription` pour `continueStroke`, appelle `service.dispatchGesture` avec un callback -- couche Android fine
- [x] `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt` -- instance connectée (`StateFlow<SwipeAccessibilityService?>`), scope, taille d'écran, `testSwipe(direction, delayMs = 5000)` -- point d'entrée
- [x] `app/src/main/java/com/swipper/app/ui/HomeScreen.kt`, `MainActivity.kt`, `res/values/strings.xml` -- boutons « Swipe test → dans 5 s » et « ← dans 5 s » + Toast de confirmation ou « Service inactif » -- déclenchement manuel
- [x] `app/src/test/java/com/swipper/app/service/StrokeSequencerTest.kt` -- un test par ligne de la matrice concernant le séquencement (5 premières), avec un faux `StrokeSink` -- matrice I/O

**Acceptance Criteria:**
- Given le service actif et Tinder (ou une autre vue à cartes) au premier plan, when le swipe test → se déclenche, then la carte part à droite ; idem à gauche.
- Given un swipe en cours, when on l'observe avec « Afficher les appuis » (options développeur), then le tracé est courbe et continu.

## Implementation Notes

- Détection de l'annulation : au lieu d'`invokeOnCancellation` (Design Notes), le séquenceur s'appuie sur l'exception levée par `suspendCancellableCoroutine` à l'annulation, sur `ensureActive()` avant chaque envoi et sur `cont.isActive` dans les callbacks (un callback tardif est ignoré). La levée du doigt est envoyée dans `withContext(NonCancellable)`.
- La levée attend son callback au plus `LIFT_TIMEOUT_MS` = 500 ms (`withTimeoutOrNull`), pour qu'un callback qui n'arrive jamais ne bloque pas l'annulation.
- Pas de trait de levée sur `Failed` (le système refuserait probablement aussi la levée) ni sur `Cancelled` (le système a déjà levé le doigt).
- Un `GestureDispatcher` par plan : il garde le dernier `StrokeDescription` pour `continueStroke`.
- `kotlinx-coroutines-android` 1.7.3 ajouté explicitement (avant, il n'arrivait que par dépendance transitive) et `kotlinx-coroutines-test` 1.7.3 pour les tests (version déjà dans le cache Gradle).
- Le résultat du swipe test (terminé / annulé par le système / refusé) est affiché dans un Toast, en plus du log `SwipeService`, pour la vérification sur appareil.
- Vérification émulateur (AVD `swipper35`, 2026-10-24) : swipe → dans l'app = un seul trait courbe et continu (7 segments, 180 ms), doigt levé à la fin ; sur l'écran des applis récentes, le swipe → fait défiler le carrousel d'une carte (geste reconnu comme un lancer) ; Toast « Swipe test → : terminé » affiché. Reste : Tinder sur téléphone réel (done_checkpoint).

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | blind, edge, verif | Segment k > 0 refusé après un trait « à continuer » : pas de levée du doigt | maybe-false | defer | `dispatchGesture` ne renvoie `false` qu'en cas de service déconnecté ou de geste invalide ; on ne sait pas si le doigt injecté reste posé (le trait de levée serait sans doute refusé aussi). À observer sur appareil ; serait medium. |
| 2 | blind, edge | Exception levée pendant l'envoi → plantage de l'app (scope sans `CoroutineExceptionHandler`) | low | defer | Aucun chemin actuel ne lève : points ≥ 0, durées < max, `continueStroke` toujours sur un trait `willContinue`. À couvrir avec la boucle automatique (story 5). |
| 3 | blind | Levée envoyée après `onUnbind`/`onDestroy` | false | rejet | `dispatchGesture` sur un service déconnecté renvoie `false` sans effet ; le timeout de 500 ms borne l'attente. |
| 4 | blind | Délai de 5 s répété dans le code et cinq textes | low | rejet | Bouton de test temporaire ; la correction ajoute des arguments de format. |
| 5 | blind | Résultat du swipe de test seulement dans les logs | medium | patch | Pendant le test sur téléphone, impossible de distinguer un geste refusé d'un geste ignoré par l'app cible. |
| 6 | blind, verif | Lignes « Service non connecté » et « Bouton de test » et couche `GestureDispatcher` non testées | medium | defer | Code Android sans Robolectric ; vérification manuelle (émulateur fait pour le chemin connecté). |
| 7 | blind | Test du timeout de levée trop faible | low | patch | Ne vérifie ni l'annulation ni le temps virtuel écoulé ; correction directe. |
| 8 | blind | Coroutines 1.7.3 anciennes pour Kotlin 2.0.21 | low | rejet | Fonctionne ; 29 + 8 tests verts ; montée de version sans défaut identifié. |
| 9 | blind, edge | `last` mis à jour avant `dispatchGesture`, même en cas de refus | false | rejet | Une instance par plan (`testSwipe` en crée une à chaque fois) et un refus termine l'exécution : le trait refusé n'est jamais prolongé. |
| 10 | blind | Design Notes (`invokeOnCancellation`) ≠ implémentation | low | patch | Écart à consigner dans Implementation Notes. |
| 11 | edge | Levée envoyée pendant qu'un segment s'exécute encore (point de départ ≠ position du doigt) | maybe-false | defer | Le système pourrait annuler la levée et envoyer ACTION_CANCEL, ce qui lève aussi le doigt. À observer en story 5 (Stop pendant un swipe) ; serait low. |
| 12 | edge | Levée au bord droit : x + 1 hors de l'écran | low | rejet | `StrokeDescription` ne refuse que les coordonnées négatives ; 1 px au-delà est sans effet. |
| 13 | edge | Exception non liée à l'annulation : pas de levée | low | defer | Même cause que #2. |

## Design Notes

`dispatchGesture` exécute ses callbacks sur le thread principal quand on passe `handler = null`. Le séquenceur convertit chaque callback en reprise de coroutine (`suspendCancellableCoroutine`) ; l'annulation est détectée via `invokeOnCancellation`/`CancellationException`, puis la levée du doigt est envoyée dans un bloc `NonCancellable`. Exemple de `lift` : `last.continueStroke(Path().apply { moveTo(x, y); lineTo(x + 1, y) }, 0, 16, false)`.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent, APK produit

**Manual checks (if no CLI):**
- Émulateur : bouton test puis écran d'accueil du lanceur ; « Afficher les appuis » activé ; la trace du geste est visible et la page ou le panneau défile dans le bon sens.
- Téléphone réel (pause de fin de story) : Tinder, un swipe dans chaque sens, la carte part ; noter si elle revient en place (point reporté de la story 2).
