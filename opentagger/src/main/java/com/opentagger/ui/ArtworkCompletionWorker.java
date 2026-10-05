package com.opentagger.ui;

import com.opentagger.CaaClient;
import com.opentagger.Config;
import com.opentagger.DeezerClient;
import com.opentagger.DiscogsClient;
import com.opentagger.FanArtClient;
import com.opentagger.FfmpegTagIO;
import com.opentagger.I18n;
import com.opentagger.MetadataCache;
import com.opentagger.TagEnrichment;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;

import javax.swing.SwingWorker;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * « Compléter les pochettes et photos manquantes » : UNE recherche par album (pochette) et UNE par artiste (portrait),
 * appliquée à toutes leurs pistes. Ne touche jamais ce qui existe déjà : une piste qui a déjà une pochette intégrée, un
 * dossier qui a déjà son artist.jpg sont laissés tels quels. Séquentiel (MusicBrainz/Cover Art Archive : 1 requête/s).
 *
 * Les données sont photographiées à la construction (sur l'EDT) : le thread de fond ne lit jamais un FileEntry affiché.
 */
public class ArtworkCompletionWorker extends SwingWorker<ArtworkCompletionWorker.Summary, Void> {

    /** Ce dont la passe a besoin d'un fichier, figé au moment de la demande. */
    public record Item(File file, String artist, String albumArtist, String album,
                       String releaseMbid, String releaseGroupMbid, String artistMbid) {}

    public record Summary(int albums, int coversAdded, int coversNotFound,
                          int artists, int photosAdded, int photosNotFound, int failures) {
        public boolean nothingToDo() { return coversAdded + coversNotFound + photosAdded + photosNotFound + failures == 0; }
    }

    private static final Set<String> VARIOUS = Set.of("variousartists", "variousartist", "va", "artistesvaries", "divers", "compilation");

    private final List<Item> items;
    private final Consumer<String> onStatus;
    private volatile boolean stopped;

    public ArtworkCompletionWorker(List<FileEntry> entries, Consumer<String> onStatus) {
        this.items = new ArrayList<>();
        for (FileEntry e : entries) {
            Item it = snapshot(e);
            if (it != null) items.add(it);
        }
        this.onStatus = onStatus != null ? onStatus : s -> {};
    }

    public void stopNow() { stopped = true; cancel(true); }

    static Item snapshot(FileEntry e) {
        if (e == null) return null;
        TagInfo t = e.activeTags();
        if (t == null) return null;
        File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
        if (f == null) return null;
        return new Item(f, nz(t.artist), nz(t.albumArtist), nz(t.album), nz(t.releaseMbid), nz(t.releaseGroupMbid), nz(t.artistMbid));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static String fold(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String mainArtist(Item i) { return !i.albumArtist().isBlank() ? i.albumArtist() : i.artist(); }

    /** Pistes d'un même album (artiste de l'album + titre d'album normalisés), dans l'ordre d'apparition. */
    static Map<String, List<Item>> groupByAlbum(List<Item> items) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (Item i : items) {
            if (i.album().isBlank()) continue;
            out.computeIfAbsent(fold(mainArtist(i)) + "|" + fold(i.album()), k -> new ArrayList<>()).add(i);
        }
        return out;
    }

    /** Pistes d'un même artiste ET d'un même dossier de portrait. Les compilations « Various Artists » n'ont pas de portrait. */
    static Map<String, List<Item>> groupByArtistFolder(List<Item> items) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        for (Item i : items) {
            String a = fold(mainArtist(i));
            if (a.isEmpty() || VARIOUS.contains(a)) continue;
            Path dir = TagEnrichment.artistPhotoFolder(i.file().toPath().toAbsolutePath(), photoInfo(i));
            out.computeIfAbsent(dir + "|" + a, k -> new ArrayList<>()).add(i);
        }
        return out;
    }

    private static TagInfo photoInfo(Item i) {
        TagInfo t = new TagInfo();
        t.artist = i.artist();
        t.albumArtist = i.albumArtist();
        t.artistMbid = i.artistMbid();
        return t;
    }

    /** Le dossier a-t-il déjà un portrait (jpg ou png) ? */
    static boolean hasPhoto(Path dir, String baseName) {
        return Files.exists(dir.resolve(baseName + ".jpg")) || Files.exists(dir.resolve(baseName + ".png"));
    }

    static boolean hasEmbeddedCover(File f) {
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            return tag != null && tag.getFirstArtwork() != null;
        } catch (Exception e) {
            return true; // illisible : on ne tente pas d'écrire dedans
        }
    }

    @Override
    protected Summary doInBackground() {
        CaaClient caa = new CaaClient();
        FanArtClient fanArt = new FanArtClient();
        DeezerClient deezer = new DeezerClient();
        DiscogsClient discogs = new DiscogsClient();
        TagWriter writer = new TagWriter();
        MetadataCache cache = new MetadataCache();
        int albums = 0, covers = 0, coversMissed = 0, artists = 0, photos = 0, photosMissed = 0, failures = 0;
        try {
            // ── Pochettes : une recherche par album ───────────────────────────────────────────────────────────
            for (List<Item> group : groupByAlbum(items).values()) {
                if (stopped || isCancelled()) break;
                List<File> missing = new ArrayList<>();
                for (Item i : group)
                    if (i.file().isFile() && !FfmpegTagIO.handles(i.file()) && !hasEmbeddedCover(i.file())) missing.add(i.file());
                if (missing.isEmpty()) continue;
                albums++;
                Item rep = group.get(0);
                for (Item i : group) if (!i.releaseMbid().isBlank()) { rep = i; break; }
                TagInfo ti = new TagInfo();
                ti.artist = mainArtist(rep);
                ti.albumArtist = rep.albumArtist();
                ti.album = rep.album();
                ti.artistMbid = rep.artistMbid();
                for (Item i : group) {
                    if (ti.releaseMbid.isBlank() && !i.releaseMbid().isBlank()) ti.releaseMbid = i.releaseMbid();
                    if (ti.releaseGroupMbid.isBlank() && !i.releaseGroupMbid().isBlank()) ti.releaseGroupMbid = i.releaseGroupMbid();
                }
                onStatus.accept(I18n.t("Pochette : %s – %s", ti.artist, ti.album));
                Path img = TagEnrichment.resolveCover(ti, missing.get(0), caa, fanArt, deezer, cache);
                if (img == null) { coversMissed++; log("pochette introuvable : " + ti.artist + " – " + ti.album); continue; }
                try {
                    int ok = 0;
                    for (File f : missing) {
                        if (stopped || isCancelled()) break;
                        try { writer.writeCoverOnly(f, img); ok++; }
                        catch (Exception ex) { failures++; log("pochette non écrite dans " + f.getName() + " : " + ex.getMessage()); }
                    }
                    if (ok > 0) {
                        covers++;
                        if (Config.get().coverSaveToFile()) {
                            String ext = img.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") ? ".png" : ".jpg";
                            Path dest = missing.get(0).toPath().resolveSibling(Config.get().coverFilename() + ext);
                            if (!Files.exists(dest) && !img.equals(dest)) {
                                try { Files.copy(img, dest, StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
                            }
                        }
                        log("pochette ajoutée à " + ok + " piste(s) : " + ti.artist + " – " + ti.album);
                    }
                } finally {
                    TagEnrichment.discardTemporaryCover(img);
                }
            }

            // ── Portraits : une recherche par artiste ─────────────────────────────────────────────────────────
            if (Config.get().artistPhotoEnabled()) {
                String base = Config.get().artistPhotoFilename();
                for (List<Item> group : groupByArtistFolder(items).values()) {
                    if (stopped || isCancelled()) break;
                    Item rep = group.get(0);
                    for (Item i : group) if (!i.artistMbid().isBlank()) { rep = i; break; }
                    TagInfo ti = photoInfo(rep);
                    Path dir = TagEnrichment.artistPhotoFolder(rep.file().toPath().toAbsolutePath(), ti);
                    if (hasPhoto(dir, base)) continue;
                    artists++;
                    onStatus.accept(I18n.t("Photo d'artiste : %s", mainArtist(rep)));
                    Path photo = null;
                    try {
                        photo = fanArt.downloadArtistPhoto(ti, cache);
                        if (photo == null) photo = discogs.downloadArtistPhotoFallback(ti, cache);
                        if (photo == null) photo = deezer.downloadArtistPhoto(ti, cache);
                        if (photo == null) { photosMissed++; log("photo introuvable : " + mainArtist(rep)); continue; }
                        String ext = photo.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") ? ".png" : ".jpg";
                        Path dest = dir.resolve(base + ext);
                        if (!Files.exists(dest)) {
                            Files.copy(photo, dest);
                            photos++;
                            log("photo ajoutée : " + dest);
                        }
                    } catch (Exception ex) {
                        failures++;
                        log("photo non écrite pour " + mainArtist(rep) + " : " + ex.getMessage());
                    } finally {
                        if (photo != null) { try { Files.deleteIfExists(photo); } catch (Exception ignored) {} }
                    }
                }
            }
        } finally {
            cache.close();
        }
        return new Summary(albums, covers, coversMissed, artists, photos, photosMissed, failures);
    }

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] [artwork] " + msg);
        System.out.flush();
    }
}
