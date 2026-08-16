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
    /** "Track ID" de l'entrée iTunes correspondante, si ce fichier a été résolu lors d'un import
     *  XML iTunes (voir ITunesImportDialog) — {@code null} tant qu'aucun import ne l'a établi.
     *  Permet de repousser un changement (renommage, note) vers CETTE entrée précise du XML iTunes
     *  (voir ITunesXmlSyncQueue/ITunesXmlWriter) sans avoir à re-résoudre le chemin à chaque fois. */
    public Integer itunesTrackId = null;

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

    public FileEntry(File file, TagInfo current) {
        this.file        = file;
        this.currentPath = file.toPath();
        this.current     = current != null ? current : new TagInfo();
    }

    /** Nom court du fichier actuel (après renommage éventuel). */
    public String filename() {
        return currentPath != null ? currentPath.getFileName().toString() : file.getName();
    }

    /** Retourne les tags actifs : result si disponible, sinon current. */
    public TagInfo activeTags() { return result != null ? result : current; }
}
