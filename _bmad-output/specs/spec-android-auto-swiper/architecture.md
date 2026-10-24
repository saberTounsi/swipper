# Architecture — Auto-swiper Android

## Stack

- Kotlin, Gradle (Kotlin DSL), un seul module `app`. minSdk 26, targetSdk = dernière version stable.
- UI : Jetpack Compose (écran principal, réglages, onboarding).
- Persistance : Jetpack DataStore (Preferences) pour les réglages et le compteur du jour.
- Concurrence : coroutines Kotlin ; une seule coroutine pilote la boucle de swipe.
- Tests : JUnit 5 + kotlinx-coroutines-test pour la logique pure (géométrie, décision, comportement).

## Règle de découpage

La logique (gestes, décision, comportement) est du **Kotlin pur sans dépendance Android**, testable avec une horloge et un générateur aléatoire injectés. Seuls `SwipeAccessibilityService`, `GestureDispatcher` et l'UI touchent au SDK Android.

## Modules (packages)

| Package | Rôle | Android ? |
|---|---|---|
| `gesture` | `GestureGenerator` : produit un `SwipePlan` (liste de segments : points + durée) à partir de la zone, de la direction et d'un `Random`. | Non |
| `decision` | `DirectionPicker` : droite/gauche selon le ratio. | Non |
| `behavior` | `BehaviorEngine` : décide la prochaine action (swipe, ouvrir profil, pause courte, fin de session, plafond atteint) et le délai avant elle. Dépend d'une `Clock`. | Non |
| `service` | `SwipeAccessibilityService` : reçoit les changements d'app au premier plan, expose l'état, exécute les plans. `GestureDispatcher` : convertit un `SwipePlan` en `GestureDescription`. | Oui |
| `control` | `AutomationController` : boucle de la session, états Idle/Running/Paused/Stopped, bouton flottant (`TYPE_ACCESSIBILITY_OVERLAY`) et notification. | Oui |
| `settings` | `SettingsRepository` (DataStore), `DailyCounter`. | Oui (fin) |
| `ui` | Écrans Compose : onboarding accessibilité, accueil (état + compteur), réglages. | Oui |

## Algorithme du geste humain (`gesture`)

1. **Départ** : point aléatoire dans la zone de swipe (par défaut x ∈ [0,35 ; 0,65], y ∈ [0,45 ; 0,65] de l'écran), avec un léger biais vers la main droite.
2. **Arrivée** : à 55–85 % de la largeur dans la direction voulue, y décalé de ±4–12 % (un pouce dérive vers le haut ou le bas).
3. **Tracé** : courbe de Bézier cubique ; points de contrôle décalés perpendiculairement de 2–8 % de la largeur, côté tiré au hasard.
4. **Vitesse** : la courbe est découpée en 6–10 segments enchaînés par `StrokeDescription.continueStroke`. La durée totale (180–600 ms) est répartie selon une courbe en cloche (ease-in-out avec du bruit). Le geste est donc lent au départ, rapide au milieu et ralentit à la fin.
5. **Micro-variations** : bruit de ±1–3 px sur les points intermédiaires ; 10 % de gestes « hésitants » avec un départ plus lent.
6. **Garde-fous** : tous les points sont ramenés dans l'écran, et chaque segment dure au moins 16 ms.

`GestureDispatcher` envoie les segments un par un : chaque `dispatchGesture` porte un segment, et son callback `onCompleted` déclenche le suivant (`willContinue = true` sauf pour le dernier). En cas d'annulation, le plan est abandonné.

## Moteur de comportement (`behavior`)

- **Délai entre swipes** : distribution log-normale (médiane ~1,8 s, bornée à 0,8–8 s), avec 5 % de « distractions » de 10–40 s.
- **Session** : N swipes tirés dans [min, max] (ex. 25–60), puis une pause longue (ex. 15–90 min).
- **Plafond quotidien** : au plafond (ex. 150), arrêt jusqu'à minuit local.
- **Ouverture de profil** : avec une probabilité p (ex. 8 %), tap à la position calibrée de l'app, temps de lecture de 2–6 s, retour, puis décision.
- Toutes les valeurs sont dans les réglages ; le moteur ne lit jamais l'heure système directement, il passe par `Clock`.

## Sécurité d'exécution

- À chaque `TYPE_WINDOW_STATE_CHANGED`, le service met à jour le package au premier plan. Si ce n'est pas une app cible, il passe en Paused et annule le geste en cours.
- Stop = annulation de la coroutine de session ; le service ignore tout callback de geste reçu après l'arrêt.
- Le service ne déclare aucune capacité de lecture de contenu en v1 (`canRetrieveWindowContent="false"`).

## Configuration du service

`res/xml/accessibility_service_config.xml` : `canPerformGestures="true"`, `accessibilityEventTypes="typeWindowStateChanged"`, `packageNames` non fixé (le filtrage se fait dans le code pour que la liste reste modifiable).
