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
import java.util.concurrent.atomic.AtomicLong;

public class MusicBrainzClient {

    private static final String BASE_URL    = "https://musicbrainz.org/ws/2";
    private static final int    MAX_RETRIES = 3;

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // Rate-limit MB centralisé ici — seul point de passage réel (getWithRetry, juste en dessous)
    // de TOUS les appels réseau MB de l'appli, y compris via des instances distinctes (le
    // MusicBrainzClient interne d'AcoustIdClient, une instance par tâche dans un pool de threads
    // type BatchProcessor/TaggingWorker...). Centraliser ici évite qu'un appelant oublie de
    // cadencer ses requêtes — remplace les rate-limits dispersés qui existaient avant côté
    // appelant (un sleep approximatif par fichier dans TaggingWorker, un appel manuel unique dans
    // BatchProcessor.findTags()), et cadence désormais chaque requête réseau réelle individuellement.
    private static final AtomicLong LAST_MB_REQUEST_MS = new AtomicLong(0);
    private static final long       MB_MIN_INTERVAL_MS = 1100;

    private static synchronized void mbRateLimit() {
        long now  = System.currentTimeMillis();
        long wait = MB_MIN_INTERVAL_MS - (now - LAST_MB_REQUEST_MS.get());
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        LAST_MB_REQUEST_MS.set(System.currentTimeMillis());
    }

    private String  lastRawJson      = "";
    /** Album préféré fourni par TaggingWorker pour orienter pickBestRelease(). */
    private String  preferredAlbum   = "";

    /** Dernier JSON brut reçu — utilisé par TaggingWorker pour la mise en cache. */
    public String lastRawJson() { return lastRawJson; }

    /**
     * Définit l'album préféré avant les appels de recherche — utilisé pour favoriser
     * la release MB dont le nom d'album correspond (ex. nom de dossier iTunes).
     * Passer "" pour désactiver l'indice.
     */
    public void setPreferredAlbum(String album) {
        this.preferredAlbum = album != null ? album.trim() : "";
    }

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
            extractTrackArtists(rec.path("artist-credit"), info);
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
                if (info.albumArtist.isBlank())     info.albumArtist     = info.artist;
                if (info.albumArtistSort.isBlank()) info.albumArtistSort = info.artistSort;
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

