---
id: SPEC-android-auto-swiper
companions:
  - architecture.md
  - architecture-diagrams.md
sources:
  - ../../forge/android-auto-swiper/forged-idea.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Auto-swiper Android

## Why

Une vision personnelle : swiper automatiquement dans les applis de rencontre (Tinder, Bumble) sans que l'automatisation ressemble à un robot. Les outils existants tapent sur des boutons à un rythme mécanique. Cette app glisse comme un doigt et se comporte comme une personne (pauses, sessions courtes, plafond quotidien). L'hypothèse que cela réduit le risque de bannissement n'est pas prouvée ; elle est assumée.

## Capabilities

- **CAP-1**
  - **intent:** L'utilisateur active le service d'accessibilité de l'app grâce à un parcours guidé et voit à tout moment s'il est actif.
  - **success:** Depuis une installation neuve, l'utilisateur arrive à l'état « service actif » en moins de 2 minutes ; l'écran principal reflète l'état réel du service, y compris après une désactivation dans les réglages Android.
- **CAP-2**
  - **intent:** Chaque swipe est un geste continu au tracé courbe, avec accélération puis décélération, dont le point de départ, la longueur, la courbure et la durée varient à chaque fois.
  - **success:** Sur 1 000 gestes générés, aucun n'est identique, tous restent dans la zone de swipe et dans 180–600 ms, et la vitesse au milieu du geste dépasse celle du début et de la fin (tests unitaires) ; sur appareil, l'app cible enregistre le swipe dans la bonne direction.
- **CAP-3**
  - **intent:** La direction de chaque swipe (droite/gauche) suit un ratio réglable par l'utilisateur.
  - **success:** Avec un ratio de 70 %, la proportion de swipes à droite sur 1 000 décisions est comprise entre 65 % et 75 % (test unitaire avec graine fixe).
- **CAP-4**
  - **intent:** Le rythme imite une personne : délai irrégulier entre swipes, sessions bornées séparées de longues pauses, plafond quotidien, et ouverture occasionnelle d'un profil avant de décider.
  - **success:** Avec une horloge simulée, les délais ne sont jamais constants, une session ne dépasse pas sa limite, le plafond quotidien arrête tout jusqu'au lendemain, et l'ouverture de profil a lieu à la fréquence réglée (± 5 points sur 1 000 swipes).
- **CAP-5**
  - **intent:** L'utilisateur démarre, met en pause et arrête l'automatisation depuis un bouton flottant et depuis la notification, et peut l'arrêter instantanément.
  - **success:** Un appui sur « Stop » arrête tout geste en moins de 500 ms ; l'automatisation s'arrête seule dès que l'app cible n'est plus au premier plan.
- **CAP-6**
  - **intent:** L'utilisateur règle les paramètres (ratio, limites, pauses, apps cibles, zone de swipe) et voit le nombre de swipes du jour.
  - **success:** Les réglages survivent à un redémarrage du téléphone ; le compteur du jour est exact et se remet à zéro à minuit (heure locale).

## Constraints

- Android uniquement, Kotlin, minSdk 26 (enchaînement de segments de geste via `continueStroke`).
- Distribution par APK installé à la main ; pas de publication sur le Play Store.
- Usage personnel : aucune donnée envoyée sur le réseau, pas de permission `INTERNET`.
- Pas de lecture du contenu de l'écran en v1 : les coordonnées viennent de la zone de swipe réglée, la direction du ratio.
- Aucun geste n'est envoyé si l'app au premier plan n'est pas une app cible autorisée.

## Non-goals

- iOS (une app ne peut pas toucher une autre app sur iOS).
- Publication sur le Play Store ou distribution à d'autres personnes.
- Décision selon le contenu du profil (photos, texte, IA) en v1.
- Envoi automatique de messages aux matchs.
- Garantir que l'app ne sera pas détectée : le risque de bannissement reste et n'est pas mesuré.

## Success signal

- Sur un vrai téléphone, l'utilisateur lance une session sur Tinder : l'app enchaîne des swipes reconnus par Tinder, au ratio réglé, avec des pauses irrégulières, s'arrête au plafond, et s'arrête net dès que l'utilisateur appuie sur Stop ou quitte Tinder.

## Assumptions

- Apps cibles v1 : Tinder et Bumble, identifiées par leur nom de package ; liste modifiable.
- La zone de swipe est exprimée en fractions de l'écran, ce qui la rend indépendante de la résolution.
- Le fait que des gestes humains réduisent la détection est une intuition non vérifiée ; la détection est probablement surtout côté serveur (ratio, rythme, absence de messages).

## Open Questions

- Où taper pour « ouvrir un profil » dans chaque app cible ? À calibrer sur l'appareil réel (CAP-4).
- Sur quel appareil et quelle version d'Android l'app sera-t-elle testée ?
- Quelles valeurs par défaut pour les limites (swipes par session, plafond quotidien, durée des pauses) ?
