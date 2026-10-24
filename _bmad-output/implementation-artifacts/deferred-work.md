- source_spec: `_bmad-output/specs/spec-android-auto-swiper/stories/1-squelette-projet-service-accessibilite.md`
  summary: L'état « actif » vient seulement du réglage ENABLED_ACCESSIBILITY_SERVICES ; un service listé mais non lié (planté) s'affiche comme actif.
  evidence: Revue story 1 (#2, #8). Le service devrait publier un état « connecté » (onServiceConnected/onUnbind) en story 5 ; vérifier aussi sur appareil le cas ACCESSIBILITY_ENABLED=0 (non vérifié, serait medium).
- source_spec: `_bmad-output/specs/spec-android-auto-swiper/stories/1-squelette-projet-service-accessibilite.md`
  summary: Aucun test automatique pour l'UI de HomeScreen ni pour le rafraîchissement en direct (ContentObserver, onResume).
  evidence: Revue story 1 (#11, #12, #13). À ajouter avec la première infrastructure de tests Compose ou instrumentés.
- source_spec: `_bmad-output/specs/spec-android-auto-swiper/stories/1-squelette-projet-service-accessibilite.md`
  summary: Pas de contrôle automatique garantissant l'absence de la permission INTERNET dans le manifeste fusionné.
  evidence: Revue story 1 (#14). Vérifié à la main avec aapt ; une dépendance future pourrait l'ajouter sans qu'aucune commande ne le détecte.
- source_spec: `_bmad-output/specs/spec-android-auto-swiper/stories/2-generateur-gestes-humains.md`
  summary: Le geste ralentit jusqu'à une vitesse quasi nulle à la fin ((1−cos πu)/2) ; une carte de Tinder pourrait revenir en place au lieu de partir.
  evidence: Revue story 2 (#17), non vérifié. À observer lors du premier swipe réel (story 3) ; si la carte revient, terminer l'easing avec une vitesse résiduelle (ease-out partiel). Serait medium.
- source_spec: `_bmad-output/specs/spec-android-auto-swiper/stories/3-envoi-des-gestes.md`
  summary: Fiabilité de l'exécution des gestes à reprendre avec la boucle automatique (story 5) : levée du doigt après un segment refusé, exception pendant l'envoi (pas de CoroutineExceptionHandler), levée envoyée pendant un segment en cours.
  evidence: Revue story 3 (#1, #2, #11, #13), non vérifié sur appareil. Tester un Stop en plein swipe avec « Afficher les appuis » ; ajouter un gestionnaire d'exceptions au scope du service.
- source_spec: `_bmad-output/specs/spec-android-auto-swiper/stories/3-envoi-des-gestes.md`
  summary: Pas de test automatique pour GestureDispatcher (construction des traits) ni pour testSwipe / « Service inactif ».
  evidence: Revue story 3 (#6). Code Android sans Robolectric ; vérifié manuellement sur émulateur pour le chemin connecté.
- source_spec: `_bmad-output/implementation-artifacts/spec-fix-lancer-geste.md`
  summary: Sur HyperOS, le service est tué (balayage dans les récents, lowmemorykiller) et Android le marque « planté » sans le relancer ; Swipper affiche pourtant « Service actif ». Les Toasts envoyés depuis l'arrière-plan sont aussi bloqués.
  evidence: Logs du Redmi 2026-10-25 (SwipeUpClean, lowmemorykiller, « Crashed services », « Toast already killed »). À traiter en story 5 : état « connecté » publié par le service, notification de premier plan pour la session, guide Xiaomi (démarrage auto, batterie sans restriction, verrouillage dans les récents).
