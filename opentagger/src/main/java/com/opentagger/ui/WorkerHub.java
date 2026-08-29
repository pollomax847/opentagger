package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;

import javax.swing.SwingWorker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Registre central des passes de fond (Tagger/Enregistrer/Compléter albums/...), à la place des
 * 9 champs nullable + gardes if dupliquées qui vivaient dans MainFrame. Remplace aussi
 * l'utilisation directe de SwingWorker.execute() : cette méthode soumet simplement `this` au pool
 * STATIQUE de Swing, partagé par toute la JVM et plafonné à 10 threads (javax.swing.SwingWorker.
 * MAX_WORKER_THREADS) — avec ~40 sites au total (les 10 classes Worker nommées + une trentaine de
 * SwingWorker anonymes dans MainFrame/les dialogues) qui s'y disputent 10 places, c'est le
 * mécanisme réel derrière l'incident "9/10 slots épuisés" par un SaveWorker bloqué, pas seulement
 * le timeout HTTP manquant qui l'a déclenché cette fois-ci.
 *
 * doInBackground()/process()/done()/les événements de propriété ne dépendent en rien de QUEL
 * executor a appelé run() (SwingWorker.run() est final ; le passage sur l'EDT est interne et
 * inconditionnel) — la migration se résume donc à ne plus appeler worker.execute() et à passer
 * par submit() ci-dessous à la place, sans toucher aux classes Worker elles-mêmes.
 */
public final class WorkerHub {

    public enum TaskKind {
        TAGGING, SAVE, ALBUM_COMPLETION, INFO_COMPLETER, ALBUM_CLUSTER,
        COMPILATION_CLUSTER, TRANSCODE, VIDEO_RECOVERY, LISTENBRAINZ_SYNC, LASTFM_SYNC, PODCAST_TAG,
        DUPLICATE_DETECT
    }

    /** Passes qui écrivent/renomment des fichiers de la bibliothèque — s'excluent mutuellement,
     *  sauf l'exception Enregistrer/Tagger gérée à part dans conflictsWith(). DUPLICATE_DETECT
     *  n'écrit rien elle-même (voir DuplicateDetector/DuplicatesDialog), mais rejoint ce groupe
     *  pour la même raison que COMPILATION_CLUSTER : le vrai risque est la suppression/déplacement
     *  fait par l'utilisateur juste après (DuplicatesDialog), pendant qu'un autre worker écrirait
     *  encore sur les mêmes fichiers — d'où l'exclusion mutuelle dès la phase de détection plutôt
     *  qu'au moment du clic sur "Supprimer". */
    private static final Set<TaskKind> LIBRARY_WRITE = EnumSet.of(
            TaskKind.TAGGING, TaskKind.ALBUM_COMPLETION, TaskKind.INFO_COMPLETER, TaskKind.TRANSCODE,
            TaskKind.ALBUM_CLUSTER, TaskKind.COMPILATION_CLUSTER, TaskKind.PODCAST_TAG,
            TaskKind.DUPLICATE_DETECT);

    public static final class TaskHandle {
        private final TaskKind kind;
        private final String label;
        private final Instant startTime;
        private final SwingWorker<?, ?> worker;
        private final Runnable cancelAction;

        private TaskHandle(TaskKind kind, String label, SwingWorker<?, ?> worker, Runnable cancelAction) {
            this.kind         = kind;
            this.label        = label;
            this.startTime    = Instant.now();
            this.worker       = worker;
            this.cancelAction = cancelAction;
        }

        public TaskKind kind()      { return kind; }
        public String   label()     { return label; }
        public boolean  isRunning() { return !worker.isDone(); }

        /** Toujours passer par ici, jamais worker.cancel(true) en direct : cancelAction est le
         *  stopNow()/cancel(bool) propre à chaque worker (voir SaveWorker.stopNow() et pareil
         *  ailleurs) — un Future.cancel() nu n'interromprait pas leur pool interne.
         *
         *  Libère aussi `kind` immédiatement, sans attendre le "state"==DONE du worker (voir le
         *  listener posé dans submit()) : ce DONE peut ne jamais arriver si le thread reste
         *  bloqué sur une E/S non interruptible (montage NAS/MergerFS qui décroche en pleine
         *  écriture — déjà vu en prod, voir le commentaire de timeout dans
         *  AudioTranscoder.transcode()) — shutdownNow()/cancel(true) n'y peuvent rien, ni ffmpeg
         *  ni un simple Files.write() ne se laissent interrompre par un thread Java. Sans ce
         *  retrait immédiat, "Arrêter" réinitialise l'UI (MainFrame.stopAll()) mais WorkerHub
         *  continue de croire la tâche active pour toujours, bloquant tout Enregistrer/Tagger
         *  ultérieur avec un message trompeur ("Enregistrement annulé"/"Taguage en cours") même
         *  après un arrêt explicite — retour utilisateur après plusieurs jours d'utilisation
         *  continue. Un thread zombie ainsi abandonné reste borné à `batch.threads` fichiers déjà
         *  en cours au moment du clic (le pool est shutdownNow() juste avant, donc rien d'autre
         *  ne démarre) — même risque déjà accepté côté UI, qui remet ces mêmes entrées PROCESSING
         *  à PENDING sans attendre ces threads non plus (voir MainFrame.stopAll()). */
        public void cancel() {
            cancelAction.run();
            WorkerHub.get().active.remove(kind, this);
        }
    }

