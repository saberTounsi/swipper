---
title: 'Story 6 — Réglages, compteur sauvegardé et guide Xiaomi'
type: 'feature'
created: '2026-10-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '405a52e90a7bc3548e1b18bcc802b14482f0e69c'
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Tous les paramètres sont figés dans le code, et le compteur du jour comme la session en cours sont perdus à chaque redémarrage du service, ce qui permet de dépasser le plafond (CAP-6). Sur HyperOS, l'utilisateur ne sait pas quels réglages empêchent le système de tuer Swipper.

**Approach:** Un écran de réglages persistés (DataStore) alimente le moteur au démarrage de chaque session ; l'état du moteur (compteur du jour, session) est sauvegardé après chaque swipe et restauré au démarrage ; un guide Xiaomi s'affiche sur les téléphones Xiaomi/Redmi/POCO.

## Boundaries & Constraints

**Always:**
- **Réglages persistés** (Jetpack DataStore Preferences) : ratio droite (0–100 %, défaut 70) ; délai médian (0,8–8 s, défaut 1,8) ; session min/max (défaut 25/60) ; pause longue min/max en minutes (défaut 15/90) ; plafond quotidien (défaut 150) ; plage horaire début/fin (défaut 9:00/23:00) ; apps cibles cochables (Tinder, Bumble ; au moins une). Les autres paramètres du moteur gardent leurs valeurs par défaut.
- **Validation dans l'écran** : une valeur invalide (min > max, hors bornes, début = fin, aucune app) est signalée sous le champ et n'est pas enregistrée ; jamais d'`IllegalArgumentException` remontée à l'utilisateur.
- Les réglages s'appliquent au prochain Démarrer ; pendant une session en cours, l'écran l'indique.
- **État sauvegardé** : `BehaviorState` étendu à `swipesToday`, `day`, `sessionSwipes`, `sessionTarget` ; écrit après chaque `recordSwipe` et chaque fin de session, lu à la création du moteur. Un état d'un jour passé repart à zéro (règle existante du moteur).
- **Compteur du jour** visible sur l'accueil, cohérent avec la notification, remis à zéro au changement de jour local.
- **Guide Xiaomi** : si `Build.MANUFACTURER` vaut xiaomi/redmi/poco (insensible à la casse), carte « Réglages Xiaomi recommandés » avec trois étapes (démarrage automatique, économiseur de batterie « Aucune restriction », verrouiller Swipper dans les applis récentes) et des boutons qui ouvrent les écrans correspondants quand ils existent, sinon les infos de l'appli.
- Logique de conversion réglages → `BehaviorConfig` et de validation en Kotlin pur, testée en JVM.

