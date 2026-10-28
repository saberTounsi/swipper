---
title: 'Écran maintenu allumé pendant la boucle de swipe'
type: 'bugfix'
created: '2026-10-28'
status: 'done'
baseline_commit: 'f321ab28638928eb5ac84716c4b85a957c2a363b'
route: 'dispatch'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Sur le Redmi, dans Tinder comme dans Bumble, le téléphone passe en veille au bout du délai d'extinction de l'écran ; la boucle voit l'écran éteint ou verrouillé (`deviceUsable` = faux), passe en Paused et ne swipe plus jusqu'à ce que l'utilisateur rallume l'écran.

**Approach:** Tant que la boucle doit swiper, la fenêtre du bouton flottant (déjà affichée pendant toute session) porte `FLAG_KEEP_SCREEN_ON` ; le drapeau est retiré dès que la boucle n'a plus à swiper. Aucune permission nouvelle, et le drapeau disparaît tout seul si le service est tué (la fenêtre part avec lui).

**Décision (2026-10-28) :** pendant la pause longue entre deux sessions (Resting), la veille est autorisée ; à la fin de la pause, la boucle attend que l'utilisateur rallume et déverrouille l'écran (comportement actuel de `deviceUsable`).

## Boundaries & Constraints

**Always:** Écran maintenu allumé seulement si l'état est Running et que l'utilisateur n'a pas mis en pause ; Idle, pause manuelle, Paused (app non cible), Resting, CapReached et OutsideHours laissent la veille normale. Le drapeau est mis à jour à chaque changement d'état ou de pause, sans recréer la fenêtre.

**Never:** Pas de `WakeLock` ni de permission `WAKE_LOCK` ; ne pas allumer un écran déjà éteint, ni déverrouiller le téléphone ; ne pas toucher à la logique de `deviceUsable` ni au comportement de la boucle.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Swipe en cours | Running, pas de pause manuelle | écran maintenu allumé | N/A |
| Pause manuelle | Running ou Resting, userPaused = vrai | veille normale | N/A |
| App quittée | Paused (app non cible au premier plan) | veille normale | N/A |
| Pause longue / plafond / hors plage | Resting, CapReached, OutsideHours | veille normale | N/A |
| Stop | Idle | veille normale (bouton éventuellement visible dans l'app cible) | N/A |
| Service tué | fenêtre retirée | drapeau perdu avec la fenêtre | N/A |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/control/FloatingControls.kt` -- fenêtre `TYPE_ACCESSIBILITY_OVERLAY` (`params`, `render(visible, state, userPaused)`, `show`/`hide`) : y ajouter/retirer `FLAG_KEEP_SCREEN_ON` puis `updateViewLayout`.
- `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt:173-182` -- collecte `combine(foreground, state, userPaused)` qui appelle `render` ; la fenêtre est visible tant que `state != Idle`, donc couvre tous les états concernés. Pas de changement nécessaire.
- `app/src/main/java/com/swipper/app/control/AutomationController.kt:31` -- `AutomationState` ; ne pas modifier.
- `app/src/test/java/com/swipper/app/control/` -- tests JVM purs (pas de Robolectric) : la règle de décision doit être une fonction pure.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/swipper/app/control/FloatingControls.kt` -- ajouter une fonction pure `shouldKeepScreenOn(state, userPaused)` (companion ou top-level `internal`) ; dans `render`, appliquer/retirer `FLAG_KEEP_SCREEN_ON` sur `params` (à la création et à chaque changement, via `updateViewLayout` seulement si le drapeau change) -- garde l'écran allumé sans permission.
- [x] `app/src/test/java/com/swipper/app/control/KeepScreenOnTest.kt` -- tester `shouldKeepScreenOn` pour chaque ligne de la matrice -- verrouille la règle.

**Acceptance Criteria:**
- Given une session Running dans Tinder ou Bumble avec un délai de veille de 30 s, when on ne touche pas le téléphone pendant 2 min, then l'écran reste allumé et le compteur continue d'augmenter.
- Given la même session, when on appuie sur Pause ou Stop, then l'écran s'éteint au délai de veille habituel.

## Implementation Notes

- `shouldKeepScreenOn` est une fonction `internal` de premier niveau dans `FloatingControls.kt` (testable sans Android). Le drapeau est appliqué avant `show()`, pour qu'une fenêtre recréée parte avec le bon drapeau, et `updateViewLayout` n'est appelé que s'il change.
- La ligne « Service tué » de la matrice relève du système (la fenêtre est retirée avec le service) : pas de test JVM possible, à vérifier sur le téléphone.

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | blind, edge | `updateViewLayout` en échec après modification de `params.flags` : drapeau désynchronisé, jamais réessayé | low | patch | Rare (fenêtre retirée par le système), correction directe : ancien drapeau remis et échec journalisé. |
| 2 | blind | Si `addView` échoue, pas de bouton donc pas de maintien de l'écran, sans signal | low | rejet | Échec déjà journalisé dans `show()` ; sans bouton la session n'est de toute façon pas pilotable. |
| 3 | blind | Aucune preuve sur le téléphone que `FLAG_KEEP_SCREEN_ON` est respecté par un `TYPE_ACCESSIBILITY_OVERLAY` sous HyperOS | maybe-false | rejet | Tranché par la vérification manuelle du spec (Redmi, veille 30 s), faite au point d'arrêt. |
| 4 | blind | Combinaisons état × pause pas toutes testées | low | patch | Test exhaustif sur `AutomationState.entries` ajouté. |
| 5 | blind, verif | Câblage `render` → `applyKeepScreenOn` → `updateViewLayout` non testé | medium | defer | Demande Robolectric ou un test instrumenté, absents du projet ; couvert par le test manuel. |
| 6 | blind | Variable `running` de `render` (vraie hors Idle) prête à confusion avec la règle du maintien | low | patch | Renommée `showPause`. |
| 7 | blind | `hide()` ne retire pas le drapeau de `params` | false | rejet | `show()` est privé et n'est appelé que par `render`, qui applique le drapeau avant. |
| 8 | blind | KDoc : fenêtre qui resterait après désactivation du service | false | rejet | `onUnbind`/`onDestroy` → `release()` → `overlay?.hide()`. |
| 9 | blind | Le diff relu n'inclut pas le spec ni `deferred-work.md` | false | rejet | Diff limité au code volontairement ; le spec est le fichier de déclarations. |

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent, APK construit

**Manual checks (if no CLI):**
- Redmi : délai de veille à 30 s, lancer la boucle dans Bumble, attendre 2 min sans toucher → écran allumé, swipes continus ; Pause → l'écran s'éteint au bout de 30 s.
