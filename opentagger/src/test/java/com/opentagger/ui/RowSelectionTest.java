package com.opentagger.ui;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultListSelectionModel;
import org.junit.Test;

public class RowSelectionTest {

    @Test public void oneFinalEventNoMatterHowManyRows() {
        DefaultListSelectionModel m = new DefaultListSelectionModel();
        int[] finalEvents = {0};
        m.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) finalEvents[0]++; });
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < 50_000; i += 2) rows.add(i); // 25 000 lignes isolées : le pire cas
        RowSelection.select(m, rows);
        assertEquals("un seul rafraîchissement du détail", 1, finalEvents[0]);
        assertEquals(0, m.getMinSelectionIndex());
        assertEquals(49_998, m.getMaxSelectionIndex());
        assertEquals(true, m.isSelectedIndex(100));
        assertEquals(false, m.isSelectedIndex(101));
    }

    @Test public void contiguousRowsBecomeOneRange() {
        DefaultListSelectionModel m = new DefaultListSelectionModel();
        RowSelection.select(m, List.of(3, 4, 5, 6, 10));
        for (int i = 3; i <= 6; i++) assertEquals(true, m.isSelectedIndex(i));
        assertEquals(false, m.isSelectedIndex(7));
        assertEquals(true, m.isSelectedIndex(10));
    }

    @Test public void emptyListClearsTheSelection() {
        DefaultListSelectionModel m = new DefaultListSelectionModel();
        m.addSelectionInterval(0, 5);
        RowSelection.select(m, List.of());
        assertEquals(true, m.isSelectionEmpty());
    }
}
