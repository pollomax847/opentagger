# OpenTagger — Réflexion finale après lecture complète de l'application

Ce fichier prend du recul sur l'ensemble de la revue (16+ rounds, ~27 000
lignes, une trentaine de bugs corrigés). Pas de nouveau bug ici — la
question posée est différente : **maintenant que j'ai tout vu, qu'est-ce
que ça dit sur l'application dans son ensemble, et qu'est-ce qui mérite
d'être changé au-delà de la liste de correctifs ?**

---

## Ce que la trentaine de bugs a en commun

En reprenant chaque bug corrigé (voir `01-BUGS-CORRIGES.md`) et en cherchant
le point commun, trois familles ressortent très nettement — et à elles
trois, elles expliquent la grande majorité des bugs trouvés, pas juste
quelques-uns :

### 1. La fragmentation en pipelines parallèles est la cause racine la plus fréquente

`TaggingWorker`, `InfoCompleterWorker`, `BatchProcessor`, `AlbumCompletionWorker`,
`MatchDialog` font chacun "à peu près la même chose" (identifier/enrichir un
fichier), mais avec leur propre copie de la logique. Résultat, retrouvé
littéralement à chaque round de cette revue :
- SongRec/AudD absents du CLI (découverte majeure, `02-ARCHITECTURE-ET-PIPELINE.md`)
- Essentia câblé dans un seul pipeline sur cinq
- Empreinte AcoustID jamais calculée par "Passe complète"
- `resolveClassicalWork()` jamais mise en cache (seule requête MB dans ce cas)
- 3 implémentations indépendantes du parsing de titre depuis un nom de fichier

Ce n'est pas une coïncidence répétée cinq fois — c'est **une seule cause**,
observée cinq fois. Tant que cette architecture reste en l'état, chaque
nouvelle fonctionnalité a un risque structurel de n'être ajoutée qu'à un
sous-ensemble des pipelines, sans qu'aucun signal ne le révèle avant qu'un
utilisateur ne tombe dessus par hasard.

### 2. Les asymétries lecture/écriture sont le deuxième plus gros pourvoyeur de bugs

Le bug le plus grave de toute la revue (readTags() qui ne relisait pas 13
champs pourtant bien écrits par TagWriter) est l'exemple le plus flagrant,
mais le même schéma est réapparu ailleurs :
- `DetailPanel.collect()` en mode multi-sélection, deux fois de suite (champs
  classiques, puis paroles/URLs)
- Presque trouvé dans `SettingsDialog.load()/save()` (vérifié finalement
  cohérent, mais c'est le genre d'endroit où ce bug vit)

Le schéma est toujours le même : un champ est ajouté d'un côté (écriture,
ou affichage) sans qu'on pense à vérifier que l'autre côté (lecture,
collecte) a bien été mis à jour en miroir. Rien dans le code ne FORCE cette
symétrie — elle repose entièrement sur la vigilance de la personne qui
ajoute le champ.

### 3. "Toute la bibliothèque" vs "la vue actuelle" n'a pas de convention unique

Le bug systémique du round 13 (24 emplacements utilisant la vue filtrée au
lieu de `allEntries()`) vient d'un choix implicite refait à chaque fois
qu'une nouvelle action en masse est écrite : `tableModel.getRowCount()`
"marche" en apparence (compile, fonctionne en test rapide sans filtre
actif), et rien ne signale que c'est le mauvais choix tant qu'un filtre
n'est pas actif au moment du clic.

---

## Ce que ces trois causes ont en commun : l'absence d'un garde-fou structurel

Dans les trois cas, le bug n'est pas une erreur de logique compliquée — la
correction tient à chaque fois en une ligne ou deux. Le vrai problème, c'est
qu'**aucune de ces trois règles n'est vérifiable autrement qu'en la
relisant** : rien n'empêche d'écrire une future action qui itère sur
`getRowCount()`, rien ne signale qu'un nouveau champ TagInfo a été oublié
côté lecture, rien ne rappelle qu'un pipeline supplémentaire a été ajouté
sans les mêmes étapes que les autres.

