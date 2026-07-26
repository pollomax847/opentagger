# OpenTagger — Suggestions d'amélioration (au-delà des bugs)

7 pistes classées par impact pour dépasser Picard, OneTagger et Jaikoz sur
leurs points forts respectifs, sans hériter de leurs angles morts. Ce ne
sont **pas des bugs** mais des évolutions possibles — voir
`01-BUGS-CORRIGES.md` pour ce qui est déjà corrigé et
`02-ARCHITECTURE-ET-PIPELINE.md` pour le contexte qui motive la suggestion
n°1 en particulier.

---

## 💡 Suggestions d'amélioration — pour dépasser Picard, OneTagger ET Jaikoz

Jaikoz (le modèle historique dont OpenTagger reprend les 36 masques de
renommage et l'esprit "autocorrecteur") est proprio/payant, à l'interface
datée, et cité en avis utilisateurs comme "overcomplicated, pain to keep
running" — mais possède un vrai point fort documenté : un moteur de
correction qui **compare les tags entre fichiers d'un même groupe et aligne
les valeurs sur la plus fréquente** (corrige les fautes de frappe
automatiquement). Voici des pistes concrètes, classées par impact, pour
qu'OpenTagger dépasse les trois sur leurs points forts respectifs sans
hériter de leurs angles morts documentés ci-dessus.

### 1. Unifier les pipelines CLI et GUI (le correctif le plus important)

Le bug le plus grave trouvé dans cette revue (absence de SongRec/AudD en
CLI) n'est qu'un symptôme : la vraie cause est que `BatchProcessor`
réimplémente à la main sa propre version de `findTags()`/écriture/renommage
au lieu d'appeler `TagEnrichment.identifyFromAudio()` (la cascade déjà
extraite et partagée) et `TagEnrichment.saveEntry()` (déjà utilisé par
`SaveWorker`). Faire converger les deux pipelines sur ces mêmes points
d'entrée réglerait d'un coup SongRec/AudD/ReplayGain/OAuth/AcoustID
submit/LRC/cover-to-file en CLI, et surtout **rendrait cette classe de bug
structurellement impossible à l'avenir** — ni Picard ni OneTagger n'ont ce
risque, parce qu'ils n'ont qu'un seul pipeline. C'est l'amélioration qui
rapprocherait le plus OpenTagger de la fiabilité "un seul moteur, partout"
de ses deux concurrents open-source.

### 2. Compléter le portage Picard dans `ReleaseMatcher` (tier "identifiers")

`ReleaseMatcher.java` documente lui-même n'avoir porté que le tier
"preferences" (pays/format/type) du modèle de Picard, pas le tier
"identifiers" (barcode/catno/ISRC) que Picard vient justement d'améliorer
(PICARD-2867/3284/3291, 2026). Ajouter ce tier — comparer le barcode/ISRC du
fichier à ceux des releases candidates avant de trancher sur les
préférences utilisateur — rapprocherait OpenTagger du modèle Picard complet
et améliorerait la précision de sélection de release sur les cas ambigus
(même album, éditions multiples). Bénéfice concret : moins de compilations
radio confondues avec l'album studio, moins d'éditions deluxe/remaster mal
choisies.

### 3. Une passe de cohérence inter-fichiers — dépasser le point fort de Jaikoz

Le "autocorrecteur par comparaison" de Jaikoz (aligner les valeurs sur la
plus fréquente au sein d'un groupe) n'existe qu'à moitié dans OpenTagger :
`AlbumClusterWorker.majorityGenre()` le fait uniquement pour le genre. Une
passe générique — appliquée à artiste/albumArtist/année à l'intérieur d'un
groupe déjà identifié par le même `releaseMbid`, en ne corrigeant QUE les
variantes clairement fautives (accent manquant, casse différente, espace en
trop — pas des artistes réellement différents) — couvrirait plus de champs
que Jaikoz avec, en plus, un vrai ancrage MusicBrainz (Jaikoz corrige par
simple vote de fréquence, sans vérifier la donnée faisant autorité).

### 4. Fusion de genres sans doublons (au lieu de l'évitement actuel)

OneTagger a un bug ouvert de doublons quand il fusionne les genres de
plusieurs sources (issue #220). OpenTagger évite ce bug en ne fusionnant
JAMAIS (`enrichGenre` ne remplit que si vide) — ce qui règle le bug mais
sacrifie la fonctionnalité : un utilisateur qui veut cumuler les genres
Discogs + Last.fm + MusicBrainz n'y a pas droit. Une vraie fusion
dédupliquée (normaliser casse/accents avant de comparer, réutiliser
`GenreFilter` pour le tri par pertinence) donnerait à OpenTagger la
fonctionnalité qu'OneTagger promet, sans son bug.

### 5. Durcir le parsing de noms de fichiers contre les cas pathologiques

Picard a corrigé de nombreux crashs sur le parsing piste/titre depuis le nom
de fichier (fichier nommé juste `1.opus`, "UB40" pris pour un numéro de
piste, point résiduel après extraction du numéro). Les regex de
`LocalCorrector` (`FILE_TRACK_ARTIST_TITLE`/`FILE_ARTIST_TITLE`) n'ont
probablement pas été testées contre des cas aussi tordus. Un petit jeu de
tests unitaires ciblant spécifiquement ces cas connus de Picard (nom
purement numérique, nom sans séparateur, numéro suivi de lettres) éviterait
à OpenTagger de découvrir ces mêmes bugs plus tard, en production.

### 6. Nettoyage défensif avant renommage (angle mort partagé, pas confirmé)

OneTagger plante purement sur un octet nul dans un tag au moment de
renommer (issue #157 — `data provided contains a nul byte`). Je n'ai pas
trouvé de nettoyage explicite de ce type dans `FileRenamer`/`TaggerScript`
côté OpenTagger — probablement pas un problème en pratique (un octet nul
dans un tag texte est rare), mais une ligne de nettoyage défensive
(supprimer les caractères de contrôle avant de construire un chemin) coûte
peu et fermerait la porte à ce type de crash si un script utilisateur ou un
tag corrompu en produisait un.

### 7. Diagnostic de configuration au démarrage

Un reproche récurrent sur Jaikoz est la difficulté à le garder fonctionnel
("pain to keep running", licence à renouveler). OpenTagger est déjà
meilleur sur ce point (gratuit, open-source), mais pourrait enfoncer le
clou avec un petit panneau "Diagnostic" (déjà quelque chose de similaire
existe peut-être dans Réglages → Audio, pas vérifié) qui vérifie en un clic
ffmpeg/fpcalc/SongRec/clés API et affiche clairement ce qui manque — un
avantage marketing autant que technique face à un concurrent payant réputé
pénible à maintenir en état de marche.

---





## Notes pour la suite

- Les trois correctifs (`LocalCorrector.java`, `BpmDetector.java`,
  `CaaClient.java`) sont appliqués dans le clone local
  (`/home/claude/opentagger` côté environnement de revue, branche
  `feature/opentagger-v1`) mais **pas poussés sur GitHub** — pas
  d'identifiants git configurés pour `pollomax847`. À appliquer/commiter
  manuellement (patch disponible sur demande).
- Le plus efficace pour la suite : un bug **observé en utilisant l'appli**
  (mauvais tag écrit, crash, comportement inattendu) permet de cibler
  directement le bon fichier plutôt que de continuer une lecture à l'aveugle
  sur ~15 000 lignes restantes.
