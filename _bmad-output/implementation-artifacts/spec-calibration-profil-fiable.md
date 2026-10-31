---
title: 'Calibration de l''ouverture de profil plus fiable'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_commit: '092ddaa46509b14574a6fe62704367addd87b7fc'
route: 'dispatch'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Séance Redmi du 2026-10-25 : la calibration de Tinder a été posée sur la photo ; le tap fait défiler les photos au lieu d'ouvrir le profil, puis le Retour sort de Tinder, et rien ne signale l'erreur. Sur Bumble, le profil s'ouvre en faisant défiler la carte, pas par un tap : la calibration de Bumble n'a pas de sens.

**Approach:** (1) Consigne explicite dans le calque : toucher la flèche ↑ en bas de la carte, pas la photo. (2) Après le toucher, le calque montre un rond à l'endroit touché avec « Valider » et « Recommencer » ; rien n'est enregistré avant Valider. (3) Deux Retours de suite qui font sortir de l'app effacent la calibration de cette app, avec un message invitant à recalibrer. (4) Ouverture de profil réservée à Tinder.

**Décisions utilisateur (2026-10-31) :** aperçu = validation dans le calque après le toucher ; coupure = effacer la calibration (persistant, « Non calibré » dans les réglages) ; Bumble = pas d'ouverture de profil (on y fait défiler, pas de tap).

## Boundaries & Constraints

**Always:** Ouverture de profil seulement si l'app supporte l'ouverture par tap (Tinder seul) et est calibrée ; une position Bumble déjà enregistrée est ignorée. Une « sortie après Retour » = après un Retour accepté, l'app au premier plan n'est plus celle du profil avant le swipe qui suit. Compteur par app : remis à 0 dès qu'un Retour laisse l'app au premier plan jusqu'au swipe ; à 2 sorties de suite, la calibration de l'app est effacée et le compteur remis à 0. La boucle garde son comportement actuel en sortie d'app (Paused). Le calque garde Annuler et Retour, et un glissé n'est toujours pas pris pour un toucher.

