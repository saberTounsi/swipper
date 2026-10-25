---
title: 'Story 7 — Ouverture occasionnelle de profil'
type: 'feature'
created: '2026-10-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'bc8ad71373507d23c1f4c16b5009fe2b3b9c6aff'
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
- [x] `gesture/TapGenerator.kt` + test -- tap humain autour d'un point calibré -- pur
- [x] `behavior/BehaviorEngine.kt`, `BehaviorConfig.kt` + tests -- `OpenProfile(readMs)`, probabilité, disponibilité de la calibration injectée -- moteur
- [x] `control/AutomationController.kt` + tests -- séquence tap → lecture → Retour → swipe ; pause pendant la lecture -- boucle
- [x] `control/CalibrationOverlay.kt`, `service/SwipeAccessibilityService.kt` -- calque de calibration -- calibration
- [x] `settings/*`, `ui/SettingsScreen.kt`, `strings.xml` -- probabilité, positions, boutons « Calibrer » -- réglages

**Acceptance Criteria:**
- Given Tinder calibré et une session de quelques minutes, when on observe, then de temps en temps un profil s'ouvre, reste affiché quelques secondes, se referme, puis la carte est swipée.

## Implementation Notes

- **Action** : `Action.OpenProfile(readMs, delayMs, backPauseMs)` — en plus de `readMs` (2–6 s), le moteur tire le délai habituel avant le tap (`delayMs`, comme un `Swipe`) et l'attente après Retour (`backPauseMs`, 0,6–1,5 s), pour que tout l'aléatoire reste dans le moteur pur. `BehaviorConfig.profileOpenProbability` (défaut 0,08) + bornes de lecture/attente. Le tirage n'a lieu que si `profileAvailable()` (injecté) est vrai et p > 0 : sans calibration, la suite aléatoire du moteur est inchangée.
- **Disponibilité** : le service passe `{ position calibrée pour le paquet au premier plan }`, lue en direct (une calibration faite après le Démarrer compte) ; la probabilité, elle, vient des réglages du Démarrer.
- **Boucle** : `TapExecutor.tap(pkg)` et `goBack: () -> Boolean` injectés dans `AutomationController`. Toute la séquence (délai → tap → lecture → Retour → attente → swipe) tourne sous la même porte que les swipes : app quittée, écran éteint ou pause pendant la lecture = annulation, pas de Retour ni de swipe. Tap non abouti ou Retour refusé = un échec (compte dans les 5 échecs d'affilée), pas de swipe. Un seul `recordSwipe`, après le swipe.
- **Tap** : `TapGenerator` — un `SwipePlan` à un segment de 2 points, 60–140 ms, départ dans ±(1,5 % × largeur − 4 px) sur chaque axe, glissement 1–4 px ; tous les points restent dans ±1,5 % de la largeur. Envoyé par `GestureDispatcher`/`StrokeSequencer` comme un swipe.
- **Calibration** : `SwipeAccessibilityService.startCalibration(pkg, label)` arrête la session en cours (ses gestes toucheraient le calque), attend jusqu'à 2 min que l'app soit au premier plan, affiche `CalibrationOverlay` (plein écran, focusable pour recevoir Retour, consigne + « Annuler » dans le haut de l'écran), enregistre le point du toucher (`FractionPoint.of`, non arrondi) pour ce paquet. Retour, « Annuler » ou changement d'app = rien d'enregistré. Pendant l'attente ou l'affichage, swipes et taps de la boucle renvoient `Failed`.
- **Réglages** : `Settings.profileOpenPercent` (0–30, défaut 8, curseur) et `profilePoints` (clés DataStore `profile_x_<pkg>` / `profile_y_<pkg>`). `save()` n'écrit pas les positions (seul `saveProfilePoint` le fait), donc un formulaire ouvert avant une calibration ne l'écrase pas ; `orDefaults()` garde les positions.
- **Revue (itération 1)** :
  - Profil laissé ouvert : après un tap abouti, le contrôleur retient le paquet (`profileOpenIn`) ; effacé après un Retour réussi. Lecture interrompue (pause, écran, plage, app quittée) : jamais de Retour en pause ; à la reprise, si l'app au premier plan est celle du profil ouvert, Retour puis 0,6–1,5 s avant toute nouvelle décision. Tant que ce Retour n'est pas parti, aucun swipe dans cette app. `profileOpenIn` est conservé à travers Stop/Démarrer pendant toute la vie du contrôleur (un Retour en trop qui quitte l'app ne fait que mettre la boucle en pause).
  - Retour refusé : arrêt immédiat en Idle avec l'erreur « retour arrière refusé : profil peut-être resté ouvert », plus aucun geste.
  - App changée (Tinder ↔ Bumble) : avant le tap → pas de tap, swipe normal ; pendant la lecture → pas de Retour dans l'autre app (interrompu, profil retenu pour Tinder).
  - Tap non abouti (ex. non calibré) : repli sur le swipe normal, sans compter d'échec.
  - Démarrer refusé (toast) pendant une calibration ; le calque n'accepte qu'un toucher à un doigt, presque immobile (seuil de glissement du système) ; le formulaire de réglages n'est plus reconstruit par une calibration.
- **Non vérifié sur téléphone** : affichage du calque, réception de Retour par le calque, effet réel du tap et du Retour dans Tinder/Bumble.

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | blind, edge, verif | Lecture interrompue (pause, écran, fin de plage, app quittée) : profil laissé ouvert, le swipe suivant part sur la page du profil | high | patch | Aucun drapeau « profil ouvert » ; à la reprise, `nextAction()` puis swipe directement. |
| 2 | blind, edge, verif | Retour arrière refusé compté comme un échec ordinaire ; la boucle continue sur le profil ouvert | high | patch | Jusqu'à 5 gestes sur la page du profil (boutons like/superlike). |
| 3 | edge | Passage Tinder ↔ Bumble pendant le délai ou la lecture : tap dans une app non calibrée, ou retour arrière dans l'autre app | medium | patch | Aucun contrôle du paquet entre la décision, le tap et le retour. |
| 4 | blind, edge | Tap raté : swipe abandonné, compté vers l'arrêt avec un message trompeur | medium | patch | Repli sur le swipe normal. |
| 5 | verif | Fin de plage pendant la séquence non testée | medium | patch | Supprimer un des contrôles laisse tout vert. |
| 6 | edge | Démarrage pendant la calibration → 5 échecs → erreur | low | patch | Garde directe. |
| 7 | blind, edge | Retour de calibration : formulaire de réglages réinitialisé, saisies perdues | medium | patch | `remember(saved)` inclut les positions. |
| 8 | blind, edge | Calque : un glissé est enregistré comme position ; ACTION_CANCEL et multi-touch non gérés | low | patch | Seuil de déplacement direct. |
| 9 | blind | Calibration annulée par les fenêtres du calque ou du système | false | rejet | `foregroundFrom` ignore déjà systemui, le clavier et les fenêtres de Swipper hors `.ui`. |
| 10 | blind, edge | Orientation non mémorisée avec la position | low | rejet | Tinder et Bumble sont en portrait. |
| 11 | blind | Impossible d'effacer une calibration | low | rejet | Probabilité à 0 % suffit ; recalibrer remplace la position. |
| 12 | blind | Message d'erreur du champ « ouverture de profil » jamais affiché | low | rejet | Curseur borné : erreur inatteignable. |
| 13 | blind | Job de calibration remis à null sans être annulé | low | patch | Annulation explicite directe. |
| 14 | blind | Libellé d'app déduit par un ternaire ; 8 % actif par défaut | low | patch | Table de libellés ; le défaut à 8 % est voulu par la spec. |
| 15 | edge | Tap au bord de l'écran : trajet de longueur nulle | low | rejet | Le point calibré est au centre de la carte. |
| 16 | verif | Positions stockées corrompues non testées | medium | patch | Retirer le `runCatching` laisse tout vert. |
| 17 | verif | Câblage `profileAvailable` / exécuteur de tap dans le service non testé | medium | defer | Même report que le câblage de session de la story 6. |

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent

**Manual checks (if no CLI):**
- Téléphone : calibrer Tinder (toucher la zone du nom / de l'âge en bas de la carte), régler la probabilité à 30 % pour l'essai, observer quelques ouvertures de profil, puis remettre 8 %.