        String url = BASE_URL + "/recording?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&fmt=json&limit=" + Config.get().num("musicbrainz.results_limit", 5)
                + "&inc=releases+artist-credits+isrcs+artist-rels";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) return List.of();

        lastRawJson = response.body();
        return parseRecordings(mapper.readTree(lastRawJson));
    }

    /** Surcharge avec album pour les fichiers sans artiste mais avec release connue. */
    public List<TagInfo> searchRecording(String artist, String title, String album) throws Exception {
        String query = buildQuery(artist, title, album);
        if (query.isBlank()) return List.of();

        String url = BASE_URL + "/recording?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&fmt=json&limit=" + Config.get().num("musicbrainz.results_limit", 5)
                + "&inc=releases+artist-credits+isrcs+artist-rels";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) return List.of();

        lastRawJson = response.body();
        return parseRecordings(mapper.readTree(lastRawJson));
    }

    private String buildQuery(String artist, String title) {
        return buildQuery(artist, title, "");
    }

    private String buildQuery(String artist, String title, String album) {
        List<String> parts = new ArrayList<>();
        if (!title.isBlank())  parts.add("recording:\"" + escapeLucene(title)  + "\"");
        if (!artist.isBlank()) parts.add("artist:\""    + escapeLucene(artist) + "\"");
        if (!album.isBlank())  parts.add("release:\""   + escapeLucene(album)  + "\"");
        return String.join(" AND ", parts);
    }

    /** Échappe les caractères spéciaux Lucene (requis par l'API MB). */
    private static String escapeLucene(String s) {
        return s.replace("\\", "\\\\")
                .replace("+",  "\\+")
                .replace("-",  "\\-")
                .replace("&&", "\\&&")
                .replace("||", "\\||")
                .replace("!",  "\\!")
                .replace("(",  "\\(")
                .replace(")",  "\\)")
                .replace("{",  "\\{")
                .replace("}",  "\\}")
                .replace("[",  "\\[")
                .replace("]",  "\\]")
                .replace("^",  "\\^")
                .replace("~",  "\\~")
                .replace("*",  "\\*")
                .replace("?",  "\\?")
                .replace(":",  "\\:")
                .replace("/",  "\\/")
                .replace("\"", "");
    }

    /**
     * GET avec retry exponentiel sur 503/429 (MB rate-limit ou surcharge).
     * Comme Picard ratecontrol.py : backoff jusqu'à ~30 secondes.
     */
    private HttpResponse<String> getWithRetry(String url) throws Exception {
        int delayMs = 1000;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                System.out.println("  MB retry " + attempt + "/" + MAX_RETRIES + " dans " + (delayMs / 1000) + "s...");
                Thread.sleep(delayMs);
                delayMs = Math.min(delayMs * 2, 30_000);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .GET()
                    .build();
            mbRateLimit();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) return response;
            if (status == 503 || status == 429) continue; // retry
            System.out.println("  MB HTTP " + status + " : " + url);
            return null;
        }
        System.out.println("  MB : échec après " + MAX_RETRIES + " tentatives");
        return null;
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

            // ── Artiste(s) piste ───────────────────────────────────────────
            extractTrackArtists(rec.path("artist-credit"), info);

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

            // Année de première sortie (disponible dans les résultats de recherche)
            String frd = rec.path("first-release-date").asText("").trim();
            if (frd.length() >= 4 && info.originalYear.isBlank())
                info.originalYear = frd.substring(0, 4);

            // Relations (compositeur, producteur, parolier…) si inclus dans la réponse
            extractRelations(rec.path("relations"), info);

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
        if (Config.get().vaName().equalsIgnoreCase(info.albumArtist) || "Various Artists".equalsIgnoreCase(info.albumArtist)) info.isCompilation = "1";

        // Primary type (Album, Single, EP, Broadcast, Other)
        String ptype = release.path("release-group").path("primary-type").asText("").trim();
        if (!ptype.isBlank()) info.releaseType = ptype;
    }

    /**
     * Extrait tous les artistes d'un nœud artist-credit MB.
     * Remplit info.artist/artistSort/artistMbid (premier artiste) ET
     * info.artists/artistsSort (tous, séparés par \0).
     */
    private void extractTrackArtists(JsonNode credits, TagInfo info) {
        if (!credits.isArray() || credits.isEmpty()) return;
        boolean standardize = Config.get().standardizeArtists();
        StringBuilder fullName = new StringBuilder(); // nom complet avec joinphrase ("Simon & Garfunkel")
        StringBuilder names    = new StringBuilder(); // noms \0-séparés pour TXXX:ARTISTS
        StringBuilder sorts    = new StringBuilder();
        for (int i = 0; i < credits.size(); i++) {
            JsonNode ac    = credits.get(i);
            // credit name = tel qu'imprimé sur le disque, standard name = nom MB officiel
            String creditName  = ac.path("name").asText("").trim();
            String standardName= ac.path("artist").path("name").asText("").trim();
            String name = (standardize && !standardName.isBlank()) ? standardName : creditName;
            String sort   = ac.path("artist").path("sort-name").asText("").trim();
            String join   = ac.path("joinphrase").asText(""); // ex: " & ", " feat. "
            fullName.append(name).append(join);
            if (i == 0) {
                info.artistSort = sort;
                info.artistMbid = ac.path("artist").path("id").asText("").trim();
            }
            if (!name.isBlank()) { if (names.length() > 0) names.append('\0'); names.append(name); }
            if (!sort.isBlank()) { if (sorts.length() > 0) sorts.append('\0'); sorts.append(sort); }
        }
        info.artist      = fullName.toString().trim();
        info.artists     = names.toString();
        info.artistsSort = sorts.toString();
    }

    /** Extrait script, country depuis la release (disponibles en inline recording lookup). */
    private void extractReleaseDetails(JsonNode release, TagInfo info) {
        String sc = release.path("text-representation").path("script").asText("").trim();
        if (!sc.isBlank() && info.script.isBlank()) info.script = sc;

        String co = release.path("country").asText("").trim();
        if (!co.isBlank() && info.country.isBlank()) info.country = co;
    }

    /**
     * Sélectionne la meilleure release parmi la liste, avec scoring multicritères (comme Picard) :
     *  - Statut Official : +100 pts ; non-Bootleg : +10 pts
     *  - Pays préféré : +(N - rang) × 10 pts  (1er pays préféré = N×10, 2e = (N-1)×10, etc.)
     *  - Format préféré : +(N - rang) × 5 pts  (CD=1er = N×5, etc.)
     */
    // Choix d'échelle pour ramener le score composite pondéré 0.0–1.0 de ReleaseMatcher (pays/
    // format/type de release, façon Picard) dans le même ordre de grandeur que les bonus additifs
    // déjà en place ci-dessous (statut officiel ±100, nom d'album préféré ±300/80) — PAS une
    // prétention de parité numérique exacte avec les scores de Picard (échelles fondamentalement
    // différentes : les leurs sont multipliés par le score de recherche MB, les nôtres s'ajoutent
    // à un score à part).
    private static final int PREFERENCE_SCALE = 100;

    private JsonNode findBestRelease(JsonNode releases, boolean onlyOfficial) {
        if (!releases.isArray() || releases.isEmpty()) return null;

        String[]  preferredCountries  = Config.get().preferredCountries();
        String[]  preferredFormats    = Config.get().preferredFormats();
        String[]  allowedPrimary      = Config.get().allowedPrimaryTypes();
        String[]  excludedSecondary   = Config.get().excludedSecondaryTypes();
        java.util.Map<String, Double> releaseTypeScores = Config.get().releaseTypeScores();

        JsonNode best      = null;
        int      bestScore = Integer.MIN_VALUE;

        for (JsonNode r : releases) {
            String status  = r.path("status").asText("");
            if (onlyOfficial && !"Official".equalsIgnoreCase(status)) continue;

            // Filtre type primaire (Album, Single, EP, Broadcast, Other)
            if (allowedPrimary.length > 0) {
                String ptype = r.path("release-group").path("primary-type").asText("").trim();
                boolean ok = false;
                for (String t : allowedPrimary) if (t.trim().equalsIgnoreCase(ptype)) { ok = true; break; }
                if (!ok) continue;
            }

            // Filtre types secondaires exclus (Compilation, Live, Soundtrack, etc.)
            if (excludedSecondary.length > 0) {
                JsonNode secTypes = r.path("release-group").path("secondary-types");
                boolean excluded = false;
                if (secTypes.isArray()) {
                    outer:
                    for (JsonNode st : secTypes) {
                        String stName = st.asText();
                        for (String ex : excludedSecondary)
                            if (ex.trim().equalsIgnoreCase(stName)) { excluded = true; break outer; }
                    }
                }
                if (excluded) continue;
            }

            int score = 0;
            if ("Official".equalsIgnoreCase(status)) score += 100;
            else if (!"Bootleg".equalsIgnoreCase(status)) score += 10;

            // Préférences pays/format/type de release — score composite pondéré façon Picard
            // (ReleaseMatcher.combinePreferences, poids réels FILE_COMPARISON_WEIGHTS
            // ['preferences']) au lieu de 3 bonus indépendants sans lien entre eux. Le type de
            // release (Album/Compilation/Live...) n'était pas scoré du tout avant ce fix (seulement
            // filtré en tout-ou-rien via allowedPrimary/excludedSecondary ci-dessus) — neutre par
            // défaut (0.5 partout) tant que releases.type_scores n'est pas configuré.
            String country = r.path("country").asText("").trim();
            List<String> mediaFormats = new ArrayList<>();
            JsonNode media = r.path("media");
            if (media.isArray()) for (JsonNode m : media) mediaFormats.add(m.path("format").asText("").trim());
            String primaryType = r.path("release-group").path("primary-type").asText("").trim();
            List<String> secondaryTypesForScore = new ArrayList<>();
            JsonNode secTypesForScore = r.path("release-group").path("secondary-types");
            if (secTypesForScore.isArray()) for (JsonNode st : secTypesForScore) secondaryTypesForScore.add(st.asText());

            double preferenceScore = ReleaseMatcher.combinePreferences(
                    country, preferredCountries, mediaFormats, preferredFormats,
                    primaryType, secondaryTypesForScore, releaseTypeScores);
            score += (int) Math.round(preferenceScore * PREFERENCE_SCALE);

            // Bonus album préféré — fort bonus si le nom d'album MB correspond à l'indice
            // fourni (tag existant ou nom de dossier iTunes). Permet de préférer la release
            // compilation quand le fichier vient d'un dossier "100 Club Hits Edition 2022".
            if (!preferredAlbum.isBlank()) {
                String mbAlbum = r.path("title").asText("").trim();
                if (!mbAlbum.isBlank()) {
                    String normMb   = normalizeAlbumName(mbAlbum);
                    String normPref = normalizeAlbumName(preferredAlbum);
                    if (normMb.equals(normPref))              score += 300; // correspondance exacte
                    else if (normMb.contains(normPref)
                          || normPref.contains(normMb))       score += 80;  // correspondance partielle
                }
            }

            if (best == null || score > bestScore) { bestScore = score; best = r; }
        }
        if (best != null) return best;
        if (onlyOfficial) return null;
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

    private void fillTrackPositionFromRelease(String recordingMbid, String releaseMbid, TagInfo info) {
        try {
            ReleaseTracklist tl = lookupRelease(releaseMbid);
            if (tl == null || tl.tracks().isEmpty()) return;
            long discCount = tl.tracks().stream().mapToInt(ReleaseTrack::disc).distinct().filter(d -> d > 0).count();
            for (ReleaseTrack t : tl.tracks()) {
                if (recordingMbid.equals(t.recordingMbid())) {
                    info.track      = String.valueOf(t.trackNo());
                    info.trackTotal = String.valueOf(t.trackTotal());
                    if (t.disc() > 0) {
                        info.discNo    = String.valueOf(t.disc());
                        info.discTotal = String.valueOf(discCount);
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Recherche d'une release par nom d'album ───────────────────────────────

    /**
     * Cherche la meilleure release MB pour un nom d'album donné.
     * @param albumName  nom de l'album (dossier ou tag existant)
     * @param artistHint artiste indicatif — ignoré pour Various Artists
     * @return releaseMbid si score ≥ 70, sinon null
     */
    public String searchBestRelease(String albumName, String artistHint) throws Exception {
        if (albumName == null || albumName.isBlank()) return null;
        StringBuilder q = new StringBuilder("release:\"").append(escapeLucene(albumName)).append("\"");
        if (artistHint != null && !artistHint.isBlank()
                && !"Various Artists".equalsIgnoreCase(artistHint)
                && !Config.get().vaName().equalsIgnoreCase(artistHint)) {
            q.append(" AND artist:\"").append(escapeLucene(artistHint)).append("\"");
        }
        String url = BASE_URL + "/release?query="
                + URLEncoder.encode(q.toString(), StandardCharsets.UTF_8)
                + "&limit=5&fmt=json";
        HttpResponse<String> resp = getWithRetry(url);
        if (resp == null) return null;
        JsonNode root  = mapper.readTree(resp.body());
        JsonNode list  = root.path("releases");
        if (!list.isArray() || list.isEmpty()) return null;
        JsonNode best  = list.get(0);
        int      score = best.path("score").asInt(0);
        if (score < 70) return null;
        return best.path("id").asText("").trim();
    }

    // ── Lookup d'une release complète (tracklist) ─────────────────────────────

    public record ReleaseTrack(int disc, int trackNo, int trackTotal, String title,
                               String artist, String recordingMbid, int lengthMs, String artistMbid) {}

    public record ReleaseTracklist(String releaseMbid, String album, String albumArtist,
                                   String albumArtistSort, String year, String releaseGroupMbid,
                                   boolean isCompilation, List<ReleaseTrack> tracks) {}

    public ReleaseTracklist lookupRelease(String releaseMbid) throws Exception {
        String url = BASE_URL + "/release/" + releaseMbid.trim()
                + "?fmt=json&inc=recordings+artist-credits+release-groups";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) return null;

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
        if (Config.get().vaName().equalsIgnoreCase(albumArtist) || "Various Artists".equalsIgnoreCase(albumArtist)) isCompilation = true;
        JsonNode secTypes = root.path("release-group").path("secondary-types");
        if (secTypes.isArray()) {
            for (JsonNode t : secTypes)
                if ("Compilation".equalsIgnoreCase(t.asText())) { isCompilation = true; break; }
        }

        List<ReleaseTrack> tracks = parseTracks(root.path("media"), albumArtist);
        return new ReleaseTracklist(releaseMbid, album, albumArtist, albumArtistSort,
                                    year, rgMbid, isCompilation, tracks);
    }

    /** Parse le tableau "media" (disques + pistes) d'une réponse MB release, avec durée (lengthMs). */
    private List<ReleaseTrack> parseTracks(JsonNode media, String albumArtist) {
        List<ReleaseTrack> tracks = new ArrayList<>();
        if (media.isArray()) {
            int discCount = media.size();
            for (JsonNode medium : media) {
                int disc       = medium.path("position").asInt(1);
                int trackTotal = medium.path("track-count").asInt(0);
                for (JsonNode t : medium.path("tracks")) {
                    int    pos      = t.path("position").asInt(0);
                    String tTitle   = t.path("title").asText("").trim();
                    String recMbid  = t.path("recording").path("id").asText("").trim();
                    int    lengthMs = t.path("length").asInt(0);
                    String tArtist     = "";
                    String tArtistMbid = "";
                    JsonNode tac = t.path("recording").path("artist-credit");
                    if (tac.isArray() && !tac.isEmpty()) {
                        tArtist     = tac.get(0).path("name").asText("").trim();
                        tArtistMbid = tac.get(0).path("artist").path("id").asText("").trim();
                    }
                    tracks.add(new ReleaseTrack(discCount > 1 ? disc : 0, pos, trackTotal,
                                               tTitle, tArtist.isBlank() ? albumArtist : tArtist, recMbid, lengthMs,
                                               tArtistMbid));
                }
            }
        }
        return tracks;
    }

    /** Désérialise un ReleaseTracklist depuis un JSON mis en cache (même format que lookupRelease). */
    public ReleaseTracklist parseReleaseFromCache(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String relMbid   = root.path("id").asText("").trim();
            String album     = root.path("title").asText("").trim();
            String date      = root.path("date").asText("");
            String year      = date.length() >= 4 ? date.substring(0, 4) : date;
            String rgMbid    = root.path("release-group").path("id").asText("").trim();
            String albumArtist = "", albumArtistSort = "";
            boolean isCompilation = false;
            JsonNode ac = root.path("artist-credit");
            if (ac.isArray() && !ac.isEmpty()) {
                albumArtist     = ac.get(0).path("name").asText("").trim();
                albumArtistSort = ac.get(0).path("artist").path("sort-name").asText("").trim();
            }
            if (Config.get().vaName().equalsIgnoreCase(albumArtist) || "Various Artists".equalsIgnoreCase(albumArtist)) isCompilation = true;
            JsonNode secTypes = root.path("release-group").path("secondary-types");
            if (secTypes.isArray())
                for (JsonNode t : secTypes)
                    if ("Compilation".equalsIgnoreCase(t.asText())) { isCompilation = true; break; }
            List<ReleaseTrack> tracks = parseTracks(root.path("media"), albumArtist);
            if (relMbid.isBlank() || album.isBlank()) return null;
            return new ReleaseTracklist(relMbid, album, albumArtist, albumArtistSort,
                                        year, rgMbid, isCompilation, tracks);
        } catch (Exception e) { return null; }
    }

    // ── Lookup d'un recording par MBID ────────────────────────────────────────
    // Utilisé par AcoustIdClient après résolution AcoustID → MBID
    public TagInfo lookupRecording(String mbid) throws Exception {
        boolean mbGenres = Config.get().bool("mb.use_genres", false);
        String url = BASE_URL + "/recording/" + mbid.trim()
                + "?fmt=json&inc=releases+artist-credits+release-groups+isrcs"
                + (mbGenres ? "+genres" : "")
                + "+artist-rels+recording-rels";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) return null;

        JsonNode rec = mapper.readTree(response.body());
        TagInfo info = new TagInfo();
        info.score         = 100;
        info.title         = rec.path("title").asText("").trim();
        info.comment       = rec.path("disambiguation").asText("").trim();
        info.recordingMbid = rec.path("id").asText("").trim();

        extractTrackArtists(rec.path("artist-credit"), info);

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

            // MB ne retourne pas media.track dans /recording?inc=releases (contrairement à la recherche).
            // Si track est toujours vide, chercher la position exacte via lookupRelease.
            if (info.track.isBlank() && !info.releaseMbid.isBlank()) {
                fillTrackPositionFromRelease(info.recordingMbid, info.releaseMbid, info);
            }
        }
        // originalYear : date de première sortie du recording
        String frd = rec.path("first-release-date").asText("").trim();
        if (frd.length() >= 4 && info.originalYear.isBlank()) info.originalYear = frd.substring(0, 4);

        // Genres MB (folksonomy) — uniquement si mb.use_genres=true
        if (Config.get().bool("mb.use_genres", false)) {
            String mbGenre = parseMbGenres(rec.path("genres"));
            if (!mbGenre.isBlank()) info.genre = mbGenre;
        }

        // Relations ARs (compositeur, chef d'orchestre, producteur…)
        extractRelations(rec.path("relations"), info);

        return info;
    }

    /** Une release parmi celles où un recording apparaît — voir {@link #lookupRecordingReleases}.
     *  Contrairement à {@link #lookupRecording}, qui n'en retient qu'une seule (via
     *  {@code findBestRelease}), ceci expose TOUTES les releases pour laisser l'appelant décider
     *  (ex. {@code ui.CompilationClusterWorker} cherche celle qui correspond à une série de
     *  compilation configurée par l'utilisateur). */
    public record RecordingRelease(String releaseId, String releaseTitle, String releaseGroupTitle,
                                    List<String> secondaryTypes) {}

    /**
     * Comme {@link #lookupRecording}, mais retourne TOUTES les releases où l'enregistrement
     * apparaît (album studio original, compilations diverses…) au lieu d'en réduire une seule via
     * {@code findBestRelease}. Utilisé par {@code ui.CompilationClusterWorker} pour vérifier si un
     * recording déjà tagué existe aussi sur une compilation nommée que l'utilisateur a configurée.
     */
    public List<RecordingRelease> lookupRecordingReleases(String recordingMbid) throws Exception {
        if (recordingMbid == null || recordingMbid.isBlank()) return List.of();
        String url = BASE_URL + "/recording/" + recordingMbid.trim()
                + "?fmt=json&inc=releases+release-groups";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) return List.of();

        lastRawJson = response.body();
        return parseRecordingReleasesFromCache(lastRawJson);
    }

    /** Reparse une réponse déjà mise en cache par {@link #lookupRecordingReleases} (même motif que
     *  {@link #parseReleaseFromCache}) — évite un aller-retour réseau (et le rate-limit MB) pour un
     *  recording déjà vérifié lors d'une exécution précédente de l'outil. */
    public List<RecordingRelease> parseRecordingReleasesFromCache(String json) {
        try {
            JsonNode rec = mapper.readTree(json);
            JsonNode releases = rec.path("releases");
            if (!releases.isArray()) return List.of();

            List<RecordingRelease> result = new ArrayList<>();
            for (JsonNode release : releases) {
                JsonNode releaseGroup = release.path("release-group");
                List<String> secTypes = new ArrayList<>();
                for (JsonNode t : releaseGroup.path("secondary-types")) secTypes.add(t.asText());
                result.add(new RecordingRelease(
                        release.path("id").asText("").trim(),
                        release.path("title").asText("").trim(),
                        releaseGroup.path("title").asText("").trim(),
                        secTypes));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Parse les genres folksonomy MB. Filtre par vote minimum et liste noire.
     * Format API : [{"name":"rock","count":15,"disambiguation":""},…]
     */
    private String parseMbGenres(JsonNode genres) {
        if (!genres.isArray() || genres.isEmpty()) return "";
        java.util.List<GenreFilter.Candidate> candidates = new java.util.ArrayList<>();
        for (JsonNode g : genres) {
            String name  = g.path("name").asText("").trim();
            int    count = g.path("count").asInt(0);
            candidates.add(new GenreFilter.Candidate(name, count));
        }
        return String.join(", ", GenreFilter.filter(candidates, Config.get().mbMaxGenres()));
    }

    /**
     * Parse les relations MB (artist-rels, recording-rels) pour remplir compositeur,
     * chef d'orchestre, producteur, arrangeur, ingénieur du son, parolier.
     * Défensif : ignore tous les champs manquants/inattendus.
     */
    private void extractRelations(JsonNode relations, TagInfo info) {
        if (!relations.isArray()) return;
        for (JsonNode rel : relations) {
            String type = rel.path("type").asText("").toLowerCase().trim();
            if (type.isBlank()) continue;
            // Les relations artist-rels ont un nœud "artist", les recording-rels ont "recording"
            JsonNode artistNode = rel.path("artist");
            if (artistNode.isMissingNode()) continue;
            String name     = artistNode.path("name").asText("").trim();
            String sortName = artistNode.path("sort-name").asText("").trim();
            if (name.isBlank()) continue;

            switch (type) {
                case "composer"                    -> { if (info.composer.isBlank())   { info.composer    = name; info.composerSort    = sortName; } }
                case "lyricist"                    -> { if (info.lyricist.isBlank())   { info.lyricist    = name; info.lyricistSort    = sortName; } }
                case "arranger"                    -> { if (info.arranger.isBlank())   { info.arranger    = name; info.arrangerSort    = sortName; } }
                case "conductor"                   -> { if (info.conductor.isBlank())  { info.conductor   = name; info.conductorSort   = sortName; } }
                case "producer"                    -> { if (info.producer.isBlank())   { info.producer    = name; info.producerSort    = sortName; } }
                case "mix"                         -> { if (info.mixer.isBlank())      { info.mixer       = name; info.mixerSort       = sortName; } }
                case "dj-mix"                      -> { if (info.djMixer.isBlank())    info.djMixer       = name; }
                case "performing orchestra"        -> { if (info.orchestra.isBlank())  { info.orchestra   = name; info.orchestraSort   = sortName; } }
                case "ensemble"                    -> { if (info.ensemble.isBlank())   { info.ensemble    = name; info.ensembleSort    = sortName; } }
                case "choir", "chorus master"      -> { if (info.choir.isBlank())      { info.choir       = name; info.choirSort       = sortName; } }
                case "engineer", "recording", "mastering", "balance"
                                                   -> { if (info.engineer.isBlank())   info.engineer      = name; }
                case "performer", "instrument", "vocal"
                                                   -> { /* géré par artist-credits */ }
                default -> {}
            }
        }
    }

    /**
     * Cherche un alias latin pour un artiste MB (translittération).
     * Essaie chaque locale de {@code preferredLocales} dans l'ordre, retourne le premier alias
     * trouvé, ou "" si aucune ne donne de résultat. Exemple : artiste MB "宇多田ヒカル" → alias
     * "Hikaru Utada" (locale=en).
     *
     * Repli sur "en" (si absente de la liste) puis sur n'importe quel alias déjà en écriture
     * latine si aucune locale demandée n'a rien donné — trouvé en vérifiant en direct sur
     * MusicBrainz (2026-07-10, artistes réels "蔡依林"/Jolin Tsai et "Ханна"/Hanna) que la
     * quasi-totalité des alias de romanisation sont tagués locale=en, quelle que soit la langue
     * de l'utilisateur — un alias "fr" (ou toute autre langue que l'anglais) spécifique existe
     * rarement. Sans ce repli, la translittération échouait silencieusement pour quasiment tout
     * le monde qui n'avait pas explicitement choisi "en", même avec la fonctionnalité activée et
     * un artistMbid valide — confirmé : zéro traduction réussie sur toute une session de plusieurs
     * jours avec locale=fr seule, malgré des dizaines d'artistes non-latins rencontrés qui ONT un
     * alias "en" utile. L'utilisateur peut maintenant lister plusieurs locales par ordre de
     * priorité plutôt que de dépendre uniquement de ce repli automatique.
     */
    public String lookupArtistAlias(String artistMbid, String[] preferredLocales) throws Exception {
        if (artistMbid == null || artistMbid.isBlank()) return "";
        String url = BASE_URL + "/artist/" + artistMbid.trim() + "?fmt=json&inc=aliases";
        HttpResponse<String> resp = getWithRetry(url);
        if (resp == null) return "";
        JsonNode root = mapper.readTree(resp.body());
        JsonNode aliases = root.path("aliases");
        if (!aliases.isArray() || aliases.isEmpty()) return "";

        java.util.List<String> locales = (preferredLocales == null || preferredLocales.length == 0)
                ? java.util.List.of("en") : java.util.Arrays.asList(preferredLocales);

        boolean hasEnglish = false;
        for (String locale : locales) {
            String prefix = locale == null ? "" : locale.trim().toLowerCase();
            if (prefix.isBlank()) continue;
            if ("en".equals(prefix)) hasEnglish = true;
            String result = findAliasByLocale(aliases, prefix);
            if (!result.isBlank()) return result;
        }

        if (!hasEnglish) {
            String result = findAliasByLocale(aliases, "en");
            if (!result.isBlank()) return result;
        }

        // Dernier repli : n'importe quel alias "Artist name" déjà en écriture latine, peu importe
        // son locale déclaré (certains alias utiles n'ont carrément pas de locale renseigné).
        for (JsonNode alias : aliases) {
            String type = alias.path("type").asText("");
            String name = alias.path("name").asText("").trim();
            if (!name.isBlank() && "Artist name".equalsIgnoreCase(type) && !TagEnrichment.hasNonLatinChars(name)) {
                return name;
            }
        }
        return "";
    }

    private static String findAliasByLocale(JsonNode aliases, String prefix) {
        // 1er passage : alias dont le locale commence par le préféré et dont le type est "Artist name"
        for (JsonNode alias : aliases) {
            String locale = alias.path("locale").asText("").toLowerCase();
            String type   = alias.path("type").asText("");
            String name   = alias.path("name").asText("").trim();
            if (!name.isBlank() && locale.startsWith(prefix)
                    && ("Artist name".equalsIgnoreCase(type) || type.isBlank())) {
                return name;
            }
        }
        // 2e passage : n'importe quel alias avec le bon locale
        for (JsonNode alias : aliases) {
            String locale = alias.path("locale").asText("").toLowerCase();
            String name   = alias.path("name").asText("").trim();
            if (!name.isBlank() && locale.startsWith(prefix)) return name;
        }
        return "";
    }

    /**
     * Recherche l'MBID d'un artiste par nom.
     * Utilisé en fallback quand un titre n'est pas dans MB (démo, bootleg) :
     * on récupère au moins l'artistMbid pour la pochette (FanArt/CAA).
     */
    /** Normalise un nom d'album pour comparaison : minuscules, sans ponctuation, sans espaces superflus. */
    private static String normalizeAlbumName(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[^a-z0-9\\u00e0-\\u024f]", " ") // garde lettres + diacritiques
                .replaceAll("\\s+", " ")
                .trim();
    }

    public String searchArtistMbid(String artistName) throws Exception {
        if (artistName == null || artistName.isBlank()) return "";
        String url = BASE_URL + "/artist?query=artist:"
                + URLEncoder.encode(escapeLucene(artistName), StandardCharsets.UTF_8)
                + "&limit=1&fmt=json";
        HttpResponse<String> resp = getWithRetry(url);
        if (resp == null) return "";
        JsonNode root = mapper.readTree(resp.body());
        JsonNode artists = root.path("artists");
        if (artists.isArray() && artists.size() > 0) {
            int score = artists.get(0).path("score").asInt(0);
            if (score >= 80) return artists.get(0).path("id").asText("").trim();
        }
        return "";
    }
}
