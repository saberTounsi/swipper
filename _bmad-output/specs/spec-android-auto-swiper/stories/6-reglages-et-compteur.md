---
title: 'Story 6 — Réglages, compteur sauvegardé et guide Xiaomi'
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
- [ ] `app/src/main/java/com/swipper/app/settings/Settings.kt` -- data class des réglages, défauts, `validate(): List<FieldError>`, `toBehaviorConfig()` -- pur
- [ ] `app/src/main/java/com/swipper/app/settings/SettingsRepository.kt` -- DataStore : Flow des réglages, `save()`, lecture/écriture de l'état du moteur -- persistance
- [ ] `app/src/main/java/com/swipper/app/behavior/BehaviorEngine.kt` + tests -- `BehaviorState` étendu (session) et restauration -- session persistée
- [ ] `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt`, `control/AutomationController.kt` -- moteur et apps cibles construits depuis les réglages à chaque Démarrer ; sauvegarde de l'état -- intégration
- [ ] `app/src/main/java/com/swipper/app/ui/SettingsScreen.kt`, `ui/XiaomiGuide.kt`, `HomeScreen.kt`, `MainActivity.kt`, `strings.xml` -- écran de réglages, guide, compteur -- UI
- [ ] `app/src/test/java/com/swipper/app/settings/SettingsTest.kt` -- validation, conversion, détection Xiaomi (fonction pure prenant le fabricant) -- matrice I/O

**Acceptance Criteria:**
- Given des réglages modifiés, when on tue l'app et la relance, then les valeurs sont conservées et utilisées au Démarrer suivant.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent

**Manual checks (if no CLI):**
- Émulateur : modifier le ratio et le plafond, `adb shell am force-stop com.swipper.app`, rouvrir : valeurs conservées.
