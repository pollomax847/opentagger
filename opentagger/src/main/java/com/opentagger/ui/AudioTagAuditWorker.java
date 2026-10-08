package com.opentagger.ui;

import com.opentagger.AcoustIdClient;
import com.opentagger.AudioAuditStore;
import com.opentagger.AudioTagAudit;
import com.opentagger.AudioTagAudit.Candidate;
import com.opentagger.AudioTagAudit.Comparison;
import com.opentagger.AudioTagAudit.Verdict;
import com.opentagger.Config;
import com.opentagger.Fingerprinter;
import com.opentagger.I18n;
import com.opentagger.SongRecClient;
import com.opentagger.TagReader;
import com.opentagger.model.TagInfo;

import javax.swing.SwingWorker;
import java.io.File;
import java.util.List;

/**
 * Audit "l'audio correspond-il vraiment aux tags ?" sur des fichiers DÉJÀ tagués — voir AudioTagAudit pour
 * le pourquoi (mesure du 2026-09-20 : ~3 % des fichiers "confirmés" contredisent leur audio) et AudioAuditStore
 * pour où vont les verdicts.
 *
 * <p>LECTURE SEULE : ne modifie ni fichier ni tag ni table principale ; il n'écrit que dans audio_audit.db.
 * Corriger un suspect reste une décision de l'utilisateur (fenêtre AudioTagAuditPanel).
 *
 * <p>Par fichier : lecture des tags actuels → empreinte fpcalc → un lookup AcoustID léger (sans MusicBrainz)
 * → comparaison. Second avis Shazam (SongRec) UNIQUEMENT pour les cas graves (autre titre / autre morceau),
 * environ 3 % des fichiers. Séquentiel et volontairement lent (≥ {@code audit.audio_tags.interval_ms} entre
 * deux lookups, 1000 ms par défaut) : la limite AcoustID (3 req/s, voir AcoustIdClient.throttle()) est
 * partagée avec le pipeline de taguage qui peut tourner en même temps, et les disques sont partagés avec
 * Plex/Navidrome/etc.
 */
public class AudioTagAuditWorker extends SwingWorker<Void, Void> {

    /** État instantané, lu par la fenêtre de revue (qui interroge, sans écouteur : le worker peut tourner
     *  fenêtre fermée). Un seul audit à la fois (WorkerHub.TaskKind.AUDIO_AUDIT), donc un état statique. */
    public record Snapshot(boolean running, int total, int done, int cached, int ok, int suspects,
                           int unverifiable, int errors, String current, String notice) {}

    private static volatile Snapshot LAST = new Snapshot(false, 0, 0, 0, 0, 0, 0, 0, "", "");
    public static Snapshot snapshot() { return LAST; }

    // Premier vrai audit (2026-09-20, 520 fichiers) : AcoustID a répondu "request timed out" ~90 s d'affilée au
    // fichier 140 puis est revenu normal (0,3 s) — l'ancien "5 échecs consécutifs = abandon" a tué un audit de
    // ~35 min pour une micro-panne. Un audit peut durer des jours : après 5 échecs de suite il PATIENTE (pauses
    // croissantes, ~37 min au total) et ne renonce qu'ensuite. Non final : réglé à quelques ms par le test.
    private static final int NETWORK_FAILURES_BEFORE_PAUSE = 5;
    static long[] PAUSE_MS = {60_000, 120_000, 300_000, 600_000, 600_000, 600_000};

    private final List<File> files;
    private final boolean    recheck;
    private volatile boolean stop = false;

    private int done, cached, ok, suspects, unverifiable, errors;
    private long lastLookupMs = 0;

    public AudioTagAuditWorker(List<File> files, boolean recheck) {
        this.files   = files;
        this.recheck = recheck;
    }

    /** Annulation propre (WorkerHub.submit cancelAction) : drapeau + interruption des attentes réseau/pauses. */
    public void requestStop() {
        stop = true;
        cancel(true);
    }

