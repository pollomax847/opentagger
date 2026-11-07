package com.opentagger;

import com.opentagger.model.TagInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identification par ALBUM ENTIER, avant la recherche chanson par chanson — comme SongKong et Bliss (demande de
 * l'utilisateur, 2026-11-07, après la comparaison avec Bliss : « reconnaître l'album entier d'abord »).
 *
 * <p>Pour un dossier qui ressemble à un album (au moins 3 fichiers, un même nom d'album sur la plupart, un même artiste
 * ou des artistes variés pour une compilation), on cherche les parutions MusicBrainz de cet album, puis on garde celle
 * qui explique le plus de fichiers — au moins 70 % (voir {@link AlbumMatcher} : chaque fichier trouve sa piste, à durée
 * compatible ; un ou deux fichiers étrangers au disque suivent ensuite la recherche chanson par chanson).
 * Avant d'accepter, trois garde-fous :
 * <ul>
 *   <li>le nom de la parution est bien celui de l'album ;</li>
 *   <li>l'artiste de la parution est celui du dossier (ou c'est une compilation pour un dossier aux artistes variés) —
 *       « 1989 » de Ryan Adams a les mêmes titres que celui de Taylor Swift ;</li>
 *   <li>au moins 60 % des fichiers sont rapprochés par leur TITRE (ou leur identifiant d'enregistrement), pas seulement
 *       par leur numéro et leur durée.</li>
 * </ul>
 * Sinon rien n'est décidé et chaque fichier suit la recherche habituelle.
 *
 * <p>Une première passe « album d'abord » avait été retirée le 2026-07-27 : elle cherchait une parution pour TOUS les
 * dossiers, y compris les dossiers fourre-tout, et bloquait le lot avant qu'aucun fichier ne soit tagué. Celle-ci ne
 * s'applique qu'aux dossiers qui ressemblent à un album ({@link #hints}), coûte au plus une recherche et
 * {@link #MAX_LOOKUPS} lectures de parution par dossier, et se calcule au moment où le premier fichier du dossier passe
 * (les autres dossiers continuent en parallèle).
 */
public final class AlbumFirst {

    private AlbumFirst() {}

    /** Un fichier du dossier, tel que ses tags actuels le décrivent (valeur absente = 0 ou chaîne vide). */
    public record Track(String id, String title, String artist, String album, String albumArtist, String year,
                        int durationSec, int trackNo, int discNo, String recordingMbid, String releaseMbid) {}

    /** Ce que le dossier annonce : nom d'album, artiste (vide pour une compilation), année. */
    public record Hints(String album, String artist, String year, boolean compilation) {}

    /** Parution retenue et piste de chaque fichier. */
    public record Decision(MusicBrainzClient.ReleaseTracklist release, Map<String, MusicBrainzClient.ReleaseTrack> trackById) {}

    /** Résultat pour le journal : la décision, ou la raison de l'abstention. */
    public record Outcome(Decision decision, Hints hints, String why) {}

    /** Accès à une base de parutions — MusicBrainz, ou Discogs en 2e source (interface pour les tests). */
    public interface Lookup {
        List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, int limit) throws Exception;
        MusicBrainzClient.ReleaseTracklist lookupRelease(String releaseMbid) throws Exception;
        /** Les identifiants de parution déjà notés dans les tags (MusicBrainz) sont-ils lisibles ici ? */
        default boolean usesTaggedReleaseIds() { return true; }
        /** Recherche qui peut tenir compte de l'année annoncée par les tags (Discogs : l'édition de cette année d'abord). */
        default List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, String year, int limit) throws Exception {
            return searchReleases(album, artist, limit);
        }
        /** Complète la parution retenue (Discogs : année de première parution). */
        default void complete(MusicBrainzClient.ReleaseTracklist chosen) {}
    }

    public static Lookup of(MusicBrainzClient mb) {
        return new Lookup() {
            @Override public List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, int limit) throws Exception {
                return mb.searchReleases(album, artist, limit);
            }
            @Override public MusicBrainzClient.ReleaseTracklist lookupRelease(String releaseMbid) throws Exception {
                return mb.lookupRelease(releaseMbid);
            }
        };
    }

    /**
     * Discogs en 2e source, comme SongKong (demande de l'utilisateur, 2026-11-07) : quand MusicBrainz ne connaît pas
     * l'album (éditions locales, mixtapes, compilations françaises…), les parutions Discogs du même nom sont essayées avec
     * EXACTEMENT les mêmes garde-fous. Les identifiants de parution Discogs sont notés « discogs:12345 » ici seulement :
     * aucun n'est jamais écrit comme identifiant MusicBrainz.
     */
    public static Lookup ofDiscogs(DiscogsClient dc) {
        return new Lookup() {
            @Override public List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, int limit) throws Exception {
                return searchReleases(album, artist, "", limit);
            }
            @Override public List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, String year, int limit) throws Exception {
                List<DiscogsClient.AlbumHit> hits;
                // Cache ouvert seulement quand Discogs sert vraiment (MusicBrainz n'a rien décidé pour ce dossier).
                try (MetadataCache cache = new MetadataCache()) { hits = new ArrayList<>(dc.searchAlbumReleases(album, artist, limit, cache)); }
                // CD et fichiers d'abord, puis l'édition de l'année des tags (tri stable : l'ordre de Discogs départage) : leurs
                // positions donnent disque et piste, leurs durées sont plus souvent remplies que celles d'un vinyle.
                int y = yearOf(year);
                hits.sort(Comparator.comparingInt((DiscogsClient.AlbumHit h) -> h.formats().matches("(?i).*\\b(CD|File|CDr)\\b.*") ? 0 : 1)
                        .thenComparingInt(h -> y <= 0 || yearOf(h.year()) <= 0 ? 0 : Math.abs(yearOf(h.year()) - y)));
                List<MusicBrainzClient.ReleaseRef> out = new ArrayList<>();
                for (DiscogsClient.AlbumHit h : hits) out.add(new MusicBrainzClient.ReleaseRef(DISCOGS_PREFIX + h.id(), h.album(), null));
                return out;
            }
            @Override public MusicBrainzClient.ReleaseTracklist lookupRelease(String id) throws Exception {
                if (id == null || !id.startsWith(DISCOGS_PREFIX)) return null;
                long n;
                try { n = Long.parseLong(id.substring(DISCOGS_PREFIX.length())); }
                catch (NumberFormatException e) { return null; }
                try (MetadataCache cache = new MetadataCache()) { return dc.lookupAlbumRelease(n, cache); }
            }
            @Override public boolean usesTaggedReleaseIds() { return false; }
            @Override public void complete(MusicBrainzClient.ReleaseTracklist chosen) {
                TagInfo m = chosen.releaseMeta();
                if (m == null || m.discogsMasterId == null || m.discogsMasterId.isBlank() || !nz(m.originalYear).isBlank()) return;
                try (MetadataCache cache = new MetadataCache()) {
                    m.originalYear = dc.masterYear(Long.parseLong(m.discogsMasterId), cache);
                } catch (Exception ignored) {
                    // année d'origine facultative
                }
            }
        };
    }

    private static int yearOf(String s) {
        if (s == null || s.length() < 4) return 0;
        try { return Integer.parseInt(s.substring(0, 4)); } catch (NumberFormatException e) { return 0; }
    }

    static final String DISCOGS_PREFIX = "discogs:";

    /** Parution venue de Discogs (pas d'identifiant MusicBrainz, un identifiant Discogs) ? */
    public static boolean fromDiscogs(MusicBrainzClient.ReleaseTracklist tl) {
        return tl != null && (tl.releaseMbid() == null || tl.releaseMbid().isBlank())
                && tl.releaseMeta() != null && tl.releaseMeta().discogsId != null && !tl.releaseMeta().discogsId.isBlank();
    }

    static final int MIN_FILES = 3;
    /** Part des fichiers qui doivent porter le même album (ou le même artiste) pour que le dossier soit un album. */
    static final double MIN_SHARE = 0.6;
    /** Part des fichiers qui doivent être rapprochés de leur piste par le titre (ou l'enregistrement). */
    static final double MIN_TITLE_EVIDENCE = 0.6;
    static final double TITLE_EVIDENCE_SIMILARITY = 0.85;
    static final double ARTIST_SIMILARITY = 0.75;
    static final int COMPILATION_MIN_ARTISTS = 3;
    /** Part des morceaux du dossier que la parution doit expliquer ; les autres suivent la recherche chanson par chanson. */
    static final double MIN_EXPLAINED = 0.7;
    /** Lectures de parution au plus par dossier (chacune coûte ~1 s de quota MusicBrainz). */
    static final int MAX_LOOKUPS = 4;
    /** Note donnée aux identifications par album entier : sous le TOC (95), au-dessus de la cohérence de groupe (92). */
    public static final int SCORE = 93;
    /** Même chose d'après Discogs : un cran en dessous (pas d'identifiant d'enregistrement pour confirmer chaque piste). */
    public static final int DISCOGS_SCORE = 91;

    private static final Set<String> GENERIC_ALBUMS = Set.of(
            "unknown album", "album inconnu", "unbekanntes album", "unbekannter album", "audios", "audio", "sans titre",
            "untitled", "no album", "none", "singles", "single", "musique", "music", "musiques", "divers", "various",
            "various artists", "compilation", "compilations", "greatest hits", "best of", "hits", "downloads", "download",
            "telechargements", "telechargement", "nouveau dossier", "new folder", "playlist", "playlists", "mp3", "flac",
            "itunes", "itunes media", "a classer", "non classe", "non reconnus", "divers artistes");

    // ── 1. Le dossier ressemble-t-il à un album ? ─────────────────────────────────────────────────────

    /**
     * Indices d'album du dossier, ou {@code null} s'il ne ressemble pas à un album (moins de 3 fichiers, albums mélangés,
     * artistes ni uniques ni variés). {@code folderAlbumName} sert de nom d'album seulement quand la plupart des fichiers
     * n'en ont pas (extraction sans tags).
     */
    public static Hints hints(List<Track> group, String folderAlbumName) {
        int n = group.size();
        if (n < MIN_FILES) return null;
        int need = (int) Math.ceil(n * MIN_SHARE);

        Map<String, Integer> albums = new HashMap<>();
        Map<String, String> albumText = new HashMap<>();
        int noAlbum = 0;
        for (Track t : group) {
            String k = AlbumMatcher.norm(t.album());
            if (k.isEmpty() || GENERIC_ALBUMS.contains(k)) { noAlbum++; continue; }
            albums.merge(k, 1, Integer::sum);
            albumText.putIfAbsent(k, t.album().trim());
        }
        String album;
        String topAlbum = top(albums);
        if (topAlbum != null && albums.get(topAlbum) >= need) {
            album = albumText.get(topAlbum);
        } else if (noAlbum >= need && usableAlbum(folderAlbumName)) {
            album = folderAlbumName.trim();
        } else {
            return null;   // albums mélangés : un dossier fourre-tout, pas un album
        }

        // Artiste de l'album d'abord, sinon artiste principal des pistes.
        Map<String, Integer> albumArtists = new HashMap<>(), artists = new HashMap<>();
        Map<String, String> artistText = new HashMap<>();
        int variousTagged = 0;
        for (Track t : group) {
            if (usableArtist(t.albumArtist())) {
                String k = AlbumMatcher.norm(t.albumArtist());
                albumArtists.merge(k, 1, Integer::sum);
                artistText.putIfAbsent(k, t.albumArtist().trim());
            } else if (t.albumArtist() != null && AudioTagAudit.isGenericArtist(t.albumArtist()) && !t.albumArtist().isBlank()) {
                variousTagged++;
            }
            if (usableArtist(t.artist())) {
                String k = AlbumMatcher.norm(leadArtist(t.artist()));
                if (!k.isEmpty()) {
                    artists.merge(k, 1, Integer::sum);
                    artistText.putIfAbsent(k, leadArtist(t.artist()));
                }
            }
        }
        String year = topText(group.stream().map(t -> t.year() == null || t.year().length() < 4 ? "" : t.year().substring(0, 4)).toList());

        // Un artiste majoritaire l'emporte sur quelques « Various Artists » égarés (vu sur un vrai dossier de 2Pac).
        String topAlbumArtist = top(albumArtists);
        if (topAlbumArtist != null && albumArtists.get(topAlbumArtist) >= need)
            return new Hints(album, artistText.get(topAlbumArtist), year, false);
        String topArtist = top(artists);
        if (topArtist != null && artists.get(topArtist) >= need)
            return new Hints(album, artistText.get(topArtist), year, false);
        if (variousTagged >= need || artists.size() >= COMPILATION_MIN_ARTISTS)
            return new Hints(album, "", year, true);
        return null;   // deux artistes sans majorité : on ne devine pas
    }

    // ── 2. Parutions candidates ───────────────────────────────────────────────────────────────────────

    /** Parutions à essayer : celles déjà notées dans les tags, puis celles trouvées par le nom de l'album (et l'artiste),
     *  le nombre de pistes le plus proche du dossier d'abord. Au plus {@link #MAX_LOOKUPS} lectures. */
    public static List<MusicBrainzClient.ReleaseTracklist> candidates(Lookup lk, List<Track> group, Hints h) throws Exception {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Map<String, Integer> tagged = new HashMap<>();
        if (lk.usesTaggedReleaseIds())
            for (Track t : group) if (t.releaseMbid() != null && !t.releaseMbid().isBlank()) tagged.merge(t.releaseMbid().trim(), 1, Integer::sum);
        tagged.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(2)
                .forEach(e -> ids.add(e.getKey()));

        int n = group.size();
        List<MusicBrainzClient.ReleaseRef> refs = new ArrayList<>();
        for (MusicBrainzClient.ReleaseRef ref : lk.searchReleases(h.album(), h.compilation() ? "" : h.artist(), h.year(), 15)) {
            if (ref.releaseMbid() == null || ref.releaseMbid().isBlank() || !albumNameAgrees(h.album(), ref.title())) continue;
            int total = ref.trackCounts() == null ? 0 : ref.trackCounts().stream().mapToInt(Integer::intValue).sum();
            if (total > 0 && total < minExplained(group)) continue;   // trop courte pour expliquer assez de fichiers
            refs.add(ref);
        }
        refs.sort(Comparator.comparingInt(ref -> {     // tri stable : l'ordre MusicBrainz départage
            int total = ref.trackCounts() == null ? 0 : ref.trackCounts().stream().mapToInt(Integer::intValue).sum();
            return total == n ? 0 : total == 0 ? 2 : 1;
        }));
        for (MusicBrainzClient.ReleaseRef ref : refs) ids.add(ref.releaseMbid());

        List<MusicBrainzClient.ReleaseTracklist> out = new ArrayList<>();
        int lookups = 0;
        for (String id : ids) {
            if (lookups++ >= MAX_LOOKUPS) break;
            MusicBrainzClient.ReleaseTracklist tl = lk.lookupRelease(id);
            if (tl != null && tl.tracks() != null && !tl.tracks().isEmpty()) out.add(tl);
        }
        return out;
    }

    // ── 3. Décision ───────────────────────────────────────────────────────────────────────────────────

    /** La meilleure parution qui explique tout le dossier ET passe les garde-fous, ou {@code null}. */
    public static Decision decide(List<Track> group, Hints h, List<MusicBrainzClient.ReleaseTracklist> candidates) {
        List<AlbumMatcher.Item> items = new ArrayList<>();
        Map<String, Track> byId = new LinkedHashMap<>();
        for (Track t : group) {
            items.add(new AlbumMatcher.Item(t.id(), t.title(), t.artist(), t.durationSec(), t.trackNo(), t.discNo(), t.recordingMbid()));
            byId.put(t.id(), t);
        }
        int min = minExplained(group);
        Decision best = null;
        for (AlbumMatcher.Match m : AlbumMatcher.acceptablePartial(items, candidates, h.album(), h.year(), min)) {
            MusicBrainzClient.ReleaseTracklist rel = m.release();
            if (!albumNameAgrees(h.album(), rel.album())) continue;
            if (!artistAgrees(h, rel)) continue;
            if (!enoughTitleEvidence(byId, m.trackByItemId())) continue;
            // Chaque fichier doit être SA piste : ici on lui donne le titre et l'artiste de la piste. Un rapprochement par la
            // seule durée (« Picture Me Rollin' » → « Wonda Why They Call U Bytch », même durée, vu sur un vrai dossier) est
            // écarté : ce fichier-là suivra la recherche chanson par chanson.
            Map<String, MusicBrainzClient.ReleaseTrack> sure = new LinkedHashMap<>();
            for (var e : m.trackByItemId().entrySet())
                if (fileIsThisTrack(byId.get(e.getKey()), e.getValue())) sure.put(e.getKey(), e.getValue());
            if (sure.size() < min) continue;
            if (best == null || sure.size() > best.trackById().size()) best = new Decision(rel, sure);   // à égalité : l'ordre d'AlbumMatcher
        }
        return best;
    }

    /** Le fichier est-il bien cette piste ? Même enregistrement, ou titre semblable ; pour un fichier sans vrai titre
     *  (« Track 01 », « 03 »), bon numéro ET durée à 3 s près. */
    static boolean fileIsThisTrack(Track t, MusicBrainzClient.ReleaseTrack tr) {
        if (t == null || tr == null) return false;
        if (t.recordingMbid() != null && !t.recordingMbid().isBlank() && t.recordingMbid().equalsIgnoreCase(tr.recordingMbid())) return true;
        if (usableTitle(t.title())) return AlbumMatcher.titleSimilarity(t.title(), tr.title()) >= TITLE_EVIDENCE_SIMILARITY;
        int len = tr.lengthMs() / 1000;
        boolean durOk = t.durationSec() > 0 && len > 0 && Math.abs(len - t.durationSec()) <= 3;
        boolean numOk = t.trackNo() > 0 && t.trackNo() == tr.trackNo() && (t.discNo() <= 0 || tr.disc() <= 0 || t.discNo() == tr.disc());
        return durOk && numOk;
    }

    private static final java.util.regex.Pattern PLACEHOLDER_TITLE = java.util.regex.Pattern.compile(
            "(?i)^\\s*(?:track|piste|pista|titre|title|audio\\s*track|cd\\s*track|trk|plage)?[\\s_.-]*\\d{0,3}\\s*$");

    static boolean usableTitle(String title) {
        return title != null && !title.isBlank() && !TagInfo.isGenericIdentityValue(title) && !PLACEHOLDER_TITLE.matcher(title).matches();
    }

    /** Tout l'enchaînement pour un dossier : indices, candidates, décision. */
    public static Outcome identify(Lookup lk, List<Track> group, String folderAlbumName) {
        return identify(lk, null, group, folderAlbumName);
    }

    /** Pareil, avec une 2e source ({@code fallback}, Discogs) essayée seulement si la première ne décide rien. */
    public static Outcome identify(Lookup lk, Lookup fallback, List<Track> group, String folderAlbumName) {
        Hints h = hints(group, folderAlbumName);
        if (h == null) return new Outcome(null, null, "pas un album (albums ou artistes mélangés, ou moins de 3 fichiers)");
        Outcome first = decideWith(lk, group, h, "MusicBrainz");
        if (first.decision() != null || fallback == null) return first;
        Outcome second = decideWith(fallback, group, h, "Discogs");
        if (second.decision() != null) return second;
        return new Outcome(null, h, first.why() + " ; " + second.why());
    }

    private static Outcome decideWith(Lookup lk, List<Track> group, Hints h, String source) {
        List<MusicBrainzClient.ReleaseTracklist> cands;
        try {
            cands = candidates(lk, group, h);
        } catch (Exception e) {
            return new Outcome(null, h, source + " injoignable : " + e.getMessage());
        }
        if (cands.isEmpty()) return new Outcome(null, h, "aucune parution de ce nom sur " + source);
        Decision d = decide(group, h, cands);
        if (d == null) return new Outcome(null, h, cands.size() + " parution(s) " + source + " essayée(s), aucune n'explique assez de fichiers");
        lk.complete(d.release());
        return new Outcome(d, h, "");
    }

    /** Tags complets d'un fichier d'après sa piste dans la parution retenue. */
    public static TagInfo toTagInfo(MusicBrainzClient.ReleaseTracklist tl, MusicBrainzClient.ReleaseTrack tr) {
        TagInfo t = new TagInfo();
        t.artist           = tr.artist();
        t.title            = tr.title();
        t.album            = tl.album();
        t.albumArtist      = tl.albumArtist() == null || tl.albumArtist().isBlank() ? tr.artist() : tl.albumArtist();
        t.albumArtistSort  = nz(tl.albumArtistSort());
        t.artistMbid       = nz(tr.artistMbid());
        t.year             = nz(tl.year());
        t.originalYear     = nz(tl.originalYear());
        t.releaseMbid      = nz(tl.releaseMbid());
        t.releaseGroupMbid = nz(tl.releaseGroupMbid());
        t.recordingMbid    = nz(tr.recordingMbid());
        t.releaseTrackMbid = nz(tr.trackMbid());
        t.discSubtitle     = nz(tr.discTitle());
        t.track            = String.valueOf(tr.trackNo());
        t.trackTotal       = String.valueOf(tr.trackTotal());
        if (tr.disc() > 0) t.discNo = String.valueOf(tr.disc());
        t.country          = nz(tl.country());
        t.barcode          = nz(tl.barcode());
        t.releaseStatus    = nz(tl.releaseStatus());
        t.label            = nz(tl.label());
        t.catalogNo        = nz(tl.catalogNo());
        t.script           = nz(tl.script());
        t.applyReleaseLevelFrom(tl.releaseMeta());
        if (tl.isCompilation()) t.isCompilation = "1";
        if (tr.lengthMs() > 0) t.mbDurationSec = tr.lengthMs() / 1000;
        t.score = SCORE;
        if (fromDiscogs(tl)) {
            TagInfo m = tl.releaseMeta();
            t.discogsId         = nz(m.discogsId);
            t.discogsMasterId   = nz(m.discogsMasterId);
            t.releaseDiscogsUrl = nz(m.releaseDiscogsUrl);
            t.discogsStyles     = nz(m.discogsStyles);
            t.discogsFormat     = nz(m.discogsFormat);
            t.score = DISCOGS_SCORE;
        }
        return t;
    }

    /** Morceaux (copies comprises comme un seul) que la parution doit expliquer : 70 % du dossier, au moins 3. */
    static int minExplained(List<Track> group) {
        List<AlbumMatcher.Item> items = new ArrayList<>();
        for (Track t : group) items.add(new AlbumMatcher.Item(t.id(), t.title(), t.artist(), t.durationSec(), t.trackNo(), t.discNo(), t.recordingMbid()));
        int slots = AlbumMatcher.slotCount(items);
        return Math.max(MIN_FILES, (int) Math.ceil(slots * MIN_EXPLAINED));
    }

    // ── Garde-fous ────────────────────────────────────────────────────────────────────────────────────

    /** Même album, éditions comprises : « 1989 » = « 1989 (Deluxe) », noms de dossier tronqués acceptés. */
    static boolean albumNameAgrees(String hint, String releaseTitle) {
        String a = AlbumMatcher.norm(AlbumMatcher.withoutBrackets(hint)), b = AlbumMatcher.norm(AlbumMatcher.withoutBrackets(releaseTitle));
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b, longer = shorter == a ? b : a;
        if (shorter.length() >= 12 && longer.startsWith(shorter)) return true;
        return TrackMatcher.titleSimilarity(a, b) >= 0.9;
    }

    static boolean artistAgrees(Hints h, MusicBrainzClient.ReleaseTracklist rel) {
        String relArtist = rel.albumArtist() == null ? "" : rel.albumArtist();
        boolean relVarious = relArtist.isBlank() || AudioTagAudit.isGenericArtist(relArtist);
        if (h.compilation()) return rel.isCompilation() || relVarious;
        if (relVarious) return false;     // un album d'UN artiste n'est pas expliqué par une compilation homonyme
        return AudioTagAudit.artistSimilarity(h.artist(), relArtist) >= ARTIST_SIMILARITY;
    }

    static boolean enoughTitleEvidence(Map<String, Track> byId, Map<String, MusicBrainzClient.ReleaseTrack> pairs) {
        int byTitle = 0;
        for (var e : pairs.entrySet()) {
            Track t = byId.get(e.getKey());
            MusicBrainzClient.ReleaseTrack tr = e.getValue();
            if (t == null || tr == null) continue;
            boolean sameRecording = t.recordingMbid() != null && !t.recordingMbid().isBlank()
                    && t.recordingMbid().equalsIgnoreCase(tr.recordingMbid());
            if (sameRecording || AlbumMatcher.titleSimilarity(t.title(), tr.title()) >= TITLE_EVIDENCE_SIMILARITY) byTitle++;
        }
        return byTitle * 10 >= pairs.size() * (int) Math.round(MIN_TITLE_EVIDENCE * 10);
    }

    // ── Petits outils ─────────────────────────────────────────────────────────────────────────────────

    private static boolean usableAlbum(String s) {
        String k = AlbumMatcher.norm(s);
        return k.length() >= 4 && !GENERIC_ALBUMS.contains(k);
    }

    private static boolean usableArtist(String s) {
        return s != null && !s.isBlank() && !AudioTagAudit.isGenericArtist(s) && !TagInfo.isGenericIdentityValue(s);
    }

    /** « X feat. Y », « X (ft. Y) », « X with Y » → « X ». */
    static String leadArtist(String artist) {
        if (artist == null) return "";
        return artist.replaceFirst("(?i)\\s*[(\\[]?\\s*\\b(?:feat\\.?|ft\\.?|featuring|with)\\s+.*$", "").trim();
    }

    private static String top(Map<String, Integer> counts) {
        String best = null; int bc = 0;
        for (var e : counts.entrySet()) if (e.getValue() > bc) { best = e.getKey(); bc = e.getValue(); }
        return best;
    }

    private static String topText(List<String> values) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String v : values) if (v != null && !v.isBlank()) n.merge(v.trim(), 1, Integer::sum);
        String t = top(n);
        return t == null ? "" : t;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
