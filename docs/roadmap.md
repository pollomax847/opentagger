# Idées pour une future mise à jour

Notes de travail, pas des specs figées — à réviser/discuter avant de démarrer l'une ou l'autre.

## Cover Flow (carrousel de pochettes)

Demandé explicitement, mis de côté volontairement pour un lot séparé après la vue arborescence par
album (voir `ui/AlbumTreeTableModel.java`) — bien plus gros chantier que "juste une autre vue du même
tableau" : un composant graphique à part, façon vieil iTunes/Finder (pochettes en carrousel,
défilement horizontal, sélection centrale mise en avant).

### Ce qui existe déjà et peut être réutilisé

- **Regroupement par album** : la même logique de clé de groupe existe DEUX fois actuellement
  (`RenamePreviewDialog.groupKey()/groupTitle()` et `AlbumTreeTableModel.groupKeyFor()/
  groupTitleFor()`, quasi identiques). Avant d'écrire un troisième regroupeur pour Cover Flow,
  extraire une classe partagée (`AlbumGrouping` ou similaire) dans `com.opentagger` — les deux
  call sites existants migrent dessus, zéro comportement changé, et Cover Flow réutilise directement.
- **Cache pochettes** : `MetadataCache`/`CaaClient`/`FanArtClient` existent déjà pour la résolution
  réseau ; il manque un cache de MINIATURES en mémoire (pas juste sur disque) pour un défilement
  fluide sur une bibliothèque de plusieurs milliers d'albums.

### Vrai prérequis qui manque (à trancher avant de coder)

La résolution de pochette est **différée à l'Enregistrement** ("façon Picard", voir
`TagEnrichment.saveEntry()`) — un album encore IDENTIFIED (pas encore enregistré) n'a donc pas de
pochette résolue en mémoire. Cover Flow devrait soit :
1. Ne montrer que les albums déjà TAGGED (pochette garantie disponible), ou
2. Déclencher une résolution de pochette "aperçu seul" (sans écrire sur le disque) pour les albums
   IDENTIFIED affichés — coût réseau/disque à mesurer, pas anodin sur une bibliothèque de plusieurs
   centaines de milliers de fichiers.
Pas tranché — nécessite une discussion avec l'utilisateur avant implémentation.

### Points d'attention perf (même culture que le reste de ce projet)

- Chargement des images : jamais l'image pleine résolution pour chaque album visible dans le
  carrousel — miniatures pré-redimensionnées, chargées en arrière-plan (`SwingWorker`, même motif
  que `MainFrame.loadCoverThumb()` existant), avec un cache LRU borné en mémoire.
- Sur ~2 To / centaines de milliers de fichiers, potentiellement des dizaines de milliers d'albums
  distincts — jamais construire toutes les vignettes d'un coup ; charger à la demande autour de la
  position actuelle du carrousel (fenêtre glissante), comme un lecteur vidéo ne décode pas tout le
  film d'avance.
- Réutiliser le throttle déjà établi (`MainFrame.refreshStats()`, 300ms) si Cover Flow doit refléter
  des identifications en direct pendant un taguage, plutôt que redessiner à chaque événement.

## Autres idées identifiées pendant cette session (concrètes, pas juste des envies)

### Tri des groupes par colonne dans la vue arborescence
Actuellement (v1, choix explicite de l'utilisateur) : ordre des groupes = ordre d'apparition, pas de
tri par clic de colonne — juste "Tout plier"/"Tout déplier". Le plan original envisageait un
`GroupSortRowSorter` (sibling de `SafeTableRowSorter`) pour trier l'ORDRE DES ALBUMS via clic
d'en-tête (Artiste, Année...), sans jamais réordonner les pistes à l'intérieur d'un groupe (toujours
par piste). Reporté pour garder le premier lot plus petit — bon candidat pour un prochain lot.

### Détection "musique classique" pas câblée partout
`LocalCorrector.detectClassical()` (remplit `TagInfo.isClassical`, la case à cocher "Musique
classique" du panneau Classique) n'est appelé que dans `TaggingWorker`/`BatchProcessor`/
`MatchDialog`/`App.java` — pas dans `InfoCompleterWorker` ni `AlbumCompletionWorker` (même
divergence "un pipeline reçoit le correctif, pas ses voisins" que ce projet croise régulièrement).
La nouvelle résolution Opus/Catalogue/Mouvement (`TagEnrichment.enrichClassicalWork`) contourne le
problème en se basant sur la présence d'un Work MB plutôt que sur `isClassical`, donc les DONNÉES
classiques se remplissent correctement même dans ces deux pipelines — mais la case à cocher
elle-même peut rester décochée à tort si aucun Work MB n'a été trouvé. Fix simple si ça dérange :
appeler `detectClassical()` aux mêmes deux endroits.

### Révision des vidéos non identifiées
La récupération vidéo automatique (`VideoRecoveryWorker`, déclenchée à chaque scan de dossier)
déplace les vidéos non reconnues dans un sous-dossier "Non identifié" à côté d'elles, sans aucune UI
dédiée pour les repasser en revue ensuite — l'utilisateur doit aller les regarder manuellement dans
l'explorateur de fichiers. Une petite fenêtre listant le contenu de tous les dossiers "Non identifié"
connus (avec, par exemple, un bouton "réessayer" ou "ouvrir le dossier") fermerait cette boucle.

### Translittération étendue au-delà de l'artiste
`TagEnrichment.translateArtist()` ne traite que le champ Artiste (non-Latin → alias latin via MB).
Avec la résolution classique de cette session (compositeur/chef d'orchestre/orchestre déjà remplis
via les relations MB), les mêmes noms non-Latins peuvent apparaître dans ces champs pour un
enregistrement classique — pas vérifié si c'est un problème réel en pratique (dépend de la
bibliothèque de l'utilisateur), à confirmer avant d'investir dessus.

## Ce qui a été explicitement écarté (pour mémoire, éviter de le re-proposer sans raison)

- **Tri-state (indéterminé) sur la case à cocher d'un en-tête de groupe** — simplifié en 2-états
  (tout/rien) pour la v1 de la vue arborescence, `Boolean.class` de Swing étant nativement 2-états.
- **Menu contextuel complet sur une ligne d'en-tête** — délibérément réduit (pas d'actions
  mono-fichier comme Renommer/Correspondance manuelle/Soumettre AcoustID) ; déplier le groupe donne
  accès au menu complet sur chaque piste, sans régression.
