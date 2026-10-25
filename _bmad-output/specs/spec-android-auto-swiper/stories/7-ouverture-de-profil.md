---
title: 'Story 7 — Ouverture occasionnelle de profil'
type: 'feature'
created: '2026-10-25'
status: 'draft'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Une vraie personne ouvre de temps en temps un profil avant de décider ; Swipper ne le fait jamais, ce qui laisse un temps passé par profil anormalement court et régulier (CAP-4, comportement).

**Approach:** Avec une probabilité réglable, la boucle ouvre le profil (un tap à une position calibrée pour chaque app), « lit » quelques secondes, revient en arrière, puis swipe. La position du tap se calibre une fois par app : l'utilisateur touche l'endroit qui ouvre le profil sur un calque transparent.

## Boundaries & Constraints

**Always:**
- **Calibration** : depuis l'écran de réglages, « Calibrer Tinder » / « Calibrer Bumble » → l'utilisateur ouvre l'app ; un calque plein écran (`TYPE_ACCESSIBILITY_OVERLAY`, semi-transparent, avec une consigne) capte un seul toucher, enregistre sa position en fractions d'écran pour l'app au premier plan, puis disparaît. Annulable (bouton ou Retour). Position persistée avec les réglages.
- **Action** : le moteur renvoie `OpenProfile(readMs)` à la place d'un `Swipe` avec la probabilité `profileOpenProbability` (réglable 0–30 %, défaut 8 %), seulement si l'app au premier plan a une position calibrée ; sinon jamais. `readMs` tiré dans 2–6 s.
- **Exécution** : tap humain (durée 60–140 ms, décalage aléatoire ±1,5 % de la largeur autour du point calibré, micro-glissement ≤ 4 px), attente `readMs`, retour par `performGlobalAction(GLOBAL_ACTION_BACK)`, attente 0,6–1,5 s, puis le swipe normal (direction et `recordSwipe` comme d'habitude). Si l'app cible quitte le premier plan pendant la lecture : pause comme ailleurs, sans retour arrière.
- Le tap passe par le même mécanisme que les swipes (`dispatchGesture`) et respecte les mêmes garde-fous (écran allumé, déverrouillé, app cible au premier plan).
- Logique de décision et génération du tap en Kotlin pur, testées en JVM.

**Never:** pas de lecture du contenu de l'écran ; pas de décision selon le profil ; pas de défilement des photos ; pas de calibration automatique.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fréquence | probabilité 8 %, Tinder calibré, 1 000 actions | 5–11 % de `OpenProfile` parmi les actions de swipe | N/A |
| Non calibré | probabilité 8 %, Bumble non calibré | aucun `OpenProfile` dans Bumble | N/A |
| Probabilité 0 | 0 % | jamais de `OpenProfile` | N/A |
| Tap humain | point calibré (0,50 ; 0,80) sur 1080×2400 | tap dans ±1,5 % de largeur autour du point, durée 60–140 ms, jamais deux taps identiques sur 100 | N/A |
| Séquence | `OpenProfile(3 000)` dans Tinder | tap → 3 s → Retour → 0,6–1,5 s → swipe ; un seul `recordSwipe` | N/A |
| Quitter pendant la lecture | l'utilisateur change d'app pendant les 3 s | pause, pas de Retour envoyé, pas de swipe | N/A |
| Calibration | calque affiché dans Tinder, toucher en (540, 1900) | position (0,50 ; 0,79) enregistrée pour `com.tinder` ; calque retiré | N/A |
| Calibration annulée | Retour sur le calque | rien d'enregistré ; calque retiré | N/A |

</frozen-after-approval>

## Code Map

- `behavior/BehaviorEngine.kt` (`Action`, ordre des contrôles), `behavior/BehaviorConfig.kt` -- nouvelle action et probabilité.
- `control/AutomationController.kt` -- exécution de la séquence ; injection d'un « exécuteur de tap » et d'une action Retour pour les tests.
- `gesture/` -- ajouter un générateur de tap humain (pur) à côté de `GestureGenerator`.
- `service/GestureDispatcher.kt`, `StrokeSequencer.kt` -- un tap est un plan à un seul segment court.
- `settings/` (story 6) -- persister les positions calibrées et la probabilité ; écran de réglages : boutons de calibration.
- `control/FloatingControls.kt` -- modèle d'overlay pour le calque de calibration.

## Tasks & Acceptance

**Execution:**
- [ ] `gesture/TapGenerator.kt` + test -- tap humain autour d'un point calibré -- pur
- [ ] `behavior/BehaviorEngine.kt`, `BehaviorConfig.kt` + tests -- `OpenProfile(readMs)`, probabilité, disponibilité de la calibration injectée -- moteur
- [ ] `control/AutomationController.kt` + tests -- séquence tap → lecture → Retour → swipe ; pause pendant la lecture -- boucle
- [ ] `control/CalibrationOverlay.kt`, `service/SwipeAccessibilityService.kt` -- calque de calibration -- calibration
- [ ] `settings/*`, `ui/SettingsScreen.kt`, `strings.xml` -- probabilité, positions, boutons « Calibrer » -- réglages

**Acceptance Criteria:**
- Given Tinder calibré et une session de quelques minutes, when on observe, then de temps en temps un profil s'ouvre, reste affiché quelques secondes, se referme, puis la carte est swipée.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent

**Manual checks (if no CLI):**
- Téléphone : calibrer Tinder (toucher la zone du nom / de l'âge en bas de la carte), régler la probabilité à 30 % pour l'essai, observer quelques ouvertures de profil, puis remettre 8 %.
