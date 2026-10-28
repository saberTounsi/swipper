---
title: 'Swipe qui part à l''horizontale (Bumble fait défiler le profil)'
type: 'bugfix'
created: '2026-10-28'
status: 'done'
baseline_commit: '8334d38c6160e21af8a5fc520351691218fb5553'
route: 'dispatch'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Sur Bumble, alors que l'ouverture de profil est à 0 %, la carte « descend » (le profil défile) au lieu de partir sur le côté. Ce n'est pas l'ouverture de profil : à 0 %, `BehaviorEngine` ne renvoie jamais `OpenProfile` (`profileOpenProbability > 0.0` exigé) et ce serait un tap, pas un glissé. C'est le swipe lui-même : la carte de Bumble défile verticalement, et Android donne le geste au premier axe qui dépasse le seuil de toucher (~8 dp ≈ 22 px). Or le tracé peut partir presque à la verticale : dérive jusqu'à 12 % de la hauteur (288 px) pour un trajet minimal de 378 px, plus un décalage perpendiculaire du premier point de contrôle jusqu'à 86 px, soit une tangente de départ jusqu'à ~65° au-dessus de l'horizontale.

**Approach:** Le swipe démarre toujours à dominante horizontale : le premier point de contrôle de la courbe reste à la hauteur du départ (tangente initiale horizontale), la dérive verticale et la courbure se font ensuite. Changement global (Tinder et Bumble) : Tinder n'en est pas gêné et le geste reste courbe et varié.

## Boundaries & Constraints

**Always:** Sur les 80 premiers px parcourus depuis le départ, chaque point du plan vérifie |Δy| ≤ 0,6 · |Δx| (Δ mesurés depuis le départ). Les autres propriétés du générateur restent vraies : bandes d'arrivée, trajet minimal, dérive 4–12 %, courbure, jamais à reculons, tremblement, profil de vitesse, reproductibilité à graine fixe.

**Never:** Pas de géométrie par app ; ne pas toucher à l'ouverture de profil, au moteur ni aux réglages ; ne pas réduire la dérive ni supprimer la courbure pour obtenir la contrainte.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Dérive maximale vers le haut | trajet minimal 0,35 largeur, dérive 12 %, décalage de contrôle max | début du tracé : |Δy| ≤ 0,6·|Δx| sur 80 px | N/A |
| Dérive vers le bas | idem, dérive négative | même contrainte | N/A |
| Petit écran | écran de test `small` | même contrainte, plan valide | N/A |
| Swipe gauche et droite | les deux sens | même contrainte | N/A |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/gesture/GestureGenerator.kt:79-105` -- construction des contrôles `c1`/`c2` (au 1/3 et 2/3 de la corde, décalés de `side * k * n`) et `cap()` qui garde les contrôles dans l'écran. Mettre `c1` à `(sx + dx/3, sy)` sans décalage perpendiculaire ; garder le décalage de `c2` (et son `cap`). Le tremblement (1–3 px, ≤ gap/2) est négligeable.
- `app/src/test/java/com/swipper/app/gesture/GestureGeneratorTest.kt` -- helpers `plans()`, `allPoints()`, écrans `phone` et `small` ; tests `courbure`, `jamais a reculons`, `amplitude et derive` à garder verts.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/swipper/app/gesture/GestureGenerator.kt` -- placer le premier point de contrôle à la hauteur du départ ; adapter le KDoc de l'étape 3 -- le doigt part à l'horizontale, la carte de Bumble est saisie avant tout défilement.
- [x] `app/src/test/java/com/swipper/app/gesture/GestureGeneratorTest.kt` -- ajouter `depart a dominante horizontale` : sur `plans()` (téléphone et petit écran), tous les points à ≤ 80 px du départ vérifient |Δy| ≤ 0,6·|Δx| -- verrouille la cause du bug.

**Acceptance Criteria:**
- Given Bumble au premier plan avec l'ouverture de profil à 0 %, when la boucle tourne 30 swipes, then aucune carte ne défile vers le bas : chaque carte part sur le côté.
- Given Tinder, when la boucle tourne, then les swipes partent comme avant.

## Implementation Notes

- Revue : sur petit écran, la contrainte cassait dans de rares cas (Δy=41,4 ; Δx=68,1 à 79,7 px, 1 plan sur 40 000). Ajout d'une garde : si la pente de départ de la courbe sans bruit dépasse 0,45 sur 80 px (`startSlope`), la courbure est inversée (le décalage de `c2` s'oppose alors à la dérive). Le test couvre maintenant 20 graines × 2 000 plans × 2 écrans et échoue si on désactive la garde.

- `c1` = `(sx + dx/3, sy)` ; seul `c2` garde le décalage perpendiculaire (le `cap` ne porte plus que sur lui, `k1` supprimé). Les projections des contrôles sur la corde restent croissantes (0 ≤ dx²/3L ≤ 2L/3 ≤ L), donc « jamais à reculons » tient toujours.
- Surprise : le tremblement seul (1–3 px, ≤ gap/2) cassait la contrainte sur les 4 premiers px (Δy=2,2 ; Δx=3,6). Borne ramenée à gap/4 (test du tremblement resserré pareil). L'amplitude 1–3 px est inchangée ; la story 2 (terminée) décrit encore « moitié de l'écart », non modifiée.

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | edge, verif | Contrainte |Δy| ≤ 0,6·|Δx| sur 80 px non garantie : petit écran, dérive et décalage max du même côté | medium | patch | Reproduit (graines 100–119, 480×800) ; garde `startSlope` avec inversion de courbure, test multi-graines qui échoue sans elle. |
| 2 | edge | Test à graine unique : le cas limite n'est jamais tiré | low | patch | 20 graines × 2 000 plans par écran. |
| 3 | blind, edge | Test vide possible si le premier point est à plus de 80 px | low | patch | Assertion `checked > 0` ajoutée (ne se produit pas : départ lent, points serrés). |
| 4 | blind | Commentaire au-dessus de `cap()` périmé (ne borne plus que `c2`) | low | patch | Commentaire précisé : `c1` est entre `sx` et `ex`, à la hauteur `sy`. |
| 5 | blind | Départ toujours parfaitement plat : signature mécanique | low | rejet | Tangente horizontale voulue par l'intention ; le premier point intermédiaire porte déjà le tremblement de 1–3 px. |
| 6 | blind | Borne gap/4 sur tout le tracé, pas seulement au départ | low | rejet | Elle ne mord que là où les points sont serrés (< 12 px : départ et levée) ; amplitude 1–3 px inchangée ailleurs. |
| 7 | blind | Le seuil `inRange` du test du tremblement pourrait casser | false | rejet | Suite complète verte (164 tests). |
| 8 | blind | Tests de courbure à graine fixe peut-être cassés par le tirage `k1` supprimé | false | rejet | `courbure` et `jamais a reculons` verts. |
| 9 | blind | Test plus strict que le seuil réel d'Android, 0,6 / 80 px non reliés au seuil | low | rejet | Valeurs fixées dans l'intention approuvée ; la corriger reviendrait à modifier le spec. |
| 10 | blind | Le segment départ → premier point n'est pas vérifié entre les points | false | rejet | Trait rectiligne : sa pente est celle de son extrémité, qui est vérifiée. |
| 11 | blind | Effet sur Tinder non couvert | low | rejet | Couvert par la vérification manuelle du spec (Tinder). |

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent, APK construit

**Manual checks (if no CLI):**
- Redmi, Options pour les développeurs → « Afficher les appuis » : sur Bumble, 30 swipes à 0 % sans défilement du profil ; sur Tinder, swipes normaux.
