---
title: 'Plage horaire d''activité et reprise aléatoire'
type: 'feature'
created: '2026-10-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'c904252d5f5928fa9c79667f047d9c03adf4fafd'
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/stories/4-decision-et-comportement.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/stories/5-controle-automatisation.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Le moteur peut swiper toute la nuit et reprend pile à minuit après le plafond : deux motifs faciles à repérer (décision utilisateur du 2026-10-25).

**Approach:** Ajouter au moteur une plage horaire d'activité (9 h–23 h par défaut) et une reprise à une heure tirée au hasard après l'ouverture de la plage ; la boucle attend hors plage puis reprend seule.

## Boundaries & Constraints

**Always:**
- `BehaviorConfig` : `activeStartMinute` (défaut 9 × 60), `activeEndMinute` (défaut 23 × 60), minutes depuis minuit local, dans [0 ; 1440), début ≠ fin ; une plage qui traverse minuit (début > fin, ex. 20 h–2 h) est acceptée. `resumeJitterMinMs` / `resumeJitterMaxMs` (défaut 2–45 min).
- Nouvelle action `Action.OutsideHours(waitMs)` : renvoyée par `nextAction()` quand l'heure locale est hors plage ; `waitMs` = durée réelle jusqu'à la prochaine ouverture de la plage + un décalage aléatoire dans [jitterMin ; jitterMax], sans dépasser la fermeture suivante de la plage. Heure d'été gérée comme pour `CapReached`.
- Ordre des contrôles dans `nextAction()` : changement de jour → plafond → hors plage → fin de session → Swipe.
- `CapReached.waitMs` : attente jusqu'à la prochaine ouverture de plage (et non plus minuit) + décalage aléatoire.
- `AutomationController` : nouvel état `OutsideHours` ; attente sur l'horloge murale par tranches (comme Resting/CapReached) ; à la fin, la boucle reprend seule (Running, ou Paused si l'app cible n'est pas au premier plan) ; CapReached garde sa fin actuelle (Idle). Pause manuelle et Stop se comportent comme pendant Resting.
- UI et notification : libellé « hors plage horaire (reprise vers HH:mm) ».
- Kotlin pur pour le moteur ; tests avec `FakeClock` et temps virtuel.

