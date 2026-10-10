# Conformité avant la v1.0.0

Règle : **la 1.0.0 ne part que si chaque ligne ci-dessous est « OK »**. Une ligne n'est OK que si une preuve est citée
(test automatique, ou essai réel daté). « Écrit » ou « semble marcher » ne compte pas.

Légende : ✅ preuve citée · 🟡 partiel (voir note) · ⬜ aucune preuve · 👤 nécessite l'utilisateur / une machine précise.

Dernier état connu : **282 tests automatiques, 0 échec** (+ vérifications réelles listées ci-dessous) (build du 2026-10-10). Les tests ne touchent ni
la bibliothèque ni `cache.db` (voir `feedback-isoler-user-home-tests`).

## 1. Identification

| Fonction | Preuve | État | Reste à faire |
|---|---|---|---|
| AcoustID / fpcalc | journal réel du 2026-10-10 (centaines de `score=100, AcoustID`) ; `AudioTagAuditTest` | ✅ | — |
| MusicBrainz (choix de release, album d'origine) | `AlbumMatcherTest`, `PinnedReleaseRetargetTest`, `ReleaseProofTest` | ✅ | — |
| SongRec sous Windows | essai réel 2026-10-10 : sur 8 fichiers « download » **sains** (décodage vérifié), 2 reconnus, 6 inconnus de Shazam ; `SongRecInstallerTest` | 🟡 | échantillon trop petit (8) pour un taux fiable : la grande majorité des « download » échantillonnés étaient absents ou illisibles ; héberger l'archive (👤 accord de publication) |
| SongRec sous Linux | aucun essai | 👤 | machine Linux |
| Budget d'appels Shazam (80 / 10 min) | `SongRecBudgetTest` (logique) | 🟡 | seuil réel de Shazam inconnu : à mesurer sur un gros lot |
| Fichier au son illisible → « Audio illisible » | `DecodeProblemTest` (octets aléatoires signalés, mp3 sain et fichier absent jamais) + essai réel sur un m4a abîmé | 🟡 | la branche dans `TaggingWorker` elle-même n'a pas de test |
| Titres génériques (« Track 3 », « 2U », « 江南 ») | `FallbackIdentityTest` (8 tests) | ✅ | — |
| Nom du fichier contredit l'identification | `ContradictsInputTest`, `SongRecConfirmedTest` | 🟡 | cas du journal : « We Love You Lord Medley » → « What Is Love » (score 100) à examiner |
| Durée incohérente | `StrictDurationMismatchTest` | ✅ | — |
| Encodage cassé (`Ã©`) | `EncodingFixerTest` | ✅ | — |
| Détection de podcast | `PodcastDetectorTest` | ✅ | — |

## 2. Tags (lecture / écriture)

| Fonction | Preuve | État | Reste à faire |
|---|---|---|---|
| Aller-retour de tous les champs (MP3, M4A, FLAC, Opus, OGG) | `TagRoundTripTest` (ffmpeg requis) | ✅ | — |
| Sous-titre de disque Opus | `TagRoundTripTest` (corrigé 2026-10-10) | ✅ | — |
| Commentaires, play counts | `TagReaderCommentTest`, `PlayCountsTest` | ✅ | — |
| Écriture M4A (AtomicParsley / réparation faststart) | `TagRoundTripTest` écrit puis relit un vrai M4A au format iTunes (ipod) | ✅ | — |
| Sauvegarde sur gros lot (3 500+ fichiers) | retours utilisateur + `SaveBacklogTest`, `DiskIoThrottleWindowsTest` | 🟡 | mesurer un lot complet après les correctifs de débit |

## 3. Bibliothèque

| Fonction | Preuve | État | Reste à faire |
|---|---|---|---|
| Renommage par masques, chemins Windows | `FileRenamerEmptyDirTest`, `WindowsFileNameTest`, `RenameSameSongTest` | ✅ | — |
| Aucun doublon créé par l'application | `MoveToFolderNoDuplicateTest`, `AutoDedupTest`, `AutoDedupPlacementTest`, `DuplicateReserveTest` | ✅ | — |
| Chemins insensibles à la casse (Windows) / sensibles (Linux) | `PathIdentityTest`, `PathIdentityCollectionsTest` | 🟡 | cas Linux sur machine réelle 👤 |
| Compilations : proposer seulement les cas plausibles | `CompilationFilterTest` ; rejeu sur la copie du cache réel (28 433 fichiers) : 20 716 candidats avec l'ancienne règle, 9 811 avec la règle d'albums, dont 2 311 à modifier ; sur ceux-là 1 973 sont des « Greatest Hits » d'un seul artiste (désormais exclus, seul « Various Artists » compte), 0 est confirmé VA, 338 non vérifiables sans réseau | ✅ | rejouer en vrai : le nombre doit être de l'ordre de quelques centaines au plus |
| Compilation = dossier de la compilation | `FolderReleaseTest`, `AlbumTagGroupingTest`, `AlbumGroupingTest` | ✅ | — |
| Pochettes (complétion automatique) | `ArtworkCompletionWorkerTest`, `ArtistPhotoFolderTest` | ✅ | — |
| Corbeille : refus si audio dedans | `TrashHelperGuardTest` | ✅ | — |
| Détection de doublons (4 niveaux) | `DuplicateDetectorTest` (7 tests : MBID, AcoustID, empreinte, artiste+titre, exclusivité, titre seul refusé) | ✅ | — |
| Transcodage sûr (jamais de perte de source) | `TranscodeSafetyTest` | ✅ | — |
| Surveillance de dossier | `FolderWatcherStableTest` | ✅ | — |
| Table : lignes fantômes, sélection par lots | `FileTableModelGhostRowsTest`, `RowSelectionTest` | ✅ | — |
| Concurrence des tâches de fond | `WorkerHubTest` | ✅ | — |

## 4. CD

| Fonction | Preuve | État | Reste à faire |
|---|---|---|---|
| CD audio : table des pistes, identifiant de disque | `CdDiscIdTest`, `CdRipperNativeTest`, `CdKnownTracksTest` | ✅ | — |
| CD audio : choix de la release, « déjà présent » | `CdRankReleasesTest`, `CdAlreadyInLibraryTest`, `CdDescribesDiscTest` | ✅ | — |
| CD inconnu : identification piste par piste | essai réel (3 pistes retrouvées) ; `CdAudioIdentifierTest` | ✅ | — |
| GnuDB (CDDB) | `GnuDbClientTest` + essai réel du serveur | ✅ | — |
| CD de données (copie sans écrasement) | `DataDiscCopierTest` + essai réel | 🟡 | deux fichiers disparus lors d'une copie : cause inconnue 👤 |
| Verrou du lecteur | `CdDriveLockTest` | ✅ | — |
| Import de CD sous Linux (Python, `eject`) | aucun essai | 👤 | machine Linux |

## 5. Interface et réglages

| Fonction | Preuve | État | Reste à faire |
|---|---|---|---|
| Niveaux d'automatisation | `AutomationModeTest` | ✅ | — |
| Réglages cohérents, compte MusicBrainz | `SettingsConsistencyTest`, `SettingsMbAccountTest` | ✅ | — |
| Vider cache / base | `CacheClearTest` | ✅ | — |
| Préférences : bouton « Installer SongRec… » | dialogue construit en vrai (2026-10-10) : bouton et état « installé : … » présents | ✅ | le clic de téléchargement attend l'archive hébergée |
| Parcours complet de chaque menu / dialogue | aucun test automatisé de l'interface | ⬜ | passe manuelle (liste à établir) |
| Langue anglaise (`messages_en.json`) | 819 textes traduits dont 116 info-bulles concaténées (placeholders, sauts de ligne et balises HTML vérifiés un à un par script) ; JSON relu par Jackson | ✅ | relecture par un anglophone souhaitable |

## 6. Distribution

| Fonction | Preuve | État | Reste à faire |
|---|---|---|---|
| Installeur Windows qui **remplace** une installation existante | non testé : l'installeur réinstallerait par-dessus ta vraie installation (même produit) ; à faire sur la version candidate 1.0.0, pas avant | ⬜ | construire la 1.0.0 puis l'installer sur la 0.9.39 👤 |
| Paquet apt / `install-opentagger.sh` | aucun essai depuis la 0.9.39 | 👤 | machine Linux |
| Mise à jour depuis l'application vers 1.0.0 | aucun | ⬜ | après publication |
| Numéro de version `1.0.0` (pom, installeur, README, notes) | non fait | ⬜ | seulement quand tout le reste est OK |
| Fichiers personnels hors du dépôt (`opentagger-history.json`, rapport de session) | retirés du suivi git et ajoutés à `.gitignore` (les fichiers restent sur le disque) | ✅ | encore présents dans l'historique : voir ligne suivante |
| Historique git : e-mail personnel, secret OAuth | non nettoyé | ⬜ | opération destructive : accord explicite requis |

## Décision

La v1.0.0 est prête quand : toutes les lignes ⬜ et 🟡 sont passées à ✅ ou explicitement écartées par le propriétaire du
projet, et les lignes 👤 ont été validées sur la machine concernée.
