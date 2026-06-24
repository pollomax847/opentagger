package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class MusicBrainzClient {

    private static final String BASE_URL = "https://musicbrainz.org/ws/2";

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private String lastRawJson = "";

    /** Dernier JSON brut reçu — utilisé par TaggingWorker pour la mise en cache. */
    public String lastRawJson() { return lastRawJson; }

    /** Désérialise une réponse Recording Search MB déjà mise en cache. */
    public List<TagInfo> parseFromCache(String json) {
        try { return parseRecordings(mapper.readTree(json)); }
        catch (Exception e) { return List.of(); }
    }

    /** Désérialise un Recording Lookup MB déjà mis en cache. */
    public TagInfo parseFromCacheLookup(String json) {
        try {
            JsonNode rec = mapper.readTree(json);
            TagInfo info = new TagInfo();
            info.score         = 100;
            info.title         = rec.path("title").asText("").trim();
            info.comment       = rec.path("disambiguation").asText("").trim();
            info.recordingMbid = rec.path("id").asText("").trim();
            JsonNode credits = rec.path("artist-credit");
            if (credits.isArray() && !credits.isEmpty()) {
                JsonNode ac = credits.get(0);
                info.artist     = ac.path("name").asText("").trim();
                info.artistSort = ac.path("artist").path("sort-name").asText("").trim();
                info.artistMbid = ac.path("artist").path("id").asText("").trim();
            }
            JsonNode isrcsCached = rec.path("isrcs");
            if (isrcsCached.isArray() && !isrcsCached.isEmpty()) info.isrc = isrcsCached.get(0).asText("").trim();
            boolean onlyOfficial = Config.get().bool("musicbrainz.only_official", true);
            JsonNode chosen = findBestRelease(rec.path("releases"), onlyOfficial);
            if (chosen == null) chosen = findBestRelease(rec.path("releases"), false);
            if (chosen != null) {
                info.releaseMbid      = chosen.path("id").asText("").trim();
                info.album            = chosen.path("title").asText("").trim();
                info.releaseGroupMbid = chosen.path("release-group").path("id").asText("").trim();
                String date = chosen.path("date").asText("");
                info.year = date.length() >= 4 ? date.substring(0, 4) : date;
                JsonNode rc = chosen.path("artist-credit");
                if (rc.isArray() && !rc.isEmpty()) {
                    info.albumArtist     = rc.get(0).path("name").asText("").trim();
                    info.albumArtistSort = rc.get(0).path("artist").path("sort-name").asText("").trim();
                }
                if (info.albumArtist.isBlank()) info.albumArtist = info.artist;
                info.language = chosen.path("text-representation").path("language").asText("").trim();
                extractSecondaryTypes(chosen, info);
                extractReleaseDetails(chosen, info);
                extractMediaInfo(chosen, info);
            }
            return info;
        } catch (Exception e) { return null; }
    }

    public List<TagInfo> searchRecording(String artist, String title) throws Exception {
        String query = buildQuery(artist, title);
        if (query.isBlank()) return List.of();

        // inc=artist-credits → artiste complet (avec artistSort)
        // inc=releases      → releases dans la réponse
        String url = BASE_URL + "/recording?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&fmt=json&limit=" + Config.get().num("musicbrainz.results_limit", 5)
                + "&inc=releases+artist-credits+isrcs";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return List.of();

        lastRawJson = response.body();
        return parseRecordings(mapper.readTree(lastRawJson));
    }

    private String buildQuery(String artist, String title) {
        List<String> parts = new ArrayList<>();
        if (!title.isBlank())  parts.add("recording:\"" + title.replace("\"","")  + "\"");
        if (!artist.isBlank()) parts.add("artist:\""    + artist.replace("\"","") + "\"");
        return String.join(" AND ", parts);
    }

    private List<TagInfo> parseRecordings(JsonNode root) {
        List<TagInfo> results = new ArrayList<>();
        boolean onlyOfficial = Config.get().bool("musicbrainz.only_official", true);

        for (JsonNode rec : root.path("recordings")) {
            TagInfo info = new TagInfo();
            info.score         = rec.path("score").asInt();
            info.title         = rec.path("title").asText("").trim();
            info.comment       = rec.path("disambiguation").asText("").trim();
            info.recordingMbid = rec.path("id").asText("").trim();
            JsonNode isrcsNode = rec.path("isrcs");
            if (isrcsNode.isArray() && !isrcsNode.isEmpty()) info.isrc = isrcsNode.get(0).asText("").trim();

            // ── Artiste piste ──────────────────────────────────────────────
            JsonNode trackCredits = rec.path("artist-credit");
            if (trackCredits.isArray() && !trackCredits.isEmpty()) {
                JsonNode ac = trackCredits.get(0);
                info.artist     = ac.path("name").asText("").trim();
                info.artistSort = ac.path("artist").path("sort-name").asText("").trim();
                info.artistMbid = ac.path("artist").path("id").asText("").trim();
            }

            // ── Release choisie ────────────────────────────────────────────
            JsonNode releases = rec.path("releases");
            JsonNode chosen   = findBestRelease(releases, onlyOfficial);
            if (chosen == null) chosen = findBestRelease(releases, false);
            if (chosen != null) {
                info.releaseMbid      = chosen.path("id").asText("").trim();
                info.album            = chosen.path("title").asText("").trim();
                info.releaseGroupMbid = chosen.path("release-group").path("id").asText("").trim();

                // Année (MB peut renvoyer "2003-05-01" → on tronque à 4)
                String date = chosen.path("date").asText("");
                info.year = date.length() >= 4 ? date.substring(0, 4) : date;

                // AlbumArtist — présent dans la release si inc=artist-credits
                JsonNode releaseCredits = chosen.path("artist-credit");
                if (releaseCredits.isArray() && !releaseCredits.isEmpty()) {
                    JsonNode rac = releaseCredits.get(0);
                    info.albumArtist     = rac.path("name").asText("").trim();
                    info.albumArtistSort = rac.path("artist").path("sort-name").asText("").trim();
                }
                if (info.albumArtist.isBlank()) info.albumArtist = info.artist;
                if (info.albumArtistSort.isBlank()) info.albumArtistSort = info.artistSort;

                // Release-group secondary types (Compilation, Live, Soundtrack, Greatest Hits)
                extractSecondaryTypes(chosen, info);
                extractReleaseDetails(chosen, info);

                // Media — piste, disc, totaux
                extractMediaInfo(chosen, info);
            }

            results.add(info);
        }
        return results;
    }

    private void extractSecondaryTypes(JsonNode release, TagInfo info) {
        JsonNode secTypes = release.path("release-group").path("secondary-types");
        if (secTypes.isArray()) {
            for (JsonNode t : secTypes) {
                String type = t.asText();
                if ("Compilation".equalsIgnoreCase(type))    info.isCompilation  = "1";
                if ("Live".equalsIgnoreCase(type))            info.isLive         = "1";
                if ("Soundtrack".equalsIgnoreCase(type))      info.isSoundtrack   = "1";
                if ("Greatest Hits".equalsIgnoreCase(type))   info.isGreatestHits = "1";
            }
        }
        if ("Various Artists".equalsIgnoreCase(info.albumArtist)) info.isCompilation = "1";

        // Primary type (Album, Single, EP, Broadcast, Other)
        String ptype = release.path("release-group").path("primary-type").asText("").trim();
        if (!ptype.isBlank()) info.releaseType = ptype;
    }

    /** Extrait script, country depuis la release (disponibles en inline recording lookup). */
    private void extractReleaseDetails(JsonNode release, TagInfo info) {
        String sc = release.path("text-representation").path("script").asText("").trim();
        if (!sc.isBlank() && info.script.isBlank()) info.script = sc;

        String co = release.path("country").asText("").trim();
        if (!co.isBlank() && info.country.isBlank()) info.country = co;
    }

    private JsonNode findBestRelease(JsonNode releases, boolean onlyOfficial) {
        if (!releases.isArray() || releases.isEmpty()) return null;
        // Priorité 1 : Official
        for (JsonNode r : releases) {
            if ("Official".equalsIgnoreCase(r.path("status").asText())) return r;
        }
        if (onlyOfficial) return null;
        // Priorité 2 : tout sauf Bootleg (Promotional, etc.)
        for (JsonNode r : releases) {
            if (!"Bootleg".equalsIgnoreCase(r.path("status").asText())) return r;
        }
        // Priorité 3 : n'importe quelle release en dernier recours
        return releases.get(0);
    }

    private void extractMediaInfo(JsonNode release, TagInfo info) {
        JsonNode media = release.path("media");
        if (!media.isArray() || media.isEmpty()) return;

        // discTotal = nombre de médias (disques)
        int discCount = media.size();
        if (discCount > 1) info.discTotal = String.valueOf(discCount);

        // Trouver le média qui contient la piste cherchée
        for (JsonNode medium : media) {
            JsonNode tracks = medium.path("track");
            if (tracks.isArray() && !tracks.isEmpty()) {
                // trackTotal du medium courant
                int tc = medium.path("track-count").asInt(0);
                if (tc > 0) info.trackTotal = String.valueOf(tc);

                // Numéro de piste
                JsonNode t = tracks.get(0);
                info.track = t.path("number").asText("").trim();

                // Numéro de disc (position du medium)
                int pos = medium.path("position").asInt(0);
                if (pos > 0 && discCount > 1) info.discNo = String.valueOf(pos);
                break;
            }
        }
    }

    // ── Lookup d'une release complète (tracklist) ─────────────────────────────

    public record ReleaseTrack(int disc, int trackNo, int trackTotal, String title,
                               String artist, String recordingMbid) {}

    public record ReleaseTracklist(String releaseMbid, String album, String albumArtist,
                                   String albumArtistSort, String year, String releaseGroupMbid,
                                   boolean isCompilation, List<ReleaseTrack> tracks) {}

    public ReleaseTracklist lookupRelease(String releaseMbid) throws Exception {
        String url = BASE_URL + "/release/" + releaseMbid.trim()
                + "?fmt=json&inc=recordings+artist-credits+release-groups";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        JsonNode root = mapper.readTree(response.body());
        String album     = root.path("title").asText("").trim();
        String date      = root.path("date").asText("");
        String year      = date.length() >= 4 ? date.substring(0, 4) : date;
        String rgMbid    = root.path("release-group").path("id").asText("").trim();

        // AlbumArtist
        String albumArtist     = "";
        String albumArtistSort = "";
        boolean isCompilation  = false;
        JsonNode ac = root.path("artist-credit");
        if (ac.isArray() && !ac.isEmpty()) {
            albumArtist     = ac.get(0).path("name").asText("").trim();
            albumArtistSort = ac.get(0).path("artist").path("sort-name").asText("").trim();
        }
        if ("Various Artists".equalsIgnoreCase(albumArtist)) isCompilation = true;
        JsonNode secTypes = root.path("release-group").path("secondary-types");
        if (secTypes.isArray()) {
            for (JsonNode t : secTypes)
                if ("Compilation".equalsIgnoreCase(t.asText())) { isCompilation = true; break; }
        }

        // Tracks
        List<ReleaseTrack> tracks = new ArrayList<>();
        JsonNode media = root.path("media");
        if (media.isArray()) {
            int discCount = media.size();
            for (JsonNode medium : media) {
                int disc       = medium.path("position").asInt(1);
                int trackTotal = medium.path("track-count").asInt(0);
                for (JsonNode t : medium.path("tracks")) {
                    int    pos      = t.path("position").asInt(0);
                    String tTitle   = t.path("title").asText("").trim();
                    String recMbid  = t.path("recording").path("id").asText("").trim();
                    String tArtist  = "";
                    JsonNode tac = t.path("recording").path("artist-credit");
                    if (tac.isArray() && !tac.isEmpty())
                        tArtist = tac.get(0).path("name").asText("").trim();
                    tracks.add(new ReleaseTrack(discCount > 1 ? disc : 0, pos, trackTotal,
                                               tTitle, tArtist.isBlank() ? albumArtist : tArtist, recMbid));
                }
            }
        }
        return new ReleaseTracklist(releaseMbid, album, albumArtist, albumArtistSort,
                                    year, rgMbid, isCompilation, tracks);
    }

    // ── Lookup d'un recording par MBID ────────────────────────────────────────
    // Utilisé par AcoustIdClient après résolution AcoustID → MBID
    public TagInfo lookupRecording(String mbid) throws Exception {
        String url = BASE_URL + "/recording/" + mbid.trim()
                + "?fmt=json&inc=releases+artist-credits+release-groups+isrcs";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        JsonNode rec = mapper.readTree(response.body());
        TagInfo info = new TagInfo();
        info.score         = 100;
        info.title         = rec.path("title").asText("").trim();
        info.comment       = rec.path("disambiguation").asText("").trim();
        info.recordingMbid = rec.path("id").asText("").trim();

        JsonNode trackCredits = rec.path("artist-credit");
        if (trackCredits.isArray() && !trackCredits.isEmpty()) {
            JsonNode ac = trackCredits.get(0);
            info.artist     = ac.path("name").asText("").trim();
            info.artistSort = ac.path("artist").path("sort-name").asText("").trim();
            info.artistMbid = ac.path("artist").path("id").asText("").trim();
        }

        JsonNode isrcsLookup = rec.path("isrcs");
        if (isrcsLookup.isArray() && !isrcsLookup.isEmpty()) info.isrc = isrcsLookup.get(0).asText("").trim();

        JsonNode releases = rec.path("releases");
        boolean onlyOfficial = Config.get().bool("musicbrainz.only_official", true);
        JsonNode chosen = findBestRelease(releases, onlyOfficial);
        // Fallback : si aucune release "Official" trouvée, accepter n'importe quelle release
        // pour récupérer au moins l'album et l'année
        if (chosen == null) chosen = findBestRelease(releases, false);
        if (chosen != null) {
            info.releaseMbid      = chosen.path("id").asText("").trim();
            info.album            = chosen.path("title").asText("").trim();
            info.releaseGroupMbid = chosen.path("release-group").path("id").asText("").trim();
            String date = chosen.path("date").asText("");
            info.year = date.length() >= 4 ? date.substring(0, 4) : date;

            JsonNode releaseCredits = chosen.path("artist-credit");
            if (releaseCredits.isArray() && !releaseCredits.isEmpty()) {
                JsonNode rac = releaseCredits.get(0);
                info.albumArtist     = rac.path("name").asText("").trim();
                info.albumArtistSort = rac.path("artist").path("sort-name").asText("").trim();
            }
            if (info.albumArtist.isBlank()) info.albumArtist = info.artist;

            info.language = chosen.path("text-representation").path("language").asText("").trim();
            extractSecondaryTypes(chosen, info);
            extractReleaseDetails(chosen, info);
            extractMediaInfo(chosen, info);
        }
        // originalYear : date de première sortie du recording
        String frd = rec.path("first-release-date").asText("").trim();
        if (frd.length() >= 4 && info.originalYear.isBlank()) info.originalYear = frd.substring(0, 4);
        return info;
    }

    /**
     * Recherche l'MBID d'un artiste par nom.
     * Utilisé en fallback quand un titre n'est pas dans MB (démo, bootleg) :
     * on récupère au moins l'artistMbid pour la pochette (FanArt/CAA).
     */
    public String searchArtistMbid(String artistName) throws Exception {
        if (artistName == null || artistName.isBlank()) return "";
        String url = BASE_URL + "/artist?query=artist:"
                + URLEncoder.encode(artistName, StandardCharsets.UTF_8)
                + "&limit=1&fmt=json";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return "";
        JsonNode root = mapper.readTree(resp.body());
        JsonNode artists = root.path("artists");
        if (artists.isArray() && artists.size() > 0) {
            int score = artists.get(0).path("score").asInt(0);
            if (score >= 80) return artists.get(0).path("id").asText("").trim();
        }
        return "";
    }
}
