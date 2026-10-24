# Forged idea — android-auto-swiper

**Quoi :** app Android qui swipe à droite/gauche dans les applis de rencontre (Tinder, Bumble…) avec des gestes qui imitent un doigt humain.

## Décisions verrouillées
- Android uniquement. Kotlin. AccessibilityService + `dispatchGesture`. APK installé à la main.
- v1 = usage personnel.
- Choix gauche/droite par ratio configurable (ex. 70 % droite). Pas de lecture d'écran en v1.
- Geste humain : trajectoire courbe, accélération/décélération, point de départ, longueur et durée variables.
- Comportement humain : pauses irrégulières, sessions courtes, plafond quotidien, ouverture occasionnelle d'un profil.
- Démarrer / arrêter depuis l'app (bouton flottant ou notification).

## Rejeté
- iOS : une app ne peut pas toucher une autre app (sandbox).
- Play Store : politique Accessibility trop restrictive.

## Risques qui restent
- Non prouvé que le geste « humain » réduise la détection (simple intuition). La détection est probablement surtout côté serveur (ratio, rythme, zéro message).
- N'importe quelle app peut lister les services d'accessibilité activés.
- Risque de bannissement du compte : jamais nul.
