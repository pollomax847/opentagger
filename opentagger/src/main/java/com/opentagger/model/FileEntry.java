package com.opentagger.model;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class FileEntry {

    public enum Status {
        PENDING, PROCESSING,
        // Identifié (tags proposés en mémoire dans `result`) mais PAS ENCORE écrit sur le disque —
        // façon Picard (scan/lookup ne touche jamais le fichier, seul un "Save" explicite écrit).
        // Se place AVANT TAGGED, pas à sa place : tout code qui vérifie `status == TAGGED` pour
        // décider qu'un fichier est éligible à une action (renommer, exporter, soumettre à
        // AcoustID, synchroniser ListenBrainz...) continue de fonctionner sans changement, un
        // fichier IDENTIFIED n'étant structurellement pas différent d'un PENDING pour ces
        // consommateurs — c'est exactement le comportement voulu (ces actions ont besoin du
        // fichier réellement sur le disque). Voir ui/SaveWorker.java pour ce qui fait passer
        // IDENTIFIED → TAGGED.
        IDENTIFIED,
        TAGGED, SKIPPED, ERROR
    }

    public final File   file;
    public TagInfo      current;      // tags lus dans le fichier avant traitement
    public TagInfo      result;       // tags proposés après identification API
    public Status       status   = Status.PENDING;
    public String       message  = "";
    public boolean      selected = true;

    /** Chemin actuel du fichier (peut changer après renommage). */
    public Path currentPath;

    /** Candidats non retenus par le batch (score < seuil) — pour sélection manuelle. */
    public List<TagInfo> candidates;

    /** Suggestions d'amélioration générées après l'analyse (pochette absente, MBID manquant…). */
    public List<String> suggestions;

    /** Forcer la ré-identification même si le cache ou les tags MB existants sont valides. */
    public boolean forceReidentify = false;

    /** Ignorer toute la cascade d'identification normale et n'essayer QUE la devinette Bandcamp
     *  (voir TaggingWorker.tryBandcampGuess()) — déclenchement manuel dédié (menu "Retraitement"),
     *  demande utilisateur 2026-09-19 après avoir constaté que l'étape automatique (6a, tout en bas
     *  de la cascade) est en pratique un pari à faible rendement (4 succès / 919 essais mesurés en
     *  prod) : plutôt que de la retirer, elle devient une action à la demande façon MetaGrater de
     *  SongKong, au lieu de tourner sur tout le lot à chaque taguage. */
    public boolean bandcampOnly = false;

    /** Fichier déjà rangé remis en ré-identification par une demande explicite (voir
     *  MainFrame.applyReidentifyRequest()) : s'il échoue (SKIPPED/ERROR/durée incohérente), il
     *  RESTE à sa place avec ses tags actuels — jamais envoyé vers Sans_correspondance ni vers le
     *  dossier « durée incohérente », ce qui arracherait des pistes déjà classées à leur album. */
    public boolean keepInPlaceIfSkipped = false;

    /** Ajouté par le parcours d'un scan de dossier (phase 1) mais tags pas encore lus (phase 2) :
     *  statut encore INCONNU — PENDING par défaut même pour un fichier déjà tagué. Exclu de tout lot
     *  « Tout tagger » tant que vrai, puis servi au lot en cours dès sa lecture (voir
     *  MainFrame.loadDirectory() et TaggingWorker.enqueueLate()). Avant ce drapeau (2026-09-25), un
     *  lot lancé pendant un scan embarquait ces fichiers « PENDING » et ré-identifiait des fichiers
     *  déjà tagués (301 « cache hit » sur 3 389 fichiers traités, mesuré en direct). */
    public volatile boolean awaitingScan = false;

    /** Fichier sans aucun son (0 octet, ou < 64 Ko sans flux audio) — jamais tagué, voir
     *  ui.ShellCleaner. */
    public boolean emptyShell = false;

    /** SKIPPED spécifiquement parce que la durée du fichier ne correspond pas à celle déclarée par
     *  MusicBrainz pour l'enregistrement identifié (voir {@link #isDurationMismatch}) — pas
     *  une simple non-identification. Distingue ce cas pour le déplacement optionnel dédié (voir
     *  Config.durationMismatchMoveEnabled()), séparé du déplacement générique SKIPPED/ERROR. */
    public boolean durationMismatch = false;

    /** Catégorie de {@link #message} pour un statut SKIPPED/ERROR — voir {@link SkipReason}. Null
     *  pour tout fichier traité avant l'introduction de ce champ (session antérieure), ou tant que
     *  le statut n'est ni SKIPPED ni ERROR. Alimente le rapport "Non identifiés" par cause. */
    public SkipReason skipReason = null;

    /** Écart significatif entre durée réelle du fichier et durée MusicBrainz de l'enregistrement
     *  identifié. 0 = non renseigné (MB ou fichier), jamais considéré comme un écart. Seule source
     *  de vérité pour ce calcul — utilisé à la fois avant taguage (TaggingWorker, bloque en SKIPPED)
     *  et après (TagEnrichment.saveEntry(), déplace pour vérification sans bloquer, seul filet de
     *  sécurité pour les chemins qui contournent le premier — matching manuel via MatchDialog
     *  excepté : durationSec y reste à 0 par absence de copie depuis entry.current, donc jamais
     *  faussement signalé pour un choix humain explicite).
     *  ATTENTION : fileSec<=0 seul ne doit JAMAIS être traité comme "forcément vide/corrompu" ici —
     *  tenté une fois (2026-07-18), reverté en urgence : entry.current.durationSec peut encore
     *  valoir 0 simplement parce que la phase 2 du scan (lecture des tags, voir MainFrame.
     *  readTags()) n'est pas encore passée sur ce fichier au moment où le taguage (qui peut
     *  démarrer avant la fin du scan) l'examine — pas parce que le fichier est réellement vide.
     *  Constaté en direct : des centaines de faux positifs sur des fichiers dont la Durée
     *  s'affichait correctement (4:19, 5:07...) une fois le scan rattrapé. La vraie détection des
     *  fichiers 0 octet reste le scan lui-même (voir MainFrame.readTags()/AudioDuration fallback),
     *  pas cette comparaison.
     *  <p>
     *  Seuil ASYMÉTRIQUE (2026-08-16, retour utilisateur confirmé sur données réelles) : un fichier
     *  plus COURT que la durée MB (20s ET 20% relatif) reste un signal fort de mauvais match (extrait,
     *  radio edit collé à tort, fichier tronqué) — seuil inchangé, strict. Un fichier plus LONG (30s
     *  ET 50% relatif, nettement plus tolérant) est très souvent légitime : live/bootleg, DJ set,
     *  version étendue, bonus — repéré en direct sur deux vrais bootlegs "blink-182 ... All The
     *  Small Things" (212s/208s fichier vs 171s MB, correctement identifiés mais rejetés à tort par
     *  l'ancien seuil symétrique). Un Math.abs() unique traitait les deux directions identiquement,
     *  alors que ce sont deux signaux de nature différente. */
    /** Version STRICTE et symétrique de {@link #isDurationMismatch} (écart de plus de 20 s ET de
     *  20 % de la durée MB, dans les DEUX sens), pour les identifications qui ne reposent pas sur
     *  l'audio (cohérence de groupe : numéro de piste + titre) — là, un fichier nettement plus long
     *  n'est pas un bootleg/live plausible mais bien probablement un autre morceau. Cas réel
     *  (2026-10-02) : "Piggy Bank" (50 Cent, ~4:06 sur MB) épinglé sur un fichier de 5:51 qui
     *  n'était pas ce morceau — +43 % passait sous le seuil de 50 % ci-dessus. */
    public static boolean isStrictDurationMismatch(int fileSec, int mbSec) {
        if (fileSec <= 0 || mbSec <= 0) return false;
        int diff = Math.abs(fileSec - mbSec);
        return diff > 20 && diff > mbSec * 0.20;
    }

    /** Fichier COURT et manifestement tronqué : moins de {@code maxShortSec}, alors que MusicBrainz annonce au moins
     *  {@code minGapSec} de plus ET au moins le double (ex. 0:40 vs 3:34). Volontairement plus étroit que
     *  {@link #isDurationMismatch} — c'est le seul cas que l'utilisateur juge sûr à écarter automatiquement (essai,
     *  2026-09-25). Durées inconnues (≤ 0) : jamais vrai, même exemption que isDurationMismatch. */
    public static boolean isShortTruncated(int fileSec, int mbSec, int maxShortSec, int minGapSec) {
        if (fileSec <= 0 || mbSec <= 0) return false;
        return fileSec < maxShortSec && mbSec - fileSec >= minGapSec && mbSec >= 2 * fileSec;
    }

    public static boolean isDurationMismatch(int fileSec, int mbSec) {
        if (fileSec <= 0 || mbSec <= 0) return false;
        int diff = fileSec - mbSec;
        if (diff < 0) {
            int shortfall = -diff;
            return shortfall > 20 && shortfall > mbSec * 0.20;
        }
        return diff > 30 && diff > mbSec * 0.50;
    }

    /**
     * Racine du dossier scanné — le renommage par masque est relatif à cette racine.
     * Ex : racine=/musique, masque={artist}/{album}/{track} - {title}
     *   → /musique/Artist/Album/01 - Title.mp3
     */
    public Path scanRoot;

    /** Taille du fichier en octets, lue UNE fois à la création (0 si illisible) — sert au total
     *  « taille de la bibliothèque » de la barre d'état. Volontairement pas relue ensuite : le total
     *  est rafraîchi toutes les quelques secondes, relire le disque (surtout un NAS) à chaque fois
     *  serait inacceptable. */
    public final long sizeBytes;

    public FileEntry(File file, TagInfo current) {
        this.file        = file;
        this.currentPath = file.toPath();
        this.current     = current != null ? current : new TagInfo();
        this.sizeBytes   = Math.max(0, file.length());
    }

    /** Nom court du fichier actuel (après renommage éventuel). */
    public String filename() {
        return currentPath != null ? currentPath.getFileName().toString() : file.getName();
    }

    /** Retourne les tags actifs : result si disponible, sinon current. */
    public TagInfo activeTags() { return result != null ? result : current; }
}
