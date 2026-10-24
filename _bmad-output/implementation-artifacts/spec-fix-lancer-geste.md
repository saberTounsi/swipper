---
title: 'Correctif — lancer en fin de geste et trajet minimal de 35 %'
type: 'bugfix'
created: '2026-10-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'd17e7d5d4762fe779fc1d733e38cc8b44b9bc62f'
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/stories/2-generateur-gestes-humains.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Sur un vrai téléphone (Redmi Note 12 Pro 5G, HyperOS 2.0), Tinder accepte le swipe, mais la carte revient parfois en place. Deux causes cumulées : le geste ralentit jusqu'à une vitesse quasi nulle à la levée du doigt (aucun « lancer »), et le trajet peut ne faire que 15 % de la largeur.

**Approach:** Modifier `GestureGenerator` (story 2) pour que le geste garde de la vitesse jusqu'à la levée, et que le trajet horizontal fasse toujours au moins 35 % de la largeur, en décalant le départ selon le sens. Décision humaine du 2026-10-25.

## Boundaries & Constraints

**Always:**
- **Profil de vitesse « lancer » :** départ lent, montée jusqu'à un pic placé entre 55 et 75 % du temps, puis légère baisse ; la vitesse au moment de la levée vaut 50 à 80 % du pic (tirés au hasard). Les gestes hésitants gardent leur départ plus lent.
- **Trajet ≥ 35 % de la largeur :** pour RIGHT, départ tiré dans la zone ∩ [0 ; 0,62] ; pour LEFT, dans la zone ∩ [0,38 ; 1] ; arrivée dans les mêmes bandes qu'avant (80–97 % / 3–20 %), avec trajet ≥ 0,35 × largeur. Une zone est refusée (`IllegalArgumentException`) si xMin > 0,62 ou xMax < 0,38, car un des deux sens serait impossible. Ces règles remplacent l'ancien minimum de 15 % et les limites 0,18 / 0,82.
- **Durée totale :** 180–450 ms (au lieu de 600), pour rester dans la plage d'un vrai lancer.
- Tout le reste de la story 2 est conservé : courbe de Bézier, dérive verticale, tremblement de côté, continuité des segments, non-recul, biais vers la droite, graine fixe, zéro import Android.

**Never:** aucune modification hors `gesture/` et de ses tests ; pas de changement de l'API publique (`plan(screen, zone, direction)`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Lancer | 1 000 plans, zone défaut | vitesse du dernier segment ≥ 50 % de la vitesse du segment le plus rapide ; premier segment plus lent que le plus rapide | N/A |
| Trajet minimal | 1 000 plans, les deux sens | \|x arrivée − x départ\| ≥ 35 % de la largeur ; arrivée dans la bande du sens | N/A |
| Départ selon le sens | 1 000 plans RIGHT puis LEFT | départ RIGHT ≤ 0,62 × largeur ; départ LEFT ≥ 0,38 × largeur ; toujours dans la zone | N/A |
| Durée | 1 000 plans | durée totale dans [180 ; 450] ms | N/A |
| Zone refusée | xMin = 0,70 ou xMax = 0,30 | `IllegalArgumentException` | `require` avec message |
| Non-régression | tests existants de la story 2 | tous verts, sauf ceux dont la règle est explicitement remplacée ci-dessus (à adapter, pas à supprimer) | N/A |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/gesture/GestureGenerator.kt` -- `ease(u, hesitant)` (ligne ~259) : remplacer l'ease-in-out sinusoïdal par le profil « lancer » ; constantes `MIN_TRAVEL`, `ZONE_X_MIN_LIMIT`/`MAX_LIMIT`, `MAX_TOTAL_MS` ; tirage du départ (étape 1) à rendre dépendant du sens.
- `app/src/test/java/com/swipper/app/gesture/GestureGeneratorTest.kt` -- adapter « profil de vitesse » (le milieu n'a plus à dépasser la fin), amplitude, zone refusée, zone limite ; ajouter les lignes de la matrice.
- Build : `JAVA_HOME` = JDK 21.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/swipper/app/gesture/GestureGenerator.kt` -- profil « lancer », départ selon le sens, trajet 35 %, durée 180–450 ms, validation de zone -- correctif
- [x] `app/src/test/java/com/swipper/app/gesture/GestureGeneratorTest.kt` -- un test par ligne de la matrice ; adapter les tests remplacés -- non-régression

**Acceptance Criteria:**
- Given le téléphone de l'utilisateur et Tinder, when 5 swipes de test sont lancés dans chaque sens, then la carte part à chaque fois.

## Implementation Notes

- Gestes hésitants : u^1,6 étant appliqué avant E, leur pic se décale vers ~69–85 % du temps et le rapport levée/pic monte à ~0,9. Accepté : ils gardent un lancer à la levée.
- Règle de zone : l'ancienne contrainte 0,18 ≤ xMin, xMax ≤ 0,82 est remplacée par xMin ≤ 0,62 et xMax ≥ 0,38 (tolérance 1e-6 pour les bornes Float). Les futurs réglages de zone (CAP-6) doivent valider contre cette règle. Les limites sont dérivées : 0,62 = RIGHT_BAND_MAX − MIN_TRAVEL, 0,38 = LEFT_BAND_MIN + MIN_TRAVEL.
- Avec le biais vers la droite, une part des swipes RIGHT finit près de 97 % de la largeur (départ proche de 0,62, bande d'arrivée réduite). Noté ; non observable côté serveur.

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | edge, verif, blind | Zones exactement à 0,62 / 0,38 refusées (Float élargi en Double) | low | patch | 0.62f → 0,6200000048 > 0,62 ; correction directe de la comparaison. |
| 2 | verif | Limites de zone non testées | medium | patch | Changer `<=` en `<` ou l'arrondi passe inaperçu. |
| 3 | verif, blind | `plan()` pourrait ignorer le pic et la vitesse de levée tirés sans qu'aucun test échoue | medium | patch | Constantes, arguments inversés ou mauvaise plage : tous les tests passent. |
| 4 | blind | Gestes hésitants hors des bornes « lancer » (pic ~69–85 %, levée ~0,9 du pic) | low | rejet | Conséquence du u^1,6 décidé en story 2 ; ils lancent toujours la carte (levée plus rapide). Consigné dans Implementation Notes. |
| 5 | blind | `RIGHT_START_MAX` et `LEFT_START_MIN` codés en dur alors qu'ils dépendent des bandes | low | patch | Modifier une constante casserait la garantie de 35 % en silence ; dérivation directe. |
| 6 | blind | Une part des swipes à droite finit près de 97 % (biais droite + départ ≤ 0,62) | low | rejet | Géométrie invisible côté serveur ; consigné. |
| 7 | blind | Test du biais mesuré sur toute la zone et non sur l'intervalle de chaque sens | low | patch | Le recadrage à gauche gonfle la moyenne à lui seul. |
| 8 | blind | Test « lancer » faible | medium | patch | Même cause que #3. |
| 9 | blind | Story 2 décrit encore l'ancienne règle de zone | low | patch | Consigné dans Implementation Notes ; la spec de story 2 reste l'historique. |
| 10 | blind | Ordre des imports, `values()` déprécié, test de durée lié à la graine | low | rejet | Cosmétique. |
| 11 | edge | `ease()` sans validation de ses paramètres | low | rejet | Fonction interne appelée avec des valeurs tirées dans des plages valides. |
| 12 | edge | Swipe à gauche depuis une zone allant jusqu'à 1 : trajet inférieur à 35 % de moins d'un pixel | low | rejet | Écart sous-pixel. |
| 13 | blind | Critère d'acceptation sur téléphone pas encore exécuté | — | — | Vérification manuelle en attente (test Tinder). |

## Design Notes

**Construire E(u) à partir de la vitesse :** définir v(u) = sin(π/2 · u/p) pour u ≤ p (montée de 0 à 1), puis v(u) = 1 − (1 − r)·(u − p)/(1 − p) pour u > p (baisse linéaire jusqu'à r), avec p ∈ [0,55 ; 0,75] et r ∈ [0,5 ; 0,8] tirés par geste. E(u) = ∫₀ᵘ v / ∫₀¹ v (intégrale analytique ou par table de 64 pas). E est monotone, E(0) = 0, E(1) = 1. Geste hésitant : appliquer u^1,6 avant E, comme avant. Les frontières restent placées à E(τᵢ) (temps cumulé).

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent

**Manual checks (if no CLI):**
- Téléphone de l'utilisateur, Tinder : 5 swipes ← puis 5 → ; la carte part à chaque fois.
