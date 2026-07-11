package com.opentagger.ui;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vue arborescence par album du tableau principal — regroupe les {@link FileEntry} déjà exposés
 * par {@link FileTableModel} (source de vérité inchangée) en groupes pliables/dépliables, pistes
 * triées par disque/piste à l'intérieur de chaque groupe. Alternative au mode liste plate, activée
 * via {@code MainFrame.setViewMode()}.
 *
 * <p><b>Coût nul en mode liste</b> : ce modèle n'observe {@code source} que pendant que
 * {@link #attach()} a été appelé (donc pendant que la vue arborescence est réellement affichée) —
 * voir {@link #detach()}. Aucun listener enregistré, aucun travail fait, tant que l'utilisateur
 * reste en vue liste (le cas normal pendant un taguage de plusieurs heures sur une bibliothèque de
 * plusieurs centaines de milliers de fichiers).
 *
 * <p><b>Perf en mode arborescence</b> : aucun événement individuel de {@code source} ne déclenche
 * de recalcul global — {@link #tableChanged} met seulement à jour les structures internes (coût
 * borné à la taille du groupe affecté, jamais O(total)) et positionne un drapeau {@code dirty}.
 * L'aplatissement réel (liste de lignes affichées + {@code fireTableDataChanged()}) n'a lieu que
 * dans {@link #flushIfDirty()}, appelé par {@code MainFrame.refreshStats()} — déjà throttlé à
 * 300ms pendant les scans/taguages. Exactement le même principe que
 * {@code FileTableModel.rebuildVisibleIfDirty()}, pour la même raison (éviter un coût O(n) par
 * fichier sur un run de plusieurs heures).
 */
public class AlbumTreeTableModel extends AbstractTableModel implements TableModelListener {

    /** Un groupe = un album (ou, à défaut de tag album, un dossier) — voir {@link #groupKeyFor}. */
    private static final class Group {
        final String key;
        final String title;
        final List<FileEntry> members = new ArrayList<>();
        boolean collapsed = true; // replié par défaut — choix utilisateur validé pour cette vue
        Group(String key, String title) { this.key = key; this.title = title; }
    }

    /** Une ligne affichée : soit un en-tête de groupe, soit un fichier réel — même schéma que
     *  {@code RenamePreviewDialog.DisplayRow}, rendu incrémental/événementiel ici. */
    private record DisplayRow(Group group, FileEntry entry) {
        boolean isHeader() { return group != null; }
    }

    private final FileTableModel source;

    // Miroir de source.visible, tenu à jour uniquement pendant que ce modèle est attaché —
    // nécessaire pour résoudre correctement un événement DELETE : FileTableModel.remove() mute déjà
    // `visible` AVANT de faire fireTableRowsDeleted (voir son code), donc au moment où tableChanged()
    // s'exécute, source.get(row) ne renvoie plus l'entrée supprimée. mirrorVisible garde l'état
    // D'AVANT cet événement (on ne le mute qu'en traitant l'événement), donc mirrorVisible.get(row)
    // renvoie encore la bonne entrée au moment du DELETE. INSERT/UPDATE n'ont pas ce problème
    // (source.get(row) reste valide après leur mutation).
    private final List<FileEntry> mirrorVisible = new ArrayList<>();
    private final Map<FileEntry, String> keyOf = new IdentityHashMap<>();
    private final LinkedHashMap<String, Group> groupsByKey = new LinkedHashMap<>();

    private List<DisplayRow> flat = new ArrayList<>();
    private boolean attached = false;
    private boolean dirty    = false;

    public AlbumTreeTableModel(FileTableModel source) { this.source = source; }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    /** À appeler quand la vue arborescence devient active. Reconstruit tout depuis l'état courant
     *  de {@code source} et commence à écouter ses changements. */
    public void attach() {
        if (attached) return;
        attached = true;
        source.addTableModelListener(this);
        fullRebuild();
    }

    /** À appeler quand on revient en vue liste — arrête d'écouter et libère l'état interne. */
    public void detach() {
        if (!attached) return;
        attached = false;
        source.removeTableModelListener(this);
        mirrorVisible.clear();
        keyOf.clear();
        groupsByKey.clear();
        flat = new ArrayList<>();
    }

    private void fullRebuild() {
        mirrorVisible.clear();
        keyOf.clear();
        groupsByKey.clear();
        int n = source.getRowCount();
        for (int i = 0; i < n; i++) {
            FileEntry e = source.get(i);
            mirrorVisible.add(e);
            addToGroup(e);
        }
        reflattenAndFire();
    }

    // ── Écoute de FileTableModel ────────────────────────────────────────────

    @Override
    public void tableChanged(TableModelEvent e) {
        if (!attached) return;
        // fireTableDataChanged() (setFilter/removeEntries/replaceAll/clear) : firstRow=0,
        // lastRow=Integer.MAX_VALUE — pas d'info ligne par ligne exploitable, tout reconstruire.
        if (e.getLastRow() == Integer.MAX_VALUE) { fullRebuild(); return; }

        int first = e.getFirstRow(), last = e.getLastRow();
        if (first < 0) return;

        switch (e.getType()) {
            case TableModelEvent.DELETE -> {
                for (int row = Math.min(last, mirrorVisible.size() - 1); row >= first; row--) {
                    if (row < 0 || row >= mirrorVisible.size()) continue;
                    removeFromGroup(mirrorVisible.remove(row));
                }
            }
            case TableModelEvent.INSERT -> {
                for (int row = first; row <= last && row < source.getRowCount(); row++) {
                    FileEntry entry = source.get(row);
                    mirrorVisible.add(Math.min(row, mirrorVisible.size()), entry);
                    addToGroup(entry);
                }
            }
            case TableModelEvent.UPDATE -> {
                for (int row = first; row <= last && row < source.getRowCount(); row++) {
                    FileEntry entry = source.get(row);
                    String oldKey = keyOf.get(entry);
                    String newKey = groupKeyFor(entry);
                    if (oldKey == null) {
                        addToGroup(entry); // filet de sécurité, ne devrait pas arriver hors INSERT
                    } else if (!oldKey.equals(newKey)) {
                        // Rejoint un autre album (ou un album pour la première fois) — le cas
                        // central de la demande : un fichier identifié plus tard doit rejoindre
                        // son groupe automatiquement.
                        removeFromGroup(entry);
                        addToGroup(entry);
                    } else {
                        resortGroup(groupsByKey.get(oldKey));
                    }
                }
            }
            default -> { }
        }
        dirty = true;
    }

    public boolean isDirty() { return dirty; }

    /** À appeler périodiquement (MainFrame.refreshStats(), déjà throttlé à 300ms) — n'aplatit et ne
     *  notifie que s'il y a eu un changement depuis le dernier appel. Ne fait rien si cette vue
     *  n'est pas active. L'appelant doit sauvegarder/restaurer la sélection de la JTable autour de
     *  cet appel si besoin (fireTableDataChanged() efface la sélection) — ce modèle ne connaît pas
     *  la JTable, seulement les données. */
    public void flushIfDirty() {
        if (!attached || !dirty) return;
        reflattenAndFire();
    }

    // ── Groupement ───────────────────────────────────────────────────────────

    private void addToGroup(FileEntry e) {
        String key = groupKeyFor(e);
        Group g = groupsByKey.computeIfAbsent(key, k -> new Group(k, groupTitleFor(e)));
        insertSorted(g.members, e);
        keyOf.put(e, key);
    }

    private void removeFromGroup(FileEntry e) {
        String key = keyOf.remove(e);
        if (key == null) return;
        Group g = groupsByKey.get(key);
        if (g == null) return;
        g.members.remove(e); // identité (FileEntry n'override pas equals) — cohérent avec indexMap
        if (g.members.isEmpty()) groupsByKey.remove(key);
    }

    private void resortGroup(Group g) {
        if (g == null) return;
        List<FileEntry> copy = new ArrayList<>(g.members);
        g.members.clear();
        for (FileEntry e : copy) insertSorted(g.members, e);
    }

    private void insertSorted(List<FileEntry> members, FileEntry e) {
        int idx = 0;
        while (idx < members.size() && TRACK_ORDER.compare(members.get(idx), e) <= 0) idx++;
        members.add(idx, e);
    }

    /** Même clé que {@code RenamePreviewDialog.groupKey()} (déjà en prod) — dossier parent en repli
     *  plutôt qu'un bucket global unique "sans album" : sur un scan de centaines de milliers de
     *  fichiers, des dizaines de milliers de fichiers fraîchement découverts sans tag album le
     *  temps d'être identifiés se retrouveraient sinon tous dans UN SEUL groupe géant. */
    private String groupKeyFor(FileEntry e) {
        TagInfo tags = e.activeTags();
        String art = !tags.albumArtist.isBlank() ? tags.albumArtist : tags.artist;
        if (!tags.album.isBlank()) return "album::" + art + "::" + tags.album;
        Path parent = parentOf(e);
        return "folder::" + (parent != null ? parent : e.filename());
    }

    private String groupTitleFor(FileEntry e) {
        TagInfo tags = e.activeTags();
        String art = !tags.albumArtist.isBlank() ? tags.albumArtist : tags.artist;
        if (!tags.album.isBlank()) return art.isBlank() ? tags.album : art + " – " + tags.album;
        Path parent = parentOf(e);
        return "📁 " + (parent != null ? parent.getFileName() : e.filename());
    }

    private static Path parentOf(FileEntry e) {
        Path p = e.currentPath != null ? e.currentPath : (e.file != null ? e.file.toPath() : null);
        return p != null ? p.getParent() : null;
    }

    // Tri intra-groupe : disque puis piste (numérique, vide/illisible en DERNIER, pas en premier —
    // un morceau non numéroté ne doit pas usurper la place de la piste 1) puis nom de fichier en
    // dernier recours pour un ordre stable. N'existe nulle part ailleurs dans l'appli (même le tri
    // par clic sur la colonne "Piste" en vue liste plate compare les chaînes telles quelles, "10"
    // avant "2") — comparateur strictement local à cette vue, ne change rien à la vue liste.
    private static int leadingInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return fallback;
        try { return Integer.parseInt(s.substring(0, i)); } catch (NumberFormatException ex) { return fallback; }
    }

    private static final Comparator<FileEntry> TRACK_ORDER = Comparator
        .<FileEntry>comparingInt(e -> leadingInt(e.activeTags().discNo, 1))
        .thenComparingInt(e -> leadingInt(e.activeTags().track, Integer.MAX_VALUE))
        .thenComparing(FileEntry::filename, String.CASE_INSENSITIVE_ORDER);

    // ── Aplatissement ────────────────────────────────────────────────────────

    /** Pendant qu'un filtre est actif (recherche/chips de statut), tous les groupes sont forcés
     *  dépliés — jamais de résultat caché dans un groupe replié. L'état de pliage choisi par
     *  l'utilisateur (Group.collapsed) n'est pas modifié, juste temporairement ignoré ; restauré
     *  tel quel dès que le filtre est retiré (prochain fireTableDataChanged() de source). */
    private void reflattenAndFire() {
        boolean forceExpand = source.isFiltered();
        List<DisplayRow> next = new ArrayList<>();
        for (Group g : groupsByKey.values()) {
            next.add(new DisplayRow(g, null));
            if (!(g.collapsed && !forceExpand))
                for (FileEntry e : g.members) next.add(new DisplayRow(null, e));
        }
        flat = next;
        dirty = false;
        fireTableDataChanged();
    }

    private boolean effectiveCollapsed(Group g) { return g.collapsed && !source.isFiltered(); }

    // ── Actions utilisateur (plier/déplier) ─────────────────────────────────

    public void toggleCollapsed(int row) {
        if (row < 0 || row >= flat.size()) return;
        DisplayRow dr = flat.get(row);
        if (!dr.isHeader()) return;
        dr.group().collapsed = !dr.group().collapsed;
        reflattenAndFire();
    }

    public void expandAll()   { setAllCollapsed(false); }
    public void collapseAll() { setAllCollapsed(true); }

    private void setAllCollapsed(boolean collapsed) {
        for (Group g : groupsByKey.values()) g.collapsed = collapsed;
        reflattenAndFire();
    }

    // ── Résolution ligne ↔ entrée(s), pour MainFrame ────────────────────────

    public boolean isHeaderRow(int row) {
        return row >= 0 && row < flat.size() && flat.get(row).isHeader();
    }

    /** null si ligne d'en-tête ou index invalide. */
    public FileEntry fileEntryAt(int row) {
        if (row < 0 || row >= flat.size()) return null;
        DisplayRow dr = flat.get(row);
        return dr.isHeader() ? null : dr.entry();
    }

    /** Une ligne d'en-tête résout vers TOUS les membres du groupe (même replié) — utilisé pour la
     *  sélection multiple/édition en lot et les actions "tout l'album" sur l'en-tête. Une ligne
     *  normale résout vers elle-même seule. */
    public List<FileEntry> membersAt(int row) {
        if (row < 0 || row >= flat.size()) return List.of();
        DisplayRow dr = flat.get(row);
        return dr.isHeader() ? List.copyOf(dr.group().members) : List.of(dr.entry());
    }

    /** Ligne de vue actuelle pour cette entrée — la ligne membre si son groupe est déplié, sinon la
     *  ligne d'en-tête de son groupe (jamais forcé déplié) : voir MainFrame.followProcessing(). -1
     *  si l'entrée n'est pas (ou plus) connue de ce modèle. */
    public int viewRowOf(FileEntry entry) {
        String key = keyOf.get(entry);
        Group g = key != null ? groupsByKey.get(key) : null;
        if (g == null) return -1;
        for (int i = 0; i < flat.size(); i++) {
            DisplayRow dr = flat.get(i);
            if (dr.isHeader()) {
                if (dr.group() == g && effectiveCollapsed(g)) return i;
            } else if (dr.entry() == entry) {
                return i;
            }
        }
        return -1;
    }

    // ── AbstractTableModel ───────────────────────────────────────────────────

    @Override public int getRowCount()    { return flat.size(); }
    @Override public int getColumnCount() { return source.getColumnCount(); }
    @Override public String getColumnName(int col)      { return source.getColumnName(col); }
    @Override public Class<?> getColumnClass(int col)    { return source.getColumnClass(col); }

    @Override
    public boolean isCellEditable(int row, int col) {
        if (row < 0 || row >= flat.size()) return false;
        DisplayRow dr = flat.get(row);
        if (dr.isHeader()) return col == FileTableModel.COL_SEL;
        int sr = source.indexOf(dr.entry());
        return sr >= 0 && source.isCellEditable(sr, col);
    }

    @Override
    public Object getValueAt(int row, int col) {
        DisplayRow dr = flat.get(row);
        if (dr.isHeader()) return headerValueAt(dr.group(), col);
        int sr = source.indexOf(dr.entry());
        return sr >= 0 ? source.getValueAt(sr, col) : "";
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        DisplayRow dr = flat.get(row);
        if (dr.isHeader()) {
            if (col == FileTableModel.COL_SEL) {
                boolean sel = Boolean.TRUE.equals(value);
                for (FileEntry e : dr.group().members) e.selected = sel;
                // Rare (clic explicite sur la case de l'en-tête) — coût d'un aplatissement complet
                // accepté, même raisonnement que toggleCollapsed()/expandAll().
                reflattenAndFire();
            }
            return;
        }
        int sr = source.indexOf(dr.entry());
        if (sr >= 0) source.setValueAt(value, sr, col);
    }

    private Object headerValueAt(Group g, int col) {
        TagInfo first = g.members.isEmpty() ? null : g.members.get(0).activeTags();
        return switch (col) {
            case FileTableModel.COL_SEL          -> !g.members.isEmpty() && g.members.stream().allMatch(e -> e.selected);
            case FileTableModel.COL_FILE         -> (effectiveCollapsed(g) ? "▸ " : "▾ ") + g.title
                                                     + "  (" + g.members.size() + ")";
            case FileTableModel.COL_ARTIST,
                 FileTableModel.COL_ALBUM_ARTIST -> first != null ? first.albumArtist.isBlank() ? first.artist : first.albumArtist : "";
            case FileTableModel.COL_ALBUM        -> first != null ? first.album : "";
            case FileTableModel.COL_YEAR         -> first != null ? first.year : "";
            case FileTableModel.COL_STATUS       -> statusSummary(g);
            default -> "";
        };
    }

    private String statusSummary(Group g) {
        int tagged = 0, identified = 0, skipped = 0, error = 0, pending = 0;
        for (FileEntry e : g.members) {
            switch (e.status) {
                case TAGGED     -> tagged++;
                case IDENTIFIED -> identified++;
                case SKIPPED    -> skipped++;
                case ERROR      -> error++;
                default         -> pending++;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (tagged     > 0) sb.append(tagged).append(" ✓ ");
        if (identified > 0) sb.append(identified).append(" 🔍 ");
        if (error      > 0) sb.append(error).append(" ✗ ");
        if (skipped    > 0) sb.append(skipped).append(" ⚠ ");
        if (pending    > 0) sb.append(pending).append(" — ");
        return sb.toString().trim();
    }

    /** Utilisé par le renderer coloré de MainFrame : null pour une ligne d'en-tête (stylée à part,
     *  pas par statut), le statut réel sinon. */
    public FileEntry.Status statusOfMemberRow(int row) {
        FileEntry e = fileEntryAt(row);
        return e != null ? e.status : null;
    }

    public String getTooltip(int row) {
        if (row < 0 || row >= flat.size()) return null;
        DisplayRow dr = flat.get(row);
        if (dr.isHeader()) return null;
        int sr = source.indexOf(dr.entry());
        return sr >= 0 ? source.getTooltip(sr) : null;
    }
}
