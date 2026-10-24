---
title: 'Story 4 — Décision et moteur de comportement'
type: 'feature'
created: '2026-10-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '683064265420974215e05d462c01266f64f57dad'
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Rien ne décide encore dans quel sens swiper ni à quel rythme. Sans ça, une boucle automatique serait mécanique : délais constants, sessions sans fin, aucun plafond (CAP-3, CAP-4).

**Approach:** Deux briques en Kotlin pur, sans Android, testées avec un aléa et une horloge injectés : `DirectionPicker` (droite/gauche selon un ratio) et `BehaviorEngine` (délai avant le prochain swipe, fin de session et longue pause, plafond quotidien jusqu'à minuit local). La story 5 les branchera sur le service.

## Boundaries & Constraints

**Always:**
- Packages `com.swipper.app.decision` et `com.swipper.app.behavior`, zéro import `android.*`/`androidx.*`. `java.time` autorisé (minSdk 26).
- Aléa (`kotlin.random.Random`) et horloge (`interface Clock { fun now(): Instant; val zone: ZoneId }`) injectés ; le moteur ne lit jamais l'heure système directement.
- Paramètres dans `BehaviorConfig` (validés par `require`), valeurs par défaut de `architecture.md` : délai log-normal de médiane 1 800 ms borné à [800 ; 8 000] ms ; 5 % de « distractions » de 10–40 s ; session de 25–60 swipes ; pause longue de 15–90 min ; plafond de 150 swipes par jour. Ratio par défaut : 70 % à droite.
- Le moteur compte les swipes via `recordSwipe()`, appelé par l'appelant seulement quand un swipe a réellement eu lieu ; `nextAction()` ne modifie pas le compteur de swipes.
- Changement de jour (date locale de `clock.zone`) : le compteur du jour repart à zéro.
- Le moteur expose son état minimal (`swipesToday`, `day`) et accepte un état initial, pour que la story 6 puisse le persister.

**Never:** pas d'ouverture de profil (story 7), pas de persistance (story 6), pas de branchement au service ni de coroutine (story 5), pas de lecture de réglages Android.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ratio | ratio 0,70, 1 000 décisions, graine fixe | 65–75 % de RIGHT | N/A |
| Ratios extrêmes | ratio 0,0 puis 1,0 | toujours LEFT, puis toujours RIGHT | N/A |
| Délais irréguliers | 1 000 actions Swipe | délais tous dans [800 ; 40 000] ms ; au moins 100 valeurs distinctes ; médiane hors distractions dans [1 400 ; 2 300] ms ; 2–8 % de délais ≥ 10 000 ms | N/A |
| Session bornée | swipes enregistrés en boucle | jamais plus de `sessionMax` swipes entre deux `Rest` ; chaque session ≥ `sessionMin` (sauf si coupée par le plafond) ; `Rest` de 15–90 min | N/A |
| Plafond | 150 swipes enregistrés le même jour | `nextAction()` renvoie `CapReached` avec une attente jusqu'au prochain minuit local | N/A |
| Nouveau jour | horloge avancée au lendemain | compteur à 0, `Swipe` de nouveau possible | N/A |
| Heure d'été | zone Europe/Paris, la veille du passage à l'heure d'été/hiver | attente `CapReached` = durée réelle jusqu'à minuit local (23 h ou 25 h comptées correctement) | N/A |
| Config invalide | ratio hors [0;1], min > max, valeurs ≤ 0, plafond < 1 | `IllegalArgumentException` | `require` avec message |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/gesture/SwipeModel.kt` -- `Direction { LEFT, RIGHT }` à réutiliser (ne pas dupliquer).
- `app/src/main/java/com/swipper/app/gesture/GestureGenerator.kt` -- style maison : KDoc en français, constantes `internal`, `require` avec message.
- `app/src/test/java/com/swipper/app/gesture/GestureGeneratorTest.kt` -- style des tests (JUnit 5, graine fixe, noms en français entre backticks).
- `architecture.md` § « Moteur de comportement » et diagramme d'états (`architecture-diagrams.md`) : `Running → Resting` en fin de session, `CapReached → Idle` à minuit.
- Build : `JAVA_HOME` = JDK 21.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/swipper/app/decision/DirectionPicker.kt` -- `class DirectionPicker(random, rightRatio: Double = 0.70)` + `fun pick(): Direction` -- CAP-3
- [x] `app/src/main/java/com/swipper/app/behavior/Clock.kt` -- `interface Clock` + `SystemClock` (Instant.now(), ZoneId.systemDefault()) -- horloge injectable
- [x] `app/src/main/java/com/swipper/app/behavior/BehaviorConfig.kt` -- data class + valeurs par défaut + validation -- paramètres
- [x] `app/src/main/java/com/swipper/app/behavior/BehaviorEngine.kt` -- `sealed interface Action { Swipe(delayMs), Rest(durationMs), CapReached(waitMs) }` ; `nextAction()`, `recordSwipe()`, `swipesToday`, `day`, état initial optionnel -- CAP-4
- [x] `app/src/test/java/com/swipper/app/decision/DirectionPickerTest.kt`, `app/src/test/java/com/swipper/app/behavior/BehaviorEngineTest.kt` -- un test par ligne de la matrice, avec une `FakeClock` -- matrice I/O

**Acceptance Criteria:**
- Given les packages `decision` et `behavior`, when on cherche `\bandroidx?\.` hors commentaires, then rien n'est trouvé.
- Given la même graine et la même horloge, when on rejoue la même suite d'appels, then les actions sont identiques.

## Implementation Notes

- Délai : log-normale **tronquée** (valeurs hors [delayMinMs ; delayMaxMs] retirées, jusqu'à 20 tirages, puis repli uniforme dans les bornes) au lieu d'un `coerceIn`, pour ne pas répéter exactement la borne.
- Changement de jour : seulement vers l'avant (`today.isAfter(day)`) ; une horloge reculée ne remet pas le compteur à zéro. Le passage au jour suivant démarre aussi une nouvelle session (compteur de session à 0, nouvelle cible).
- `nextAction()` engage l'action renvoyée (une fin de session tire une nouvelle cible) : l'appelant doit exécuter chaque action reçue.

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | edge, blind | Horloge reculée ou changement de fuseau vers l'ouest → compteur remis à 0, plafond contourné | medium | patch | `today != day` réinitialise aussi quand la date recule. |
| 2 | edge, blind, verif | La session n'est pas réinitialisée au changement de jour → `Rest` de 15–90 min inutile après minuit | medium | patch | Le test le tolère explicitement (`if (first is Action.Rest)`). |
| 3 | blind | Délais écrêtés : ~3,6 % exactement à 800 ms | medium | patch | Valeur exacte répétée, visible côté serveur dans le rythme ; troncature par nouveau tirage (reste dans les bornes gelées). |
| 4 | blind, edge | Progression de session non persistée → session relancée sans pause après redémarrage | medium | defer | Persistance = story 6 ; y ajouter sessionSwipes/sessionTarget. |
| 5 | blind, edge | `nextAction()` engage l'action (l'appeler deux fois saute une pause) | low | patch | Contrat à documenter pour la story 5. |
| 6 | blind | Reprise chaque jour à minuit pile, et swipes possibles toute la nuit | medium | defer | Nouvelle fonctionnalité (fenêtre d'activité, décalage aléatoire au réveil) : décision produit, à proposer pour la story 5/6. |
| 7 | blind | `Clock` maison au lieu de `java.time.Clock` | low | rejet | Interface minimale suffisante, cohérente avec l'injection voulue. |
| 8 | edge, blind | Débordement avec `Long.MAX_VALUE` / `Int.MAX_VALUE` dans la config | low | rejet | Valeurs irréalistes ; la future UI de réglages bornera les saisies. |
| 9 | blind | Tests de validation sans σ NaN/Infini ni médiane < min | low | patch | Ajout direct. |
| 10 | blind | Test « pas d'import Android » dépendant du répertoire de travail | low | rejet | Même approche que les tests de la story 2, fiable sous Gradle. |
| 11 | edge | `recordSwipe()` sans `Swipe` préalable peut dépasser le plafond | low | rejet | Contrat d'appel de la story 5. |
| 12 | edge | État initial avec une date future ignoré | low | rejet | Couvert par #1 (bascule seulement vers l'avant). |
| 13 | edge | Fuseau relu à chaque appel | low | rejet | Avec #1, un changement de fuseau ne peut plus réinitialiser à tort. |
| 14 | verif | Bascule de jour dans `recordSwipe()` non testée | medium | patch | Supprimer l'appel laisse tous les tests verts. |
| 15 | verif | Borne haute des délais non testée | medium | patch | `coerceAtLeast` au lieu de `coerceIn` passerait inaperçu. |

## Design Notes

**Ordre des contrôles dans `nextAction()` :** 1) changement de jour → remise à zéro ; 2) plafond atteint → `CapReached` ; 3) session terminée (swipes de la session ≥ cible tirée) → `Rest`, puis nouvelle cible tirée et compteur de session remis à 0 ; 4) sinon `Swipe(délai)`. Le délai log-normal : `exp(ln(médiane) + σ·N(0,1))` avec σ ≈ 0,45, puis borné ; avec 5 % de chances, une distraction de 10–40 s remplace ce délai. `N(0,1)` par Box-Muller à partir du `Random` injecté.

**Attente jusqu'à minuit :** `ZonedDateTime.now(clock).toLocalDate().plusDays(1).atStartOfDay(zone)` moins maintenant, en millisecondes ; gère les jours de 23 h et 25 h.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest` -- expected: tous les tests passent (43 existants + nouveaux)