**Never:** Pas de lecture du contenu de l'écran pour savoir si le profil s'est ouvert. Pas de nouvelle permission. Ne pas arrêter la boucle sur une sortie après Retour. Pas d'ouverture de profil sur Bumble par défilement.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Retour normal | Retour accepté, Tinder reste au premier plan, swipe | compteur Tinder à 0 | N/A |
| 1re sortie | Retour accepté, autre app avant le swipe | compteur 1, Paused, calibration gardée | N/A |
| 2e sortie de suite | idem une seconde fois | calibration Tinder effacée, message, compteur 0 | effacement en échec : journalisé |
| Sorties non consécutives | sortie, Retour normal, sortie | compteur 1, rien d'effacé | N/A |
| Pause pendant l'attente après Retour | écran éteint / pause, app inchangée | compteur inchangé | N/A |
| Bumble avec position ancienne | Bumble au premier plan, point Bumble enregistré | jamais d'ouverture de profil, swipes normaux | N/A |
| Calque : toucher puis Recommencer | rond affiché, Recommencer | rond retiré, nouveau toucher attendu, rien enregistré | N/A |
| Calque : toucher puis Valider | rond affiché, Valider | position enregistrée | échec d'écriture : message d'échec existant |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/swipper/app/control/TargetApps.kt` -- ajouter les apps à ouverture de profil par tap (`TINDER`) et `supportsProfile(pkg)`.
- `app/src/main/java/com/swipper/app/control/AutomationController.kt:253-283` -- branche `Action.OpenProfile` : `goBack()` puis `delay(backPauseMs)` sous `whileGateOpen` ; le compteur de sorties et un nouveau rappel `onProfileBackExits: (String) -> Unit` (défaut `{}`) se branchent ici. `closeLeftProfile` n'est pas concerné.
- `app/src/main/java/com/swipper/app/service/SwipeAccessibilityService.kt` -- `sessionSetup()` (`profileAvailable`), `tapExecutor`, `startCalibration` (refuser hors `supportsProfile`) ; brancher `onProfileBackExits` → `SettingsRepository.clearProfilePoint` + `toast` + `Log`. Les Toasts d'arrière-plan peuvent être bloqués par HyperOS : « Non calibré » dans les réglages reste l'indication durable.
- `app/src/main/java/com/swipper/app/settings/SettingsRepository.kt:90` -- à côté de `saveProfilePoint`, ajouter `clearProfilePoint(pkg)` (retire les deux clés).
- `app/src/main/java/com/swipper/app/control/CalibrationOverlay.kt` -- deux phases : attente du toucher, puis rond + Valider/Recommencer (boutons et consigne en haut, la flèche est en bas) ; `onPoint` seulement sur Valider.
- `app/src/main/java/com/swipper/app/ui/SettingsScreen.kt:157-170` + `app/src/main/res/values/strings.xml:88-101` -- seulement le bouton « Calibrer Tinder » ; consignes et messages mis à jour.
- Tests : `app/src/test/java/com/swipper/app/control/AutomationControllerTest.kt` (`ProfileRig`, l.735), `SettingsRepositoryTest.kt`, `TargetApps` (nouveau test si absent).

## Tasks & Acceptance

**Execution:**
- [x] `TargetApps.kt` -- `PROFILE_PACKAGES` et `supportsProfile` -- source unique de « l'app ouvre son profil par tap ».
- [x] `SettingsRepository.kt` -- `clearProfilePoint` -- effacement persistant.
- [x] `AutomationController.kt` -- compteur de sorties après Retour par app et rappel `onProfileBackExits` -- règle pure et testable.
- [x] `SwipeAccessibilityService.kt` -- `supportsProfile` dans `profileAvailable`, `tapExecutor`, `startCalibration` ; câbler `onProfileBackExits` -- Bumble exclu, effacement + message.
- [x] `CalibrationOverlay.kt` -- phase de validation avec rond, Valider, Recommencer -- erreur de toucher visible avant l'enregistrement.
- [x] `SettingsScreen.kt`, `strings.xml` -- bouton Tinder seul ; consigne « touchez la flèche ↑ en bas de la carte, pas la photo » ; messages de validation et d'effacement -- consignes claires.
- [x] Tests -- toutes les lignes contrôleur de la matrice dans `AutomationControllerTest` ; `clearProfilePoint` dans `SettingsRepositoryTest` ; `supportsProfile` -- verrouille les règles.

**Acceptance Criteria:**
- Given les réglages, when on regarde la section Ouverture de profil, then seul « Calibrer Tinder » est proposé et la consigne parle de la flèche ↑.
- Given une calibration Tinder sur la photo et l'ouverture à 30 %, when deux ouvertures de suite font sortir de Tinder, then les réglages affichent « Non calibré » pour Tinder.

## Implementation Notes

- Sortie détectée dans la branche `OpenProfile` : après un Retour accepté, `backDone` ; à la fin de l'attente, si l'app a changé, on sort sans swiper. Après `whileGateOpen` : app changée → compteur +1 (à 2 : rappel puis remise à 0) ; swipe envoyé → remise à 0 ; sinon (pause, écran) inchangé. Limite : si l'événement « autre app » arrive après la fin de l'attente (≥ 0,6 s), le swipe part et la sortie n'est pas comptée.
- Le service efface via `clearProfilePoint`, journalise et tente un Toast (peut être bloqué par HyperOS en arrière-plan).
- Calque : rond jaune de 44 dp à l'endroit touché ; pendant la validation, le fond ignore les touches ; Recommencer revient à l'attente. Partie Android sans test automatique (pas de Robolectric) : vérification manuelle.
- Les lignes « Bumble avec position ancienne » et `startCalibration` reposent sur `TargetApps.supportsProfile` (testé) ; le câblage dans le service n'est pas testé (même report que la story 6).

## Spec Change Log

## Review Triage Log

| # | Couche | Constat | Verdict | Route | Preuve |
|---|---|---|---|---|---|
| 1 | blind, edge | Compteur de sorties jamais vidé : une sortie d'une ancienne calibration ou d'une session passée + une nouvelle efface la position refaite | medium | patch | `profileBackExits.clear()` dans `start()` (une calibration arrête la boucle) ; test « compteur vide à chaque Démarrer ». |
| 2 | verif | Garde « app changée après le Retour » non testée vers une autre app cible (Bumble : porte ouverte) | medium | patch | Test ajouté ; il échoue si on retire la garde (swipe dans Bumble). |
| 3 | verif | Règle « Tinder seul » dupliquée dans deux lambdas du service, non testée | medium | patch | `Settings.openableProfilePoint` (pure, testée avec une position Bumble ancienne), utilisée par `profileAvailable` et `tapExecutor`. |
| 4 | blind, edge | Valider convertit avec la taille d'écran du moment du clic, pas du toucher | low | patch | `FractionPoint` calculé au toucher. |
| 5 | blind | Surcharges `toast(Int)` courte / `toast(String)` longue implicites | low | patch | Renommée `toastLong`. |
| 6 | blind | Appel `countProfileBackExit` illisible sur une ligne | low | patch | `left` nommé, appel sur plusieurs lignes. |
| 7 | blind, edge, verif | Sortie volontaire de l'utilisateur pendant l'attente de 0,6–1,5 s comptée comme échec | low | rejet | C'est la définition approuvée (« l'app n'est plus celle du profil avant le swipe ») ; fenêtre d'1 s, et il en faut 2 dans la même session. |
| 8 | edge | Événement « Accueil » reçu après la fin de l'attente : swipe sur le bureau, compteur remis à 0 | maybe-false | defer | Dépend du délai réel des événements sur HyperOS ; à observer sur le Redmi. Serait medium. |
| 9 | edge | Échec de swipe (`Failed`) compté comme « resté dans l'app » | false | rejet | L'app est restée au premier plan : c'est la remise à 0 prévue. |
| 10 | edge | Retour de `closeLeftProfile` non compté | low | rejet | Cas rare (lecture interrompue puis reprise) ; hors du chemin approuvé. |
| 11 | blind | Effacement non lié au point qui a causé les sorties (recalibration pendant l'écriture) | low | rejet | Écriture immédiate ; une recalibration prend plusieurs secondes et arrête la boucle. |
| 12 | edge | Exception autre qu'`IOException` pendant l'effacement | low | rejet | Remonte au `CoroutineExceptionHandler` du service, qui journalise. |
| 13 | edge | Trois boutons trop larges sur petit écran ou grande police | low | rejet | ~3 × 300 px sur 1 080 px ; Retour annule toujours. |
| 14 | edge | Consigne longue qui passe sous les boutons (10 % / 25 %) | low | rejet | ~450 px de haut pour 600 px disponibles sur le Redmi. |
| 15 | blind | Premier Retour sortant sans message ; Toast peut-être bloqué | low | rejet | Retour approuvé : Toast + « Non calibré » dans les réglages. |
| 16 | blind | Liste des apps calibrables et « deux Retours » codés en dur dans l'UI | low | rejet | Une seule app ; pas de besoin de généraliser. |
| 17 | blind, verif | Positions Bumble anciennes laissées dans DataStore | low | rejet | Ignorées par `openableProfilePoint` (testé) ; sans effet. |
| 18 | blind, verif | Calque Valider / Recommencer sans test automatique | medium | defer | Code de vues Android, pas de Robolectric ; test manuel sur le Redmi. |
| 19 | blind, verif | Câblage `onProfileBackExits` → `clearProfilePoint` dans le service non testé | medium | defer | Même report que le câblage de session de la story 6. |

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` -- expected: tous les tests passent, APK construit

**Manual checks (if no CLI):**
- Redmi : Calibrer Tinder → toucher la photo → le rond apparaît sur la photo → Recommencer → toucher la flèche ↑ → Valider. Ouverture à 30 % : quelques profils s'ouvrent et se referment. Puis recalibrer volontairement sur la photo : après 2 sorties de Tinder, « Non calibré ».