    private static final WorkerHub INSTANCE = new WorkerHub();
    public static WorkerHub get() { return INSTANCE; }

    // Seul l'EDT touche cette map : submit()/current()/blockers()/activeLabels()/cancelAll() sont
    // tous appelés depuis des gestionnaires d'action Swing, et le retrait au "state"==DONE repasse
    // par le même thread (SwingWorker garantit que ses PropertyChangeListener sont notifiés sur
    // l'EDT). Même invariant que les 9 champs nullable qu'elle remplace — pas besoin d'une map
    // concurrente.
    private final Map<TaskKind, TaskHandle> active = new EnumMap<>(TaskKind.class);

    // Threads "coordinateurs" : chacun passe le plus clair de son temps bloqué sur future.get() à
    // attendre le pool interne (batch.threads) du worker qu'il porte — même profil que les threads
    // de drainage de ProcessUtils. Des threads virtuels évitent de choisir arbitrairement une
    // taille de pool : au plus 10 TaskKind peuvent de toute façon être actifs simultanément.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private WorkerHub() {}

    public Optional<TaskHandle> current(TaskKind kind) {
        return Optional.ofNullable(active.get(kind));
    }

    /** Tâches actives qui empêcheraient kind de démarrer maintenant. Vide = rien ne bloque. */
    public List<TaskHandle> blockers(TaskKind kind) {
        List<TaskHandle> result = new ArrayList<>();
        for (TaskHandle h : active.values()) {
            if (conflictsWith(kind, h.kind())) result.add(h);
        }
        return result;
    }

    /** Labels de blockers(kind) — pour les messages "Encore en cours : X, Y — attendez la fin...". */
    public List<String> blockerLabels(TaskKind kind) {
        List<String> labels = new ArrayList<>();
        for (TaskHandle h : blockers(kind)) labels.add(h.label());
        return labels;
    }

    /** Toutes les tâches actives, tous types confondus — remplace le corps d'activeOperations(). */
    public List<TaskHandle> active() {
        return new ArrayList<>(active.values());
    }

    public List<String> activeLabels() {
        List<String> labels = new ArrayList<>();
        for (TaskHandle h : active.values()) labels.add(h.label());
        return labels;
    }

