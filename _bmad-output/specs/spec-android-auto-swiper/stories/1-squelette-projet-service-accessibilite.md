---
title: 'Story 1 — Squelette du projet et service d''accessibilité'
type: 'feature'
created: '2026-10-24'
status: 'ready-for-dev'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-android-auto-swiper/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Le dépôt est vide : il n'y a ni projet Android ni service d'accessibilité, alors que tout le reste (gestes, comportement, contrôle) en dépend (CAP-1).

**Approach:** Créer un projet Kotlin/Compose « Swipper » qui déclare `SwipeAccessibilityService` (gestes autorisés, aucune lecture d'écran), guide l'utilisateur pour l'activer, et affiche en direct si le service est actif.

## Boundaries & Constraints

**Always:** minSdk 26, compileSdk/targetSdk 35 ; Kotlin + Jetpack Compose ; un seul module `app` ; packages selon `architecture.md` ; pas de permission `INTERNET` ; `canRetrieveWindowContent="false"`, `canPerformGestures="true"`, événements `typeWindowStateChanged` seulement. L'état « actif » vient de `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, observé en direct (ContentObserver) et relu à chaque retour sur l'écran.

**Never:** aucun geste envoyé, aucune logique de swipe, de réglage ni de bouton flottant dans cette story ; pas d'injection de dépendances (Hilt) ni de module Gradle supplémentaire ; pas de bibliothèque hors AndroidX/Compose/JUnit.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Premier lancement | Service désactivé | Écran « Service inactif » + bouton « Activer » ouvrant `Settings.ACTION_ACCESSIBILITY_SETTINGS` | N/A |
| Activation | L'utilisateur active Swipper puis revient | L'écran passe à « Service actif » sans redémarrer l'app | N/A |
| Désactivation externe | Service coupé dans les réglages pendant que l'app est ouverte | L'écran repasse à « inactif » en < 1 s | N/A |
| Android 13+ en APK | Interrupteur grisé (« paramètre restreint ») | L'onboarding explique : Infos de l'appli → ⋮ → « Autoriser les paramètres restreints », avec un bouton qui ouvre les infos de l'appli | Lien vers `ACTION_APPLICATION_DETAILS_SETTINGS` |
| Liste vide ou mal formée | `ENABLED_ACCESSIBILITY_SERVICES` null ou vide | État « inactif » | Aucun crash |

**Décisions :**
- Outillage : installer les command-line tools Android (platform 35, build-tools, platform-tools) dans `D:\outils_dev\android-sdk` ; compiler et tester localement avec le JDK 21 ; `local.properties` pointe vers ce SDK.
- Git : `git init` + `.gitignore` Android ; un commit à la fin de chaque story (`_bmad/render/` ignoré).

</frozen-after-approval>

## Code Map

- Dépôt vide : rien à réutiliser ; `_bmad/` et `_bmad-output/` ne doivent pas être modifiés par le build.
- `architecture.md` -- packages `service`, `ui` concernés ici ; `gesture`, `behavior`, `decision`, `control`, `settings` viendront plus tard (ne pas créer de fichiers vides).

## Tasks & Acceptance

**Execution:**
- [ ] `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, wrapper Gradle 8.10.2 -- projet racine, AGP 8.7.x, Kotlin 2.0.21 + plugin Compose, BOM Compose -- base de build reproductible
- [ ] `app/build.gradle.kts` -- applicationId `com.swipper.app`, min 26 / target 35, Compose, JUnit -- module unique
- [ ] `app/src/main/AndroidManifest.xml` -- activité principale ; service avec `BIND_ACCESSIBILITY_SERVICE` et meta-data ; pas d'`INTERNET` -- déclaration du service
- [ ] `app/src/main/res/xml/accessibility_service_config.xml` + `res/values/strings.xml` -- config du service (cf. Boundaries) et description affichée par Android -- l'utilisateur sait ce qu'il active
- [ ] `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt` -- service minimal : `onServiceConnected`, `onAccessibilityEvent` et `onInterrupt` vides -- point d'ancrage des stories 3 et 5
- [ ] `app/src/main/java/com/swipper/app/service/AccessibilityStatus.kt` -- fonction pure `isServiceEnabled(setting: String?, component: ComponentName): Boolean`, plus un Flow basé sur ContentObserver -- source de vérité de l'état
- [ ] `app/src/main/java/com/swipper/app/ui/MainActivity.kt`, `ui/HomeScreen.kt` -- écran état + « Activer » + aide « paramètres restreints » -- onboarding CAP-1
- [ ] `app/src/test/java/com/swipper/app/service/AccessibilityStatusTest.kt` -- tests de `isServiceEnabled` : null, vide, autre service, nom court/long, plusieurs services séparés par `:`, casse -- matrice I/O
- [ ] `.gitignore` (si Git) -- ignorer `build/`, `.gradle/`, `local.properties`, `.idea/`

**Acceptance Criteria:**
- Given une installation neuve, when l'utilisateur suit l'onboarding, then il atteint « Service actif » en moins de 2 minutes.
- Given l'APK, when on lit son manifeste fusionné, then il n'y a pas de permission `INTERNET`.
- Given le service actif, when un événement d'accessibilité arrive, then le service ne fait rien et ne plante pas.

## Implementation Notes

## Spec Change Log

## Review Triage Log

## Design Notes

`ENABLED_ACCESSIBILITY_SERVICES` contient des composants séparés par `:`, au format long (`com.swipper.app/com.swipper.app.service.SwipeAccessibilityService`) ou court (`com.swipper.app/.service.SwipeAccessibilityService`). On compare avec `ComponentName.unflattenFromString`, et non avec `contains` sur la chaîne, pour éviter les faux positifs.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest` -- expected: tous les tests passent
- `./gradlew :app:assembleDebug` -- expected: `app/build/outputs/apk/debug/app-debug.apk` produit

**Manual checks (if no CLI):**
- Sur téléphone : installer l'APK, activer le service via l'onboarding, vérifier le passage actif/inactif dans les deux sens.
