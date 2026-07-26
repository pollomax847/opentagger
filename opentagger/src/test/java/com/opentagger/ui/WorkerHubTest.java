package com.opentagger.ui;

import org.junit.Test;

import javax.swing.SwingWorker;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Couvre le correctif "Arrêter n'importe jamais WorkerHub.active si le worker reste bloqué sur
 * une E/S non interruptible" (montage NAS/MergerFS qui décroche en pleine écriture, ffmpeg gelé,
 * etc. — voir le commentaire de TaskHandle.cancel()). Avant ce correctif, cancel() se contentait
 * d'appeler cancelAction et attendait le "state"==DONE du worker pour libérer le TaskKind ; si ce
 * DONE n'arrivait jamais, tout Enregistrer/Tagger ultérieur restait bloqué avec un message
 * trompeur ("Enregistrement annulé"/"Taguage en cours") même après un arrêt explicite.
 */
public class WorkerHubTest {

    /** Simule un thread bloqué sur une E/S non interruptible : la boucle ravale
     *  InterruptedException et rebloque aussitôt, exactement ce qu'un Thread.interrupt()/
     *  shutdownNow() ne peut pas débloquer (contrairement à un simple appel réseau/HTTP borné). */
    private static class StuckWorker extends SwingWorker<Void, Void> {
        @Override protected Void doInBackground() {
            while (true) {
                try { Thread.sleep(Long.MAX_VALUE); }
                catch (InterruptedException ignored) { /* comme une E/S bloquante réelle : on continue */ }
            }
        }
    }

    @Test
    public void cancelReleasesKindEvenIfWorkerNeverReachesDone() {
        StuckWorker worker = new StuckWorker();
        // Comme SaveWorker.stopNow()/TaggingWorker.stopNow() : appelle cancel(true), qui interrompt
        // le thread une fois — sans effet ici puisque la boucle rebloque immédiatement.
        WorkerHub.TaskHandle handle =
            WorkerHub.get().submit(WorkerHub.TaskKind.SAVE, "Test", worker, () -> worker.cancel(true));

        assertTrue("la tâche doit apparaître active juste après submit()",
            WorkerHub.get().current(WorkerHub.TaskKind.SAVE).isPresent());

        handle.cancel();

        assertEquals("cancel() doit libérer SAVE immédiatement, sans attendre un DONE qui, ici, "
            + "n'arrivera jamais",
            Optional.empty(), WorkerHub.get().current(WorkerHub.TaskKind.SAVE));
    }
}