    @Override
    protected Void doInBackground() {
        final int total = files.size();
        if (Config.get().acoustidKey().isBlank()) {
            abort(I18n.t("Audit audio ↔ tags impossible : aucune clé AcoustID (Préférences → APIs)."));
            return null;
        }
        if (com.opentagger.FpcalcInstaller.resolve() == null) {
            abort(I18n.t("Audit audio ↔ tags impossible : fpcalc introuvable (Préférences → Audio)."));
            return null;
        }
        final long intervalMs = Math.max(400, Config.get().num("audit.audio_tags.interval_ms", 1000));
        final AcoustIdClient acoustId = new AcoustIdClient();
        final SongRecClient  songRec  = new SongRecClient();
        final boolean songRecOk = SongRecClient.isAvailable();

        update(true, total, "");
        log(I18n.t("Audit audio ↔ tags : %d fichier(s)%s.", total,
                recheck ? I18n.t(" (re-vérification forcée)") : ""));

        int consecutiveFailures = 0, pausesTaken = 0;
        try (AudioAuditStore store = AudioAuditStore.open()) {
            for (File f : files) {
                if (stop || isCancelled() || Thread.currentThread().isInterrupted()) break;
                update(true, total, f.getName());
                try {
                    long[] st = AudioAuditStore.statOf(f);          // 1 seul appel système (voir statOf)
                    if (st == null) { done++; continue; }
                    long mtime = st[0], size = st[1];
                    AudioAuditStore.Row known = store.get(f.getAbsolutePath());
                    // Un verdict ERROR (fpcalc n'a pas pu lire le fichier — décodage OU délai dépassé, le message est
                    // le même) n'est jamais considéré « déjà audité » : retenté à chaque audit, ces échecs sont rapides.
                    if (!recheck && AudioAuditStore.isCurrent(known, mtime, size) && !"ERROR".equals(known.verdict())) {
                        cached++; done++;
                        continue;
                    }
                    AudioAuditStore.Row row = auditOne(f, mtime, size, acoustId, songRec, songRecOk, intervalMs);
                    store.put(row);
                    consecutiveFailures = 0;
                    pausesTaken = 0;                 // une panne guérie remet le compteur de pauses à zéro
                    notice = "";
                    count(row);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (NetworkFailure nf) {
                    // Transitoire (réseau/AcoustID) : RIEN n'est enregistré, le fichier sera revu au prochain
                    // audit — un verdict "erreur" mis en cache ici ferait ignorer des fichiers sains.
                    errors++;
                    log("  ✗ " + f.getName() + " : " + nf.getMessage());
                    if (++consecutiveFailures >= NETWORK_FAILURES_BEFORE_PAUSE) {
                        if (pausesTaken >= PAUSE_MS.length) {
                            notice = I18n.t("Audit interrompu : AcoustID injoignable malgré %d pause(s) — relancez-le plus tard "
                                    + "(les fichiers déjà audités seront ignorés).", pausesTaken);
                            log(notice);
                            break;
                        }
                        long pause = PAUSE_MS[pausesTaken++];
                        notice = I18n.t("AcoustID injoignable : pause de %d s avant de reprendre (%d/%d)",
                                pause / 1000, pausesTaken, PAUSE_MS.length);
                        log(notice);
                        update(true, total, f.getName());
                        try {
                            Thread.sleep(pause);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // Une seule nouvelle tentative avant la pause suivante : si AcoustID n'est pas revenu, inutile
                        // de brûler 5 requêtes de plus (chacune attend son délai d'expiration complet).
                        consecutiveFailures = NETWORK_FAILURES_BEFORE_PAUSE - 1;
                    }
                } catch (Exception e) {
                    errors++;
                    log("  ✗ " + f.getName() + " : " + e.getMessage());
                }
                done++;
                if (done % 50 == 0) {
                    log(I18n.t("  audit : %d/%d — %d suspect(s), %d invérifiable(s), %d ignoré(s) car déjà audités",
                            done, total, suspects, unverifiable, cached));
                }
                update(true, total, f.getName());
            }
        } catch (Exception e) {
            notice = I18n.t("Audit interrompu : base d'audit inaccessible (%s)", e.getMessage());
            log(notice);
        } finally {
            update(false, total, "");
            log(I18n.t("Audit terminé — %d/%d traité(s), %d OK, %d suspect(s), %d invérifiable(s), %d erreur(s), "
                    + "%d déjà audité(s).", done, total, ok, suspects, unverifiable, errors, cached));
        }
        return null;
    }

    private AudioAuditStore.Row auditOne(File f, long mtime, long size, AcoustIdClient acoustId, SongRecClient songRec,
                                         boolean songRecOk, long intervalMs) throws Exception {
        String path = f.getAbsolutePath();
        TagInfo tags = TagReader.read(f);
        if (tags.artist.isBlank() && tags.title.isBlank()) {
            return row(path, mtime, size, Verdict.UNVERIFIABLE, "", tags, null, "", "", 0, "fichier sans artiste ni titre");
        }

        Fingerprinter.Result fp;
        try {
            fp = Fingerprinter.compute(f);
        } catch (Exception e) {
            // « Arrêter » pendant fpcalc fait échouer compute() exactement comme un fichier illisible : ne SURTOUT
            // PAS l'enregistrer (constaté au test d'arrêt du 2026-09-20 : un fichier sain se retrouvait avec un
            // verdict ERROR persistant).
            abortIfStopped();
            return row(path, mtime, size, Verdict.ERROR, "", tags, null, "", "", 0, e.getMessage());
        }
        int fileDur = parseIntOr(fp.duration(), tags.durationSec);

        pace(intervalMs);
        List<Candidate> candidates;
        try {
            candidates = acoustId.lookupRecordings(fp);
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception e) {
            throw new NetworkFailure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        Comparison cmp = AudioTagAudit.compare(tags.artist, tags.title, candidates);
        Verdict verdict = cmp.verdict();
        String shazam = "", note = cmp.note();
        TagInfo sr = null;

        if (AudioTagAudit.needsSecondOpinion(verdict)) {
            if (!songRecOk) {
                shazam = "NA";
            } else {
                try {
                    sr = songRec.recognize(f);
                } catch (InterruptedException ie) {
                    throw ie;
                } catch (Exception e) {
                    sr = null;
                }
                if (sr == null || sr.title.isBlank()) {
                    shazam = "SILENT";
                    sr = null;
                } else {
                    Candidate srCand = new Candidate(sr.artist, sr.title, 1.0, 0, "");
                    if (AudioTagAudit.compare(tags.artist, tags.title, List.of(srCand)).verdict() == Verdict.OK) {
                        // Shazam donne raison au tag : les deux moteurs se contredisent, AcoustID a probablement
                        // une empreinte mal rattachée côté base — ne pas accuser le fichier.
                        shazam  = "SUPPORTS_TAG";
                        verdict = Verdict.OK;
                        note    = "Shazam confirme les tags (AcoustID en désaccord)";
                    } else {
                        Candidate ac = cmp.best();
                        boolean agree = ac != null && AudioTagAudit.compare(ac.artist(), ac.title(),
                                List.of(srCand)).verdict() == Verdict.OK;
                        shazam = agree ? "AGREE" : "OTHER";
                    }
                }
            }
        }
        abortIfStopped();   // un second avis interrompu n'est pas un « Shazam n'a rien reconnu »
        return row(path, mtime, size, verdict, shazam, tags, cmp.best(), sr == null ? "" : sr.artist,
                sr == null ? "" : sr.title, fileDur, note);
    }

    /** Lève InterruptedException si l'audit a été arrêté : le résultat en cours est alors peut-être faussé par
     *  l'interruption elle-même (fpcalc/SongRec/HTTP coupés) et ne doit JAMAIS être mis en cache. */
    private void abortIfStopped() throws InterruptedException {
        if (stop || isCancelled() || Thread.currentThread().isInterrupted())
            throw new InterruptedException("audit arrêté");
    }

    private static AudioAuditStore.Row row(String path, long mtime, long size, Verdict v, String shazam, TagInfo tags,
                                           Candidate best, String srArtist, String srTitle, int fileDur, String note) {
        return new AudioAuditStore.Row(path, mtime, size, v.name(), shazam, tags.artist, tags.title,
                best == null ? "" : best.artist(), best == null ? "" : best.title(),
                best == null ? 0 : best.score(), best == null ? 0 : best.durationSec(),
                srArtist, srTitle, fileDur, note == null ? "" : note, false, System.currentTimeMillis());
    }

    private void count(AudioAuditStore.Row r) {
        Verdict v = Verdict.valueOf(r.verdict());
        switch (v) {
            case OK -> ok++;
            case UNVERIFIABLE -> unverifiable++;
            case ERROR -> errors++;
            default -> {
                suspects++;
                log("  ⚠ " + v + (r.shazam().isEmpty() ? "" : " [Shazam " + r.shazam() + "]") + " — "
                        + new File(r.path()).getName() + " : tag « " + r.tagArtist() + " — " + r.tagTitle()
                        + " » ≠ audio « " + r.acArtist() + " — " + r.acTitle() + " »");
            }
        }
    }

    /** Espace deux lookups d'au moins {@code intervalMs}, en plus du créneau partagé d'AcoustIdClient. */
    private void pace(long intervalMs) throws InterruptedException {
        long wait = lastLookupMs + intervalMs - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastLookupMs = System.currentTimeMillis();
    }

    private String notice = "";

    private void update(boolean running, int total, String current) {
        LAST = new Snapshot(running, total, done, cached, ok, suspects, unverifiable, errors, current, notice);
    }

    /** Arrêt avant le moindre fichier : la raison est journalisée ET portée par le Snapshot (la fenêtre de
     *  revue l'affiche — sans elle l'utilisateur verrait simplement "rien ne se passe"). */
    private void abort(String reason) {
        notice = reason;
        update(false, files.size(), "");
        log(reason);
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }

    /** Échec réseau/AcoustID (à distinguer d'un fichier illisible) : jamais mis en cache. */
    private static final class NetworkFailure extends Exception {
        NetworkFailure(String msg) { super(msg); }
    }
}