**Never:** pas d'écran de réglages (story 6) ; pas de changement de la géométrie des gestes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Dans la plage | 14 h, plage 9–23 h | comportement inchangé (Swipe / Rest / CapReached) | N/A |
| Avant la plage | 7 h 30 | `OutsideHours`, attente = jusqu'à 9 h + 2–45 min | N/A |
| Après la plage | 23 h 10 | `OutsideHours`, attente jusqu'au lendemain 9 h + 2–45 min | N/A |
| Reprise variée | 100 attentes `OutsideHours` à la même heure | au moins 50 valeurs distinctes ; aucune reprise pile à 9 h 00 | N/A |
| Plage à cheval sur minuit | plage 20 h–2 h ; à 1 h → dans la plage ; à 3 h → hors plage jusqu'à 20 h + décalage | N/A |
| Plafond | plafond atteint à 15 h | `CapReached`, attente jusqu'au lendemain 9 h + décalage | N/A |
| Heure d'été | veille du changement d'heure (Europe/Paris), après 23 h | attente comptée en durée réelle | N/A |
| Boucle hors plage | contrôleur en marche, moteur renvoie `OutsideHours` | état OutsideHours, aucun swipe, puis reprise seule à la fin de l'attente | N/A |
| Config invalide | minute hors [0 ; 1440), début = fin, jitter min > max ou < 0 | `IllegalArgumentException` | `require` |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/behavior/BehaviorConfig.kt`, `BehaviorEngine.kt` (`Action`, `nextAction()`, `millisUntilNextMidnight()`), `test/.../behavior/BehaviorEngineTest.kt` (`FakeClock`, `paris(...)`).
- `app/src/main/java/com/swipper/app/control/AutomationController.kt` (`AutomationState`, attente par tranches de Resting/CapReached), `AutomationNotification.kt`, `ui/HomeScreen.kt`, `res/values/strings.xml`, `test/.../control/AutomationControllerTest.kt`.
- Build : `JAVA_HOME` = JDK 21.

## Tasks & Acceptance

**Execution:**
- [x] `behavior/BehaviorConfig.kt`, `behavior/BehaviorEngine.kt` -- plage, jitter, `OutsideHours`, `CapReached` jusqu'à l'ouverture -- moteur
- [x] `control/AutomationController.kt`, `AutomationNotification.kt`, `ui/HomeScreen.kt`, `strings.xml` -- état OutsideHours et libellés -- contrôle et UI
- [x] `test/.../behavior/BehaviorEngineTest.kt`, `test/.../control/AutomationControllerTest.kt` -- une méthode par ligne de la matrice ; adapter les tests du plafond (attente jusqu'à l'ouverture et non minuit) -- matrice I/O

**Acceptance Criteria:**
- Given les tests existants, when on les relance, then ils passent tous (adaptés seulement là où la règle du plafond change).

## Implementation Notes

- Plage : début inclus, fin exclue, comparée sur l'heure locale (`LocalTime`) de `clock.zone`. Prochaine ouverture/fermeture = première occurrence strictement après l'instant de référence (`ZonedDateTime.of`, une heure sautée au passage à l'heure d'été est décalée d'autant) ; les attentes sont des `Duration` entre instants, donc en durée réelle. `nextAction()` lit l'horloge une seule fois et cette valeur sert au calcul de l'attente.
- Décalage de reprise : uniforme dans [jitterMin ; jitterMax], ramené à [min(jitterMin, latest) ; min(jitterMax, latest)] avec latest = durée de la plage ouverte − 1 min (`CLOSING_MARGIN_MS`, borné à 0) : la reprise tombe au moins 1 min avant la fermeture, pour qu'un retard d'ordonnancement ne fasse pas sauter toute la plage.
- `CapReached` : reprise à la première ouverture de plage **à partir du prochain minuit local** (le compteur ne repart à zéro qu'à minuit). Pour 9–23 h c'est le lendemain 9 h ; pour une plage à cheval sur minuit (20 h–2 h), minuit est dans la plage, donc reprise à minuit + décalage (une reprise à 20 h le même jour retomberait sur le plafond).
- `OutsideHours` clôt la session (compteur de session à 0, nouvelle cible) : après une longue interruption le même jour (plage 20 h–2 h, hors plage à 3 h), la reprise démarre une nouvelle session au lieu d'enchaîner un `Rest` juste après.
- Contrôleur : après le délai d'un `Swipe`, la plage est revérifiée (`BehaviorEngine.isInActiveHours(Instant)`, fonction pure) ; hors plage le swipe est abandonné (ni compté ni échec) et le `nextAction()` suivant renvoie `OutsideHours`.
- Contrôleur : `resumeAt: StateFlow<Instant?>` = échéance de l'attente en `OutsideHours` **et** `CapReached` (`null` sinon), remis à `null` à la fin de l'attente et par `toIdle()` (Stop, fin du plafond, exception ; pas de `finally`, qui pourrait effacer l'échéance d'une nouvelle session après Stop + Démarrer). `zone` expose le fuseau de l'horloge de la boucle.
- Libellés : « hors plage horaire (reprise vers HH:mm) » et « plafond du jour atteint (reprise possible vers HH:mm) ». `AutomationNotification.stateText(context, state, resumeAt, zone)` est le seul point d'entrée public (`stateLabel` privé, car deux ressources attendent un argument) ; l'heure est formatée en 24 h dans le fuseau du contrôleur (`formatResumeTime`, fonction de premier niveau testée par `AutomationNotificationFormatTest`), « --:-- » si inconnue.
- Tests existants adaptés au-delà de la règle du plafond : départ par défaut du banc du contrôleur passé de 08:00 à 10:00 UTC (08:00 est désormais hors plage) ; test « minuit pendant Running » sur une plage 20 h–2 h ; test du plafond du contrôleur qui attend le lendemain 9 h + décalage. Tests moteur : les `when` sur `Action` gèrent `OutsideHours` (avance de l'horloge ; le test de session vérifie que le moteur a remis la session à zéro) ; le test DST du plafond utilise un décalage fixe de 5 min.
- Vérification (2026-10-25) : `./gradlew :app:testDebugUnitTest :app:assembleDebug` vert (104 tests) avant les corrections de revue ; après corrections, `BehaviorEngineTest` (27), `AutomationControllerTest` (20) et `AutomationNotificationFormatTest` (4) verts. Non vérifié sur appareil.

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | blind, edge | Session non réinitialisée après `OutsideHours` (même jour) | medium | patch | Le harnais de test masque le cas en remettant `inSession` à 0. |
| 2 | edge | Swipe exécuté après la fermeture si son délai chevauche l'heure de fin | medium | patch | Pas de revérification de la plage après le délai. |
| 3 | edge | Reprise possible 1 ms avant la fermeture ; deux lectures de l'horloge | low | patch | Marge et lecture unique : corrections directes. |
| 4 | blind | Heure de reprise absente pendant le plafond (attente jusqu'au lendemain matin) | medium | patch | `resumeAt` défini seulement pour `OutsideHours`. |
| 5 | blind | `CapReached` finit en Idle : attente allongée sans bénéfice | low | rejet | Fin en Idle conservée explicitement dans l'intention gelée ; l'utilisateur relance chaque jour. |
| 6 | blind, edge | Fuseau d'affichage ≠ fuseau du moteur ; `stateLabel` renvoie un format à argument | low | patch | Corrections directes. |
| 7 | blind | Plage à cheval sur minuit : jusqu'à 2 plafonds par nuit | low | rejet | Plage par défaut 9 h–23 h ; cas limite d'une configuration future. |
| 8 | blind | Bornes de plage dans l'heure sautée du passage à l'heure d'été | low | rejet | Bornes à 2 h–3 h irréalistes. |
| 9 | blind | Contrôle `resumeJitterMaxMs ≥ 0` redondant, tests de config dupliqués | low | rejet | Cosmétique. |
| 10 | blind | Diagramme d'états (architecture) non mis à jour avec `OutsideHours` | low | defer | Compagnon de spec à re-dériver via bmad-spec. |
| 11 | verif | Formatage de l'heure de reprise non testé | medium | patch | Fuseau UTC, motif `hh:mm` ou repli nul changés : aucun test n'échoue. |
| 12 | edge | Décalage nul possible pour une plage très courte | low | rejet | Plage par défaut de 14 h. |
| 13 | edge | Tests existants adaptés au-delà de la règle du plafond | — | note | Signalé par l'implémentation ; consigné dans les notes. |

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent
