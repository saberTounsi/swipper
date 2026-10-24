---
title: 'Story 2 — Générateur de gestes humains'
type: 'feature'
created: '2026-10-24'
status: 'done'
route: 'dispatch'
review_loop_iteration: 1
baseline_commit: 'a0b24a4101821a7ee6ac4f185ed8c3422040cfdd'
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Rien ne produit encore de geste. Il faut que chaque swipe ressemble à un doigt : tracé courbe, accélération puis décélération, paramètres jamais identiques (CAP-2).

**Approach:** Un `GestureGenerator` en Kotlin pur transforme (taille d'écran, zone de départ, direction, `Random`) en `SwipePlan` : une suite de segments enchaînables, chacun avec ses points et sa durée, prêts à être envoyés par `continueStroke` en story 3.

## Boundaries & Constraints

**Always:** Package `com.swipper.app.gesture`, zéro import `android.*` ; `Random` et dimensions injectés (déterministe à graine fixe). Départ aléatoire dans la zone (fractions d'écran, défaut x ∈ [0,35 ; 0,65], y ∈ [0,45 ; 0,65], biais vers la droite) ; dérive verticale de ±4–12 % de la hauteur ; Bézier cubique avec points de contrôle décalés perpendiculairement de 2–8 % de la largeur ; 6–10 segments ; durée totale 180–600 ms ; chaque segment ≥ 16 ms ; 10 % de gestes « hésitants » (départ plus lent) ; tous les points bornés à l'écran.

**Décisions (revue, 2026-10-24) :**
- **Arrivée :** x d'arrivée tiré dans une bande près du bord : RIGHT → [max(0,80 ; départ + 0,15) ; 0,97] de la largeur ; LEFT → [0,03 ; min(0,20 ; départ − 0,15)]. Le trajet horizontal fait donc toujours ≥ 15 % de la largeur, dans le bon sens. Une zone avec xMin < 0,18 ou xMax > 0,82 est refusée (`IllegalArgumentException`), car elle ne garantit pas ce trajet. Remplace « déplacement de 55–85 % + écrêtage à 2/98 % ».
- **Tremblement :** bruit sur les points intermédiaires seulement, **perpendiculaire à la direction départ→arrivée** (jamais le long), d'amplitude min(1–3 px ; moitié de l'écart entre points voisins). Le doigt ne recule jamais : la projection des points successifs sur la direction du geste ne décroît jamais.

**Never:** aucun appel au SDK Android, aucune exécution de geste, aucune décision gauche/droite (story 4), aucune lecture de réglages.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Swipe droite | 1080×2400, zone défaut, RIGHT | x final > x initial ; 6–10 segments ; durée 180–600 ms | N/A |
| Swipe gauche | idem, LEFT | x final < x initial | N/A |
| Variété | 1 000 plans, graine fixe | aucun plan identique à un autre | N/A |
| Bornes | 1 000 plans, petit écran 480×800 | départ dans la zone ; tous les points dans l'écran ; chaque segment ≥ 16 ms | N/A |
| Profil de vitesse | 1 000 plans | vitesse moyenne des segments du milieu > celle du premier et du dernier segment | N/A |
| Hésitants | 1 000 plans | 5–15 % ont un premier segment plus lent que la moyenne des non-hésitants | N/A |
| Continuité | tout plan | le 1er point de chaque segment = dernier point du segment précédent | N/A |
| Amplitude et dérive | 1 000 plans, zone défaut, les deux sens | x d'arrivée dans la bande du sens choisi ; trajet ≥ 15 % de la largeur ; arrivées variées (au moins 100 valeurs de x distinctes) ; |Δy| dans [4 % ; 12 %] de la hauteur | N/A |
| Biais droite | 1 000 plans | position moyenne du départ dans la zone > 0,5 | N/A |
| Courbure | 1 000 plans | ≥ 90 % des plans ont une frontière de segment écartée d'au moins 0,5 % de la largeur de la corde départ→arrivée | N/A |
| Jamais à reculons | 1 000 plans, dont hésitants, écran 480×800 | la projection de tous les points (dans l'ordre) sur la direction départ→arrivée ne décroît jamais | N/A |
| Entrée invalide | largeur/hauteur ≤ 0 ; zone hors [0;1], min > max, NaN ; xMin < 0,18 ou xMax > 0,82 | `IllegalArgumentException` | `require(...)` avec message |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/service/AccessibilityStatus.kt` -- exemple du style maison (KDoc en français, fonctions pures testables en JVM) ; ne pas modifier.
- `app/build.gradle.kts` -- JUnit 5 déjà configuré (`useJUnitPlatform`) ; aucune dépendance à ajouter.
- `architecture.md` -- `GestureDispatcher` (story 3) consommera `SwipePlan` : chaque `Segment` = une `StrokeDescription`, d'où l'invariant de continuité.
- Build : `JAVA_HOME` = JDK 21 (le `java` du PATH est en Java 8).

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/swipper/app/gesture/SwipeModel.kt` -- `Point(x: Float, y: Float)`, `Segment(points: List<Point>, durationMs: Long)` (≥ 2 points), `SwipePlan(segments: List<Segment>, hesitant: Boolean)` avec `totalDurationMs`, `start`, `end` ; `Direction { LEFT, RIGHT }` ; `ScreenSize(widthPx, heightPx)` ; `SwipeZone(xMin, xMax, yMin, yMax)` avec `DEFAULT` -- modèle partagé avec la story 3
- [x] `app/src/main/java/com/swipper/app/gesture/GestureGenerator.kt` -- `class GestureGenerator(private val random: Random)` + `fun plan(screen, zone, direction): SwipePlan` selon Design Notes -- cœur de CAP-2
- [x] `app/src/test/java/com/swipper/app/gesture/GestureGeneratorTest.kt` -- une méthode de test par ligne de la matrice, graine fixe ; bornes lues dans les constantes `internal` du générateur (pas de littéraux) ; chaque test vérifie que ses partitions ne sont pas vides ; le test « pas d'android » échoue avec un message clair si le dossier est introuvable et cherche la regex `\bandroidx?\.` hors commentaires -- critères de succès CAP-2

**Acceptance Criteria:**
- Given le module `gesture`, when on cherche des imports `android.`, then il n'y en a aucun.
- Given la même graine, when on génère deux fois, then les plans sont identiques (reproductibilité des tests).

## Implementation Notes

- Écran borné à [0 ; largeur−1] × [0 ; hauteur−1]. Le sens de la dérive verticale est inversé s'il ferait sortir l'arrivée de l'écran (l'autre sens tient toujours, 12 % < 50 %) : |Δy| reste dans [4 % ; 12 %] sans écrêtage.
- Points de contrôle : si le décalage tiré sort de l'écran, on prend l'autre côté, sinon on le réduit au maximum admissible. Les contrôles restent dans l'écran, donc toute la courbe aussi (enveloppe convexe) ; le décalage reste perpendiculaire, donc la projection sur la corde reste monotone.
- Tremblement : si le point bruité sortirait de l'écran, on garde le point de la courbe.
- Tests : tolérance de 1e-3 (arrondi Float) sur les bornes de bande, la dérive et la non-décroissance de la projection. Les hésitants sont comparés en vitesse relative (rapportée à la vitesse moyenne du plan). Test ajouté : zone aux limites (x ∈ [0,18 ; 0,82], y ∈ [0 ; 1]) sur 480×800.

- Écran borné à [0 ; largeur−1] × [0 ; hauteur−1]. Le sens de la dérive verticale est inversé s'il ferait sortir l'arrivée de l'écran (l'autre sens tient toujours, 12 % < 50 %) : |Δy| reste dans [4 % ; 12 %] sans écrêtage.
- Points de contrôle : si le décalage tiré sort de l'écran, on prend l'autre côté, sinon on réduit le décalage au maximum admissible. Les contrôles restent dans l'écran, donc toute la courbe aussi (enveloppe convexe) ; le décalage reste perpendiculaire, donc la projection sur la corde reste monotone.
- Tremblement : si le point bruité sortirait de l'écran, on garde le point de la courbe.
- Tests : tolérance de 1e-3 (arrondi Float) sur les bornes de bande, la dérive et la non-décroissance de la projection. Vitesse « hésitante » comparée en vitesse relative (rapportée à la vitesse moyenne du plan). Test supplémentaire : zone aux limites (x ∈ [0,18 ; 0,82], y ∈ [0 ; 1]) sur 480×800.

## Spec Change Log

- **Boucle 1 (2026-10-24)** — Déclencheurs : triage #1–#3 (intent_gap), #4–#10 (bad_spec). Modifié : géométrie d'arrivée et tremblement (décisions humaines, bloc gelé) ; easing évalué au temps cumulé ; fractions calculées une seule fois ; précondition de `splitDuration` ; tests d'amplitude, dérive, biais, courbure, non-recul ; tests robustes. État évité : arrivées répétées à 98 %, swipes inversés avec une zone près du bord, doigt qui recule, sauts de vitesse de ±15 %. **KEEP :** modèle `SwipeModel.kt` (Point/Segment/SwipePlan/Direction/ScreenSize/SwipeZone avec validation `require` dans `init`) ; table de longueur d'arc à 64 échantillons inversée par interpolation ; frontières partagées entre segments (continuité exacte) ; `splitDuration` (poids ±15 %, plancher 16 ms, correction à la somme exacte) ; biais u^0,8 ; points de contrôle au 1/3 et 2/3 de la corde du même côté ; easing (1−cos πu)/2, précédé de u^1,6 pour les hésitants ; vitesse de segment mesurée entre ses extrémités dans les tests.

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | blind, edge, verif | Zone proche du bord : l'arrivée écrêtée à 2/98 % peut passer derrière le départ (swipe inversé ou quasi nul) | medium | intent_gap | `ex.coerceIn(0.02w, 0.98w)` sans contrainte sur le départ ; `SwipeZone` accepte [0;1] et la zone sera réglable (CAP-6). La géométrie gelée (55–85 % + écrêtage) ne dit pas quoi faire. |
| 2 | revue interne | Avec la zone par défaut, la plupart des swipes finissent exactement à 98 % de la largeur | medium | intent_gap | Départ 0,35–0,65 + 0,55–0,85 ≥ 0,90, souvent > 0,98 → écrêtage ; arrivée répétitive, contraire à « paramètres jamais identiques ». Même cause que #1. |
| 3 | blind, edge | Le bruit ±1–3 px peut dépasser l'avance du segment (départ lent) → le doigt recule | medium | intent_gap | Hésitant n=10 : 1er segment ≈ 0,0015 × trajet (< 1 px) avec bruit x/y de 1–3 px. Le bruit est fixé dans le bloc gelé. |
| 4 | blind | Easing évalué sur l'indice et non sur le temps cumulé → sauts de vitesse de ±15 % aux frontières | low | bad_spec | Design Notes : frontières à `E(i/N)` alors que les durées sont bruitées ; correction directe = `E(temps cumulé / total)`. |
| 5 | blind | Fractions d'easing calculées deux fois (frontières et segments) | low | bad_spec | Deux calculs parallèles de la même suite ; à unifier lors de la re-dérivation. |
| 6 | verif, blind | Amplitude, dérive, biais droite, courbure et absence de bruit sur les frontières non testés | medium | bad_spec | Mettre `MIN_DX=0.01`, `dy=0`, offsets à 0 ou bruit sur les frontières laisse tous les tests verts. |
| 7 | edge, blind | `splitDuration` suppose `total ≥ 16·n` sans `require` | low | bad_spec | Vrai avec les constantes actuelles ; une précondition explicite suffit. |
| 8 | edge, blind | Test « pas d'import android » fragile (répertoire de travail, `androidx`, noms qualifiés) | low | bad_spec | `first {}` lève sans message ; ne détecte que `import android.`. |
| 9 | edge | Test hésitants vide de sens si une partition est vide | low | bad_spec | Avec la graine fixe les deux partitions sont non vides ; une assertion explicite suffit. |
| 10 | blind | Bornes des tests codées en dur (6..10, 180..600, 16) | low | bad_spec | Les constantes `internal` sont accessibles depuis les tests. |
| 11 | edge | Zone proche du haut/bas : points empilés sur le bord | low | intent_gap | Même cause que #1 (validation de la zone). |
| 12 | blind | Comparaison flottante du départ dans la zone sans tolérance | false | rejet | Zone par défaut de largeur non nulle ; le départ est tiré strictement à l'intérieur, l'arrondi Float ne peut pas sortir de la zone. |
| 13 | blind | Test de variété trivial | low | rejet | Il vérifie bien l'exigence CAP-2 telle qu'écrite ; renforcé indirectement par #6. |
| 14 | verif, blind | (boucle 1) Inversion de la dérive près du haut/bas non vérifiée | medium | patch | Supprimer la ligne d'inversion laisse les 16 tests verts : le test de zone limite ne vérifie que l'écran. |
| 15 | verif, blind | (boucle 1) Tremblement des points intermédiaires non testé (nombre, amplitude, direction) | medium | patch | `amp = 0`, bruit le long de la corde ou zéro point intermédiaire : tous les tests passent. |
| 16 | blind | (boucle 1) Test des hésitants confus : `ratio` mesure en fait la part d'hésitants | low | patch | Le `assertEquals(hesitant.size, slower)` final rend le ratio redondant ; séparation directe. |
| 17 | blind | (boucle 1) Vitesse nulle à la fin du geste ((1−cos πu)/2) → risque de « retour élastique » de la carte | maybe-false | defer | L'arrivée à 80–97 % de la largeur dépasse en principe le seuil de distance ; à confirmer sur appareil en story 3 (serait medium). |
| 18 | edge, blind | (boucle 1) Écrans minuscules (largeur < 34 px, hauteur 1 px) : trajet < 15 %, dérive nulle, NaN | low | rejet | Aucun écran réel n'en approche ; la correction ajoute des gardes. |
| 19 | edge | (boucle 1) Comparaison flottante du départ au bord exact de la zone | false | rejet | Exige `nextDouble() == 0.0` exactement ; le départ reste dans la zone à l'arrondi Float près, et le test lit la même valeur. |
| 20 | blind | (boucle 1) Tremblement en pixels fixes, signe changeant à chaque point | low | rejet | Amplitude 1–3 px et direction fixées par la décision humaine (bloc gelé). |
| 21 | blind | (boucle 1) Biais vers la droite indépendant du sens → swipes à gauche plus longs en moyenne | low | rejet | Biais voulu par l'architecture ; la géométrie n'est pas visible côté serveur. |
| 22 | blind | (boucle 1) Durée tirée indépendamment de la distance | low | rejet | Vitesses résultantes dans une plage plausible (≈ 0,3–5 px/ms) ; amélioration, pas un défaut. |
| 23 | blind | (boucle 1) Plafonnement des points de contrôle non testé directement | low | rejet | Couvert indirectement par le test de zone limite (points dans l'écran) et #14. |
| 24 | blind | (boucle 1) Limites de `GestureDescription` (nombre de traits, durée max) non vérifiées | false | rejet | La story 3 envoie un segment = un `dispatchGesture` à un seul trait ; 600 ms est loin de la durée max. |

## Design Notes

**Profil de vitesse sans calcul de vitesse explicite :** `dispatchGesture` parcourt chaque segment à vitesse constante. On découpe donc le temps en N tranches à peu près égales (durées bruitées de ±15 %, puis remises à l'échelle pour que la somme fasse la durée tirée, chaque tranche ≥ 16 ms), et on place la frontière i sur la courbe à la fraction de longueur `E(τᵢ)`, où τᵢ = (somme des i premières durées) / durée totale — calculée une seule fois et partagée par frontières et segments — où `E` est une fonction d'easing monotone (sinusoïdale : `E(u) = (1 − cos(πu)) / 2`). Les segments du milieu couvrent ainsi plus de distance dans le même temps, donc vont plus vite. Pour un geste hésitant : `E(u)` = easing avec départ plus lent (par exemple `u^1.6` composé avant l'easing).

**Longueur d'arc :** échantillonner la Bézier en ~64 points, cumuler les distances, puis inverser par interpolation linéaire pour trouver le paramètre t d'une fraction de longueur donnée. Chaque segment contient ses frontières plus 2–4 points intermédiaires pris sur la courbe (tracé lisse) ; le bruit ne touche que ces points intermédiaires, jamais les frontières (continuité exacte). Il est appliqué selon la normale à la corde départ→arrivée (la projection d'une telle Bézier sur la corde est linéaire en t, donc monotone ; un bruit purement normal ne la modifie pas), avec une amplitude bornée par la moitié de l'écart entre points voisins, ce qui garantit que le doigt n'avance jamais à reculons.

**Arrivée :** tirer d'abord le départ, puis x d'arrivée uniformément dans la bande du sens choisi (bornes de la bande calculées avec le départ, voir Décisions). `splitDuration` commence par `require(total >= MIN_SEGMENT_MS * n)`.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest` -- expected: tous les tests passent (ceux de la story 1 compris)
- `grep -r "import android" app/src/main/java/com/swipper/app/gesture` -- expected: aucun résultat