**Never:** pas de réglage de la géométrie des gestes ni de la zone de swipe (valeurs par défaut conservées) ; pas d'ouverture de profil (story 7) ; pas de réseau ; pas de nouvelle permission.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Défauts | première installation | écran pré-rempli avec les défauts ; moteur identique à aujourd'hui | N/A |
| Persistance | ratio 60 %, plafond 100, redémarrage du téléphone | valeurs relues identiques ; moteur créé avec ratio 0,60 et plafond 100 | N/A |
| Saisie invalide | session min 70 > max 60 | message sous le champ ; valeur non enregistrée | validation pure |
| Aucune app cible | Tinder et Bumble décochés | refusé avec message | validation pure |
| Compteur survit | 40 swipes, service tué puis relancé le même jour | compteur 40 ; session reprise là où elle en était | N/A |
| Nouveau jour | état sauvegardé hier | compteur 0 au démarrage | règle du moteur |
| Plafond après redémarrage | 150 swipes puis redémarrage le même jour | `CapReached` immédiatement | N/A |
| Xiaomi | `MANUFACTURER` = "Xiaomi" | carte du guide visible | N/A |
| Autre marque | `MANUFACTURER` = "Google" | pas de carte du guide | N/A |
| Écran introuvable | intent du démarrage automatique absent | ouvre les infos de l'appli | `ActivityNotFoundException` interceptée |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt` (~ligne 89) -- crée aujourd'hui `BehaviorEngine(BehaviorConfig(), …)` et `DirectionPicker(Random.Default)` : y lire réglages et état sauvegardé.
- `app/src/main/java/com/swipper/app/behavior/BehaviorConfig.kt`, `BehaviorEngine.kt` (`BehaviorState`, `initialState`, `recordSwipe`) -- étendre l'état ; garder la règle de bascule vers l'avant.
- `app/src/main/java/com/swipper/app/control/TargetApps.kt`, `AutomationController.kt` (`isTarget` injectable) -- apps cibles venant des réglages.
- `app/src/main/java/com/swipper/app/ui/HomeScreen.kt`, `MainActivity.kt` -- accès à l'écran de réglages, compteur, guide.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` -- ajouter `androidx.datastore:datastore-preferences`.
- Build : `JAVA_HOME` = JDK 21.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/swipper/app/settings/Settings.kt` -- data class des réglages, défauts, `validate(): List<FieldError>`, `toBehaviorConfig()` -- pur
- [x] `app/src/main/java/com/swipper/app/settings/SettingsRepository.kt` -- DataStore : Flow des réglages, `save()`, lecture/écriture de l'état du moteur -- persistance
- [x] `app/src/main/java/com/swipper/app/behavior/BehaviorEngine.kt` + tests -- `BehaviorState` étendu (session) et restauration -- session persistée
- [x] `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt`, `control/AutomationController.kt` -- moteur et apps cibles construits depuis les réglages à chaque Démarrer ; sauvegarde de l'état -- intégration
- [x] `app/src/main/java/com/swipper/app/ui/SettingsScreen.kt`, `ui/XiaomiGuide.kt`, `HomeScreen.kt`, `MainActivity.kt`, `strings.xml` -- écran de réglages, guide, compteur -- UI
- [x] `app/src/test/java/com/swipper/app/settings/SettingsTest.kt` -- validation, conversion, détection Xiaomi (fonction pure prenant le fabricant) -- matrice I/O

**Acceptance Criteria:**
- Given des réglages modifiés, when on tue l'app et la relance, then les valeurs sont conservées et utilisées au Démarrer suivant.

## Implementation Notes

- **Réglages → session** : `AutomationController` reçoit un `setupProvider` appelé à chaque `start()` (moteur + `DirectionPicker` + apps cibles construits depuis les réglages et l'état sauvegardé). Tant que la lecture initiale de DataStore (asynchrone, dans la portée du service) n'est pas finie, le provider renvoie `null` : le Démarrer est différé puis lancé par `setupReady()` ; `stop()` l'annule. Aucune lecture bloquante sur le thread principal.
- **État persisté** : `BehaviorState` = `swipesToday`, `day`, `sessionSwipes`, `sessionTarget`, `restUntil`. Le contrôleur le signale (`onStateChanged`) après chaque `recordSwipe` et chaque `nextAction` qui le modifie (fin de session, pause, changement de jour). `restUntil` est posé à chaque `Rest` ; restauré, il est respecté (`Rest(restant)` tant que l'heure ne l'a pas atteint), effacé à son terme, au changement de jour et hors plage. Une cible restaurée hors des nouvelles bornes est retirée et la session repart de zéro.
- **Écriture** : `saveBehaviorStateAsync` numérote les états et un seul collecteur (portée du processus, `Dispatchers.IO`) écrit toujours le plus récent ; une écriture en échec est journalisée sans arrêter les suivantes. `currentBehaviorState()` attend l'écriture du dernier état demandé avant de lire : une nouvelle instance du service ne relit jamais un état plus ancien. Un swipe ne peut être perdu que si le processus meurt avant cette écriture.
- **Lecture tolérante** : sans jour lisible, aucun état ; des champs de session corrompus sont remis à zéro sans toucher au compteur du jour (pas de contournement du plafond). Réglages illisibles → défauts.
- **Saisie** : délai médian à une décimale au plus (précision du formulaire relu, pas d'arrondi silencieux) ; min > max signalé sous le min, début = fin sous la fin.
- **Compteur de l'accueil** : pendant une session, celui du contrôleur (le même que la notification) ; à l'arrêt, l'état sauvegardé, ramené à 0 si son jour est passé (réévalué toutes les 30 s).
- **Écart assumé** : la visibilité du bouton flottant suit immédiatement les apps cibles enregistrées ; le filtrage des gestes, lui, garde celles du Démarrer.
- **Guide Xiaomi** : écrans `com.miui.securitycenter/…AutoStartManagementActivity` et `com.miui.powerkeeper/…HiddenAppsConfigActivity`, repli sur les infos de l'appli puis les réglages (`ActivityNotFoundException` et `SecurityException`) ; l'étape « verrouiller dans les récentes » est un texte (pas d'écran). Non vérifié sur un vrai téléphone Xiaomi.

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | blind, edge | Reconnexion : lecture de l'état avant l'écriture en attente → compteur plus bas, plafond dépassable | medium | patch | L'écriture asynchrone n'est ni attendue ni vidée avant la lecture. |
| 2 | blind | Fin de pause longue non sauvegardée : Stop → Démarrer saute la pause | medium | patch | `BehaviorState` sans échéance de pause. |
| 3 | blind, edge, verif | `runBlocking` sur le thread principal au Démarrer | medium | patch | Risque d'ANR si DataStore est lent à froid. |
| 4 | blind, edge | Délai à 2 décimales arrondi à l'affichage → formulaire modifié, valeur changée en silence | low | patch | `formatSeconds` arrondit au dixième. |
| 5 | blind | Un champ stocké invalide remet tous les réglages par défaut | low | rejet | Aucune migration de format aujourd'hui. |
| 6 | blind | Ratio illisible → 0 au lieu du défaut | low | patch | Correction directe. |
| 7 | blind, edge | Échec d'enregistrement → plantage sans message | low | patch | try/catch direct. |
| 8 | blind, edge | Apps cibles : réglages en direct pour le bouton flottant, instantané pour la boucle | low | rejet | Écart documenté ; le geste reste limité aux apps de la session. |
| 9 | blind | Collecte sans cycle de vie, minuterie du jour toujours active | low | patch | `collectAsStateWithLifecycle` déjà disponible. |
| 10 | blind | Compteur qui recule brièvement après Stop | low | rejet | Transitoire, le temps de l'écriture. |
| 11 | blind, edge | Session restaurée ≥ nouvelle cible → pause immédiate | low | patch | Remise à zéro directe. |
| 12 | edge | État corrompu dans la session → tout l'état ignoré, compteur à 0 | low | patch | Assainir les champs de session. |
| 13 | edge | Exception autre qu'IO dans l'écrivain asynchrone → plus aucune sauvegarde | low | patch | Garde directe. |
| 14 | edge | Réglages ouverts avant la première lecture → rien ne s'affiche | low | rejet | Fenêtre de quelques ms. |
| 15 | verif | État signalé après une fin de session non testé | medium | patch | Supprimer `reportState()` laisse tout vert. |
| 16 | verif | Aller-retour DataStore non testé | medium | patch | Inverser deux clés laisse tout vert. |
| 17 | verif | Câblage réglages → session dans le service non testé | medium | defer | Code de service Android ; extraire une fonction pure plus tard. |
| 18 | blind | Petits détails d'UI (texte d'exemple codé en dur, saisies « 7: », guide non masquable) | low | rejet | Cosmétique. |

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent

**Manual checks (if no CLI):**
- Émulateur : modifier le ratio et le plafond, `adb shell am force-stop com.swipper.app`, rouvrir : valeurs conservées.