    /**
     * Lance worker sur l'executor du hub (jamais worker.execute()) et l'enregistre sous kind.
     * Revalide blockers(kind) lui-même en filet de sécurité — l'appelant garde la main sur ses
     * propres vérifications et messages (French/UX) avant d'appeler submit(), une violation ici
     * signalerait un bug d'appelant, pas un cas attendu en usage normal.
     */
    public TaskHandle submit(TaskKind kind, String label, SwingWorker<?, ?> worker, Runnable cancelAction) {
        List<TaskHandle> blockers = blockers(kind);
        if (!blockers.isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (TaskHandle h : blockers) labels.add(h.label());
            throw new IllegalStateException("WorkerHub.submit(" + kind + ") refusé, bloqué par : " + labels);
        }

        TaskHandle handle = new TaskHandle(kind, label, worker, cancelAction);

        // Ordre impératif : écouteur posé ET entrée dans active AVANT l'exécution. Dans l'autre
        // sens, un worker assez rapide pourrait passer à state==DONE avant que le listener existe,
        // laissant l'entrée dans active pour toujours — une fuite silencieuse, symétrique du bug
        // qu'on corrige.
        worker.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName()) && evt.getNewValue() == SwingWorker.StateValue.DONE) {
                active.remove(kind, handle);
            }
        });
        active.put(kind, handle);
        executor.execute(worker);
        return handle;
    }

    /** Annule toutes les tâches actives — remplace la séquence à 8 lignes de MainFrame.stopAll(). */
    public void cancelAll() {
        for (TaskHandle h : new ArrayList<>(active.values())) h.cancel();
    }

    private static boolean conflictsWith(TaskKind a, TaskKind b) {
        if (a == b) return true;
        if ((a == TaskKind.LISTENBRAINZ_SYNC && b == TaskKind.TAGGING)
                || (a == TaskKind.TAGGING && b == TaskKind.LISTENBRAINZ_SYNC)) return true;
        // LASTFM_SYNC (LastFmSyncWorker) : même raison que LISTENBRAINZ_SYNC juste au-dessus, même
        // forme d'écriture (writer.write() sur les fichiers déjà TAGGED du tableau).
        if ((a == TaskKind.LASTFM_SYNC && b == TaskKind.TAGGING)
                || (a == TaskKind.TAGGING && b == TaskKind.LASTFM_SYNC)) return true;
        // Recherche de compilation (CompilationClusterWorker) : ne fait QUE chercher des
        // correspondances et les remonter pour revue utilisateur (CompilationMatchDialog) — n'écrit
        // RIEN sur le disque ni ne mute aucun FileEntry pendant cette passe (voir sa Javadoc), et
        // ne regarde que les fichiers déjà TAGUÉS, jamais les PENDING/PROCESSING que Tagger traite
        // — ensembles disjoints, même exception que Enregistrer/Tagger juste en dessous. Débloqué
        // le 2026-07-28 après un retour utilisateur ("ça bloque le taguage pour rien").
        if ((a == TaskKind.TAGGING && b == TaskKind.COMPILATION_CLUSTER)
                || (a == TaskKind.COMPILATION_CLUSTER && b == TaskKind.TAGGING)) return false;
        // INFO_COMPLETER (InfoCompleterWorker, "▶ COMPLÉTER") : ne cible QUE les fichiers déjà
        // status==TAGGED||IDENTIFIED (autoCompleteIncomplete(), snapshot figé passé au constructeur,
        // jamais de relecture live de tableModel) — disjoint par construction de la cible de Tagger
        // (PENDING/SKIPPED/ERROR), même exception que Enregistrer/Tagger juste au-dessus. SANS ce
        // correctif : une passe de complétion sur plusieurs milliers de fichiers déjà tagués (réseau,
        // peut prendre des heures) affamait indéfiniment le taguage de nouveaux fichiers — la boucle
        // de relance automatique (scheduleAutoTaggingFollowUp) se contentait de se reprogrammer en
        // silence sans jamais aboutir, sans même prévenir l'utilisateur — repéré en direct
        // (2026-08-13) : 3971 fichiers PENDING jamais repris après 2h+ de complétion ininterrompue.
        // ALBUM_COMPLETION (différent d'INFO_COMPLETER malgré le nom proche) reste volontairement
        // EXCLU de cette exception : il pioche AUSSI dans les candidats SKIPPED/PENDING en lecture
        // live (AlbumCompletionWorker.doInBackground()), un vrai chevauchement avec Tagger sans
        // verrou croisé — le débloquer causerait une vraie course de données (perte de mise à jour),
        // pas juste résoudre la famine.
        if ((a == TaskKind.TAGGING && b == TaskKind.INFO_COMPLETER)
                || (a == TaskKind.INFO_COMPLETER && b == TaskKind.TAGGING)) return false;
        if (a == TaskKind.SAVE || b == TaskKind.SAVE) {
            TaskKind other = (a == TaskKind.SAVE) ? b : a;
            // Enregistrer et Tagger touchent des ensembles de fichiers disjoints par construction
            // (IDENTIFIED vs PENDING/PROCESSING) — seule exception volontaire du modèle, reprise
            // telle quelle des gardes existantes de startTagging()/saveAll().
            return other != TaskKind.TAGGING && LIBRARY_WRITE.contains(other);
        }
        return LIBRARY_WRITE.contains(a) && LIBRARY_WRITE.contains(b);
    }

    /**
     * Filet de sécurité pour les boucles "for (Future<?> f : futures) f.get();" des workers à pool
     * interne — PAS une échéance par fichier (certaines passes durent légitimement des heures/jours
     * sur une grosse bibliothèque, voir TaggingWorker/AlbumCompletionWorker), seulement un dernier
     * recours si une tâche reste bloquée bien au-delà de ce qu'un appel borné (HTTP, sous-processus)
     * devrait jamais prendre. Jusqu'ici un tel blocage restait invisible (catch(Exception ignored)
     * muet) et pouvait épuiser le pool SwingWorker statique sans aucune trace — d'où l'incident.
     */
    public static void awaitAll(ExecutorService pool, List<Future<?>> futures, long timeoutSec) {
        pool.shutdown();
        for (Future<?> f : futures) {
            try {
                f.get(timeoutSec, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log(I18n.t("Tâche bloquée depuis plus de %ds, arrêt forcé du pool restant.", timeoutSec));
                pool.shutdownNow();
            } catch (Exception ignored) {
                // Annulation normale ou erreur déjà journalisée par la tâche elle-même.
            }
        }
    }

    // 900s (15 min) par défaut jusqu'ici — trop agressif en pratique : constaté en direct sur un lot
    // de 1252 fichiers "Enregistrer tout", CROSS_DEVICE_COPY_LIMIT (voir FileRenamer) sérialise les
    // déplacements cross-device à 1 seul à la fois pour ménager un disque mécanique/USB de
    // destination — un fichier peut légitimement attendre son tour plus de 15 minutes derrière des
    // centaines d'autres. Ce délai déclenchait alors pool.shutdownNow(), qui interrompt TOUS les
    // threads en cours (pas seulement celui jugé "bloqué"), causant une cascade de "Déplacement
    // cross-device interrompu" sur des fichiers qui progressaient normalement. Relevé à 6h : reste
    // un filet de sécurité contre un VRAI blocage infini (appel réseau/sous-processus sans propre
    // timeout), sans jamais confondre ça avec une file d'attente longue mais bornée.
    public static long defaultFutureTimeoutSec() {
        return Config.get().num("worker.future_timeout_sec", 21600);
    }

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }
}
