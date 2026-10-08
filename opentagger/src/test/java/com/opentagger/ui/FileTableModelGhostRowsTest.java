package com.opentagger.ui;

import static org.junit.Assert.assertEquals;

import com.opentagger.model.FileEntry;
import java.io.File;
import java.util.Set;
import org.junit.Test;

/**
 * Régression (2026-10-03) : le chip « Total » (lignes affichées) montrait 187 340 alors que la
 * bibliothèque (fichiers chargés) en comptait 130 333. Cause : update() rajoutait dans la vue une
 * entrée déjà RETIRÉE de la liste complète (scan annulé, ligne retirée, « Vider la liste ») dès qu'un
 * traitement en arrière-plan la mettait à jour — une ligne fantôme comptée par la vue seulement.
 */
public class FileTableModelGhostRowsTest {

    private static FileEntry entry(String name) {
        return new FileEntry(new File(name), null);
    }

    @Test
    public void updateOfARemovedEntryDoesNotResurrectItAsAGhostRow() {
        FileTableModel m = new FileTableModel();
        FileEntry a = entry("a.mp3"), b = entry("b.mp3"), c = entry("c.mp3");
        m.add(a); m.add(b); m.add(c);

        m.removeEntries(Set.of(a));          // rollback d'un scan annulé
        assertEquals(2, m.totalCount());
        assertEquals(2, m.getRowCount());

        m.update(a);                         // une tâche d'arrière-plan finit et met à jour l'entrée retirée
        assertEquals(2, m.totalCount());
        assertEquals("la vue ne doit jamais compter plus que la bibliothèque", 2, m.getRowCount());
    }

    @Test
    public void updateAfterRemoveRowOrClearDoesNotCreateGhosts() {
        FileTableModel m = new FileTableModel();
        FileEntry a = entry("a.mp3"), b = entry("b.mp3");
        m.add(a); m.add(b);

        m.remove(0);                         // « Retirer la sélection »
        m.update(a);
        assertEquals(1, m.getRowCount());

        m.clear();                           // « Vider la liste »
        m.update(a);
        m.update(b);
        assertEquals(0, m.totalCount());
        assertEquals(0, m.getRowCount());
    }

    @Test
    public void aFilteredEntryThatBecomesMatchingIsStillAddedToTheView() {
        FileTableModel m = new FileTableModel();
        FileEntry a = entry("a.mp3");
        m.add(a);
        m.setFilter(e -> e.status == FileEntry.Status.TAGGED);   // a (PENDING) n'est pas visible
        assertEquals(0, m.getRowCount());

        a.status = FileEntry.Status.TAGGED;
        m.update(a);                                             // légitime : devient visible
        assertEquals(1, m.getRowCount());
        assertEquals(1, m.totalCount());
    }
}
