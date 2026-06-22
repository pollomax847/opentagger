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
            boolean onlyOfficial = Config.get().bool("musicbrainz.only_official", true);
            JsonNode chosen = findBestRelease(rec.path("releases"), onlyOfficial);
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
                + "&inc=releases+artist-credits";

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

                // isCompilation — release-group secondary types
                JsonNode secTypes = chosen.path("release-group").path("secondary-types");
                if (secTypes.isArray()) {
                    for (JsonNode t : secTypes) {
                        if ("Compilation".equalsIgnoreCase(t.asText())) {
                            info.isCompilation = "1"; break;
                        }
                    }
                }
                // "Various Artists" → compilation
                if ("Various Artists".equalsIgnoreCase(info.albumArtist)) info.isCompilation = "1";

                // Media — piste, disc, totaux
                extractMediaInfo(chosen, info);
            }

            results.add(info);
        }
        return results;
    }

    private JsonNode findBestRelease(JsonNode releases, boolean onlyOfficial) {
        if (!releases.isArray() || releases.isEmpty()) return null;
        for (JsonNode r : releases) {
            if ("Official".equalsIgnoreCase(r.path("status").asText())) return r;
        }
        return onlyOfficial ? null : releases.get(0);
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

    // ── Lookup d'un recording par MBID ────────────────────────────────────────
    // Utilisé par AcoustIdClient après résolution AcoustID → MBID
    public TagInfo lookupRecording(String mbid) throws Exception {
        String url = BASE_URL + "/recording/" + mbid
                + "?fmt=json&inc=releases+artist-credits+release-groups";

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

        JsonNode releases = rec.path("releases");
        boolean onlyOfficial = Config.get().bool("musicbrainz.only_official", true);
        JsonNode chosen = findBestRelease(releases, onlyOfficial);
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

            extractMediaInfo(chosen, info);
        }
        return info;
    }
}
