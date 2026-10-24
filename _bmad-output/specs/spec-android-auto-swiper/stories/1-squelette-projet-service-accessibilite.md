---
title: 'Story 1 — Squelette du projet et service d''accessibilité'
type: 'feature'
created: '2026-10-24'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '06328442d42a867629f8d061f5aeede7560f70ce'
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
- [x] `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, wrapper Gradle 8.10.2 -- projet racine, AGP 8.7.x, Kotlin 2.0.21 + plugin Compose, BOM Compose -- base de build reproductible
- [x] `app/build.gradle.kts` -- applicationId `com.swipper.app`, min 26 / target 35, Compose, JUnit -- module unique
- [x] `app/src/main/AndroidManifest.xml` -- activité principale ; service avec `BIND_ACCESSIBILITY_SERVICE` et meta-data ; pas d'`INTERNET` -- déclaration du service
- [x] `app/src/main/res/xml/accessibility_service_config.xml` + `res/values/strings.xml` -- config du service (cf. Boundaries) et description affichée par Android -- l'utilisateur sait ce qu'il active
- [x] `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt` -- service minimal : `onServiceConnected`, `onAccessibilityEvent` et `onInterrupt` vides -- point d'ancrage des stories 3 et 5
- [x] `app/src/main/java/com/swipper/app/service/AccessibilityStatus.kt` -- fonction pure `isServiceEnabled(setting: String?, component: ComponentName): Boolean`, plus un Flow basé sur ContentObserver -- source de vérité de l'état
- [x] `app/src/main/java/com/swipper/app/ui/MainActivity.kt`, `ui/HomeScreen.kt` -- écran état + « Activer » + aide « paramètres restreints » -- onboarding CAP-1
- [x] `app/src/test/java/com/swipper/app/service/AccessibilityStatusTest.kt` -- tests de `isServiceEnabled` : null, vide, autre service, nom court/long, plusieurs services séparés par `:`, casse -- matrice I/O
- [x] `.gitignore` (si Git) -- ignorer `build/`, `.gradle/`, `local.properties`, `.idea/`

**Acceptance Criteria:**
- Given une installation neuve, when l'utilisateur suit l'onboarding, then il atteint « Service actif » en moins de 2 minutes.
- Given l'APK, when on lit son manifeste fusionné, then il n'y a pas de permission `INTERNET`.
- Given le service actif, when un événement d'accessibilité arrive, then le service ne fait rien et ne plante pas.

## Implementation Notes

- Versions : AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Gradle 8.10.2, JUnit 5.11.3 (JUnit Platform). Build : `JAVA_HOME` = JDK 21 (le `java` du PATH est en Java 8).
- `isServiceEnabled(String?, ComponentName)` délègue à une surcharge pure (paquet, classe) + `parseComponent` (même règle que `unflattenFromString`), car les tests JVM n'ont pas les classes Android sans Robolectric.
- Comparaison sensible à la casse, comme `ComponentName.equals` (corrigé après le premier passage, qui l'ignorait).
- L'aide « paramètres restreints » s'affiche sur tout Android 13+ quand le service est inactif : l'app ne peut pas savoir si l'interrupteur est réellement grisé.
- Lignes 1 à 4 de la matrice (UI/appareil) : vérification manuelle sur téléphone (done_checkpoint) ; ligne 5 couverte par les tests unitaires. 11 tests, 0 échec ; APK debug produit.
- Vérification sur émulateur (AVD `swipper35`, Pixel 7, Android 15, 2026-10-24) : lignes 1, 2, 4 et 5 de la matrice OK. Après désactivation externe, l'écran affiche « inactif » en ≤ 1 s, sans crash. Ligne 3 (paramètres restreints) non reproductible : l'installation par adb n'est pas soumise à ce blocage ; à vérifier sur un vrai téléphone avec un APK ouvert depuis un gestionnaire de fichiers.

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | edge | Le repli `ACTION_SETTINGS` peut relancer ActivityNotFoundException | low | patch | Le second `startActivity` du catch n'est pas protégé ; correction directe. |
| 2 | edge | Service listé mais non lié → « actif » affiché à tort | medium | defer | Réel si le service plante ; l'intention gelée fixe le réglage comme source de vérité. À traiter en story 5 (état « connecté » publié par le service). |
| 3 | edge | `trim()` accepte des entrées que `unflattenFromString` refuse | low | patch | Divergence réelle avec le commentaire « même règle » ; correction = supprimer le trim. |
| 4 | edge | Affirmation de parité avec `unflattenFromString` inexacte | low | patch | Même cause que #3 ; `sep==0` donne un paquet vide qui ne correspond jamais, donc sans effet. |
| 5 | blind | Wrapper Gradle (`gradlew`, jar) et `.gitignore` absents | false | rejet | Fichiers présents dans l'arbre, exclus volontairement du diff de revue ; `.gitignore` dans le commit de base. |
| 6 | blind | La description du service promet trop (« sait seulement quelle appli… ») | medium | patch | Les événements `typeWindowStateChanged` portent le titre et la classe de la fenêtre. |
| 7 | blind | L'aide « paramètres restreints » oublie l'étape préalable | medium | patch | Sur Android 13+, l'entrée du menu ⋮ n'apparaît qu'après une première tentative d'activation. |
| 8 | blind | `ACCESSIBILITY_ENABLED` (interrupteur général) ignoré | maybe-false | defer | Il faudrait vérifier sur appareil si la liste peut rester remplie quand l'interrupteur général est à 0 ; serait medium. |
| 9 | blind | État lu à trois endroits dans MainActivity | false | rejet | Redondant mais correct ; aucun résultat faux ; la relecture à chaque retour est exigée par l'intention. |
| 10 | verif + blind | Le test code en dur le nom de classe ; le chemin `ComponentName` n'est pas testé | medium | patch | Renommer le service laisserait les tests verts alors que l'app ne le détecterait jamais. |
| 11 | blind | Pas de test du flow ContentObserver | medium | defer | Même constat que #13. |
| 12 | verif | Branches UI de HomeScreen non testées | medium | defer | Pas d'outillage de test UI ; contrôle manuel prévu (done_checkpoint). |
| 13 | verif | Rafraîchissement en direct (observer, onResume) non vérifié | medium | defer | Nécessite un test instrumenté qui modifie les réglages sécurisés. |
| 14 | verif | Aucun contrôle automatique « pas d'INTERNET » | medium | defer | `aapt dump permissions` fait à la main seulement ; une dépendance future pourrait l'ajouter. |
| 15 | blind | Attributs de config manquants (settingsActivity, summary, flags) | low | rejet | Améliorations, pas des défauts ; sans conséquence pour cette story. |
| 16 | blind | Pas d'icône ni de `dataExtractionRules` | low | rejet | Icône par défaut, cosmétique ; aucune donnée stockée pour l'instant. |
| 17 | blind | `onServiceConnected` ne fait qu'appeler `super` | false | rejet | Squelette exigé par la spec comme point d'ancrage. |
| 18 | blind | Pas de thème sombre | low | rejet | Cosmétique ; la correction ajoute des branches. |
| 19 | blind | Le chemin « Réglages → Accessibilité → Swipper » est faux chez certains constructeurs | low | patch | Chez Samsung, le service est sous « Applications installées » ; correction = reformuler. |
| 20 | blind | Dépendances coroutines/lifecycle seulement transitives | low | rejet | Pratique courante ; peu probable de casser ; la correction ajoute des déclarations. |

## Design Notes

`ENABLED_ACCESSIBILITY_SERVICES` contient des composants séparés par `:`, au format long (`com.swipper.app/com.swipper.app.service.SwipeAccessibilityService`) ou court (`com.swipper.app/.service.SwipeAccessibilityService`). On compare avec `ComponentName.unflattenFromString`, et non avec `contains` sur la chaîne, pour éviter les faux positifs.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest` -- expected: tous les tests passent
- `./gradlew :app:assembleDebug` -- expected: `app/build/outputs/apk/debug/app-debug.apk` produit

**Manual checks (if no CLI):**
- Sur téléphone : installer l'APK, activer le service via l'onboarding, vérifier le passage actif/inactif dans les deux sens.