C'est le fil conducteur de mes recommandations ci-dessous : plutôt que de
compter sur la vigilance (la mienne pendant cette revue, ou la tienne/celle
d'un futur contributeur ensuite), les corrections les plus utiles sont
celles qui rendent la classe entière de bug **impossible à réintroduire**,
pas seulement corrigée une fois.

---

## Recommandations, par ordre de valeur pour l'effort qu'elles demandent

### 1. (Déjà en suggestion n°1, renforcée) Unifier les pipelines d'enrichissement

Après avoir tout lu, cette suggestion n'est plus une hypothèse — c'est la
cause racine la plus documentée de cette revue. Concrètement, faire
converger `BatchProcessor`, `InfoCompleterWorker`, `AlbumCompletionWorker`
et `MatchDialog` sur les mêmes points d'entrée que `TaggingWorker`
(`TagEnrichment.identifyFromAudio()`, `TagEnrichment.saveEntry()`) rendrait
structurellement impossible la prochaine divergence — pas seulement les
cinq déjà trouvées.

### 2. Un test de "round-trip" sur les tags — le correctif le moins cher pour le bug le plus grave trouvé

Un seul test unitaire suffirait à rendre le bug `readTags()`/`TagWriter`
impossible à réintroduire silencieusement : pour chaque champ non vide d'un
`TagInfo`, écrire sur un fichier temporaire puis relire, et vérifier
l'égalité. Un tel test aurait détecté les 13 champs manquants en une
exécution, sans qu'aucune lecture manuelle ne soit nécessaire. C'est
probablement le meilleur ratio effort/valeur de toute cette liste : quelques
dizaines de lignes de test contre le bug le plus impactant de toute la
revue.

### 3. Un test d'intégration "aucune action ne doit ignorer un fichier filtré"

De façon symétrique : un test qui charge deux fichiers factices, applique un
filtre qui masque l'un des deux, déclenche chaque action en masse
(`startTagging`, `saveAll`, `setAllSelected`, etc.) et vérifie que le
fichier masqué est bien traité aurait détecté à lui seul les 24
emplacements du round 13, d'un coup, au lieu d'une relecture manuelle
méthode par méthode. Ce test devient aussi le filet de sécurité pour toute
future action en masse ajoutée au projet.

### 4. Une checklist de revue de code — un pense-bête, pas un outil

Étant donné que ce projet n'a pas de CI/build automatisé accessible (vérifié
dans cet environnement — Maven Central bloqué, mais rien n'indique qu'il y
en ait une ailleurs non plus vu l'absence de fichier `.github/workflows`),
une checklist courte à coller dans `CONTRIBUTING.md` ou en tête de
`TagEnrichment.java` type *"Nouveau champ TagInfo ? → vérifier readTags(),
TagWriter, DetailPanel.collect() (les deux modes), populateMulti()"* et
*"Nouvelle action en masse ? → allEntries(), pas getRowCount()"* coûte
une demi-heure et prévient la reproduction de ces deux classes de bugs par
la seule mémoire humaine, en attendant les tests ci-dessus.

### 5. Isoler la logique métier de `MainFrame.java`/`SettingsDialog.java` de leur UI

Les deux fichiers les plus gros du projet (4800 et 2258 lignes) mélangent
construction Swing (bruit, faible risque) et logique réelle (les 24 bugs du
round 13 vivaient tous dans `MainFrame.java`). Extraire au moins les
méthodes d'action en masse dans une classe séparée (`BulkActions` ou
similaire, recevant `FileTableModel` en paramètre) rendrait cette logique
testable indépendamment de Swing — condition préalable pour que la
suggestion n°3 soit réellement simple à écrire, et réduirait le "bruit"
dans lequel ce genre de bug se cache facilement (comme trouvé ici,
uniquement à force de tout relire une méthode à la fois).

### 6. Centraliser les clés de configuration en constantes

`Config.java`/`SettingsDialog.java` utilisent des chaînes littérales
partout (`"tags.preserve_compilation"`, etc.) — vérifiées cohérentes une
par une dans cette revue, mais rien ne garantit qu'elles le restent : une
faute de frappe dans une seule des deux occurrences ne casse pas la
compilation, elle crée silencieusement un réglage fantôme. Remplacer par
des constantes `public static final String KEY_X = "..."` partagées entre
lecture et écriture élimine cette classe de risque une fois pour toutes,
avec un coût de refactor modeste vu que la correspondance est déjà connue
et documentée dans cette revue.

---

## Ce qui n'a PAS besoin de changer

Toujours par souci d'équilibre : la qualité générale du code — gestion des
threads, prudence sur les accès disque, discipline de commentaires
expliquant CHAQUE choix contre-intuitif avec le bug réel qui l'a motivé —
est largement au-dessus de la moyenne de ce que je vois habituellement. Les
recommandations ci-dessus visent des angles morts précis, pas une refonte
générale : ce projet gagnerait à consolider ce qu'il fait déjà bien
(discipline de commentaires, prudence sur la concurrence) en l'appliquant
aussi aux trois familles de bugs identifiées ci-dessus, pas à tout
reconstruire.
