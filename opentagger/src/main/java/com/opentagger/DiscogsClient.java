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
import java.util.ArrayList;
import java.util.List;

public class DiscogsClient {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DiscogsClient.class.getName());
    private static final String BASE_URL = "https://api.discogs.com";

    private String authHeader() {
        return "Discogs key=" + Config.get().discogsKey() + ", secret=" + Config.get().discogsSecret();
    }

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Enrichit un TagInfo : genres, barcode, catalogue number.
     * Stratégie : artiste + album → artiste + titre → artiste seul.
     */
    public void enrichGenres(TagInfo info, MetadataCache cache) throws Exception {
        if (info.artist.isBlank()) return;
        // Sans clé/secret configurés, la requête échouerait de toute façon (401) — éviter l'appel
        // réseau inutile sur chaque fichier, même pattern que lastfmEnabled()/AcoustID pré-vérifié.
        if (Config.get().discogsKey().isBlank() || Config.get().discogsSecret().isBlank()) return;

        JsonNode hit = null;
        if (!info.album.isBlank())  hit = searchBest(info.artist, info.album, cache);
        if (hit == null && !info.title.isBlank()) hit = searchBest(info.artist, info.title, cache);
        if (hit == null) hit = searchBest(info.artist, "", cache);
        if (hit == null) return;

        // Genres/styles : ordre et limite configurables
        if (info.genre.isBlank()) {
            String source = Config.get().discogsGenreSource();
            int    max    = Config.get().genreMaxCount();
            List<String> genres;
            if ("genre_only".equals(source)) {
                genres = extraireTableau(hit.path("genre"));
            } else if ("genre_then_style".equals(source)) {
                genres = extraireTableau(hit.path("genre"));
                if (genres.isEmpty()) genres = extraireTableau(hit.path("style"));
            } else {
                genres = extraireTableau(hit.path("style"));
                if (genres.isEmpty()) genres = extraireTableau(hit.path("genre"));
            }
            java.util.List<GenreFilter.Candidate> candidates = new ArrayList<>();
            for (String g : genres) candidates.add(new GenreFilter.Candidate(g));
            List<String> filtered = GenreFilter.filter(candidates, max > 0 ? max : Integer.MAX_VALUE);
            if (!filtered.isEmpty()) info.genre = String.join(", ", filtered);
        }

        // Mood depuis les mêmes genre/style Discogs (2026-09-13) — indépendant du bloc ci-dessus
        // (qui s'arrête dès que info.genre est déjà connu, ex. via MusicBrainz plus tôt dans la
        // cascade) : le mood doit rester tenté même quand le genre l'est déjà, tant qu'AUCUNE des
        // trois sources (MB/Discogs/Last.fm, voir MoodClassifier) ne l'a encore trouvé. Aucun appel
        // réseau de plus : "hit" est déjà résolu ci-dessus pour ce même fichier.
        if (info.mood.isBlank()) {
            List<String> allTags = new ArrayList<>(extraireTableau(hit.path("style")));
            allTags.addAll(extraireTableau(hit.path("genre")));
            String mood = MoodClassifier.classify(allTags);
            if (!mood.isBlank()) info.mood = mood;
        }

        // Barcode (tableau dans les résultats Discogs)
        if (info.barcode.isBlank()) {
            JsonNode barcodes = hit.path("barcode");
            if (barcodes.isArray() && !barcodes.isEmpty()) {
                String bc = barcodes.get(0).asText("").trim().replaceAll("[^0-9]", "");
                if (!bc.isBlank()) info.barcode = bc;
            }
        }

        // Catalogue number
        if (info.catalogNo.isBlank()) {
            String catno = hit.path("catno").asText("").trim();
            if (!catno.isBlank() && !"none".equalsIgnoreCase(catno)) info.catalogNo = catno;
        }

        // Pays de sortie
        if (info.country.isBlank()) {
            String co = hit.path("country").asText("").trim();
            if (!co.isBlank()) info.country = co;
        }

        // Année (fallback si MB n'a pas fourni l'année)
        if (info.year.isBlank()) {
            String yr = hit.path("year").asText("").trim();
            if (yr.matches("\\d{4}")) info.year = yr;
        }

        // Identifiant et URL Discogs
        if (info.discogsId.isBlank()) {
            long id = hit.path("id").asLong(0);
            if (id > 0) {
                info.discogsId = String.valueOf(id);
                String uri = hit.path("uri").asText("").trim();
                if (!uri.isBlank())
                    info.releaseDiscogsUrl = "https://www.discogs.com" + uri;
            }
        }
    }

    /**
     * Enrichit un TagInfo avec des informations sur l'ARTISTE lui-même (biographie, vrai nom, URL
     * Discogs) — distinct de {@link #enrichGenres}, qui porte sur la RELEASE. Résout d'abord
     * l'artiste par nom EXACT (voir {@link #findExactArtist}) avant d'appeler sa ressource
     * {@code /artists/{id}}, qui expose {@code profile} (biographie, balisage BBCode Discogs) et
     * {@code realname}.
     */
    public void enrichArtistInfo(TagInfo info, MetadataCache cache) throws Exception {
        if (info.artist.isBlank()) return;
        if (Config.get().discogsKey().isBlank() || Config.get().discogsSecret().isBlank()) return;
        if (!info.artistBio.isBlank() && !info.artistRealName.isBlank() && !info.artistDiscogsUrl.isBlank()) return;

        JsonNode found = findExactArtist(info.artist, cache);
        if (found == null) return;
        long id = found.path("id").asLong(0);
        if (id <= 0) return;

        JsonNode detail = fetchArtistDetail(id, cache);
        if (detail == null) return;

        if (info.artistDiscogsUrl.isBlank()) {
            String uri = detail.path("uri").asText("").trim();
            if (!uri.isBlank()) info.artistDiscogsUrl = uri;
        }
        if (info.artistRealName.isBlank()) {
            String real = detail.path("realname").asText("").trim();
            if (!real.isBlank()) info.artistRealName = real;
        }
        if (info.artistBio.isBlank()) {
            String profile = detail.path("profile").asText("").trim();
            if (!profile.isBlank()) info.artistBio = cleanProfile(profile);
        }
    }

    /**
     * Photo d'artiste de repli quand FanArt.tv n'a rien renvoyé (voir FanArtClient.
     * downloadArtistPhoto, qui exige un MBID artiste — souvent absent pour une identification
     * SongRec/texte seule, voir TagEnrichment.translateArtist()/artistMbid). Résolution par nom
     * EXACT uniquement (voir findExactArtist) : Discogs héberge des homonymes hors-musique (le
     * problème réel qui a motivé ce correctif — "Black Pumas" affichant la photo de Robin Williams
     * dans Navidrome, causé par un provider TIERS sans garde de nom exact) — même rigueur ici pour
     * ne pas reproduire cette classe de bug côté OpenTagger.
     */
    public java.nio.file.Path downloadArtistPhotoFallback(TagInfo info, MetadataCache cache) throws Exception {
        if (info.artist.isBlank()) return null;
        if (Config.get().discogsKey().isBlank() || Config.get().discogsSecret().isBlank()) return null;

        JsonNode found = findExactArtist(info.artist, cache);
        if (found == null) return null;
        long id = found.path("id").asLong(0);
        if (id <= 0) return null;

        JsonNode detail = fetchArtistDetail(id, cache);
        if (detail == null) return null;
        JsonNode images = detail.path("images");
        if (!images.isArray() || images.isEmpty()) return null;

        String url = null;
        for (JsonNode img : images) {
            if ("primary".equals(img.path("type").asText(""))) { url = img.path("uri").asText(null); break; }
        }
        if (url == null) url = images.get(0).path("uri").asText(null);
        return url != null ? ImageDownloader.downloadToTempFile(url, cache) : null;
    }

    /**
     * Résout l'artiste Discogs correspondant EXACTEMENT (nom normalisé, suffixe de désambiguïsation
     * Discogs "(2)" retiré) à {@code artist} — pas de recherche floue/premier-résultat : Discogs
     * indexe des acteurs/personnalités sous des noms homonymes de groupes, un match approximatif
     * reproduirait exactement le bug d'un provider tiers déjà constaté (photo d'artiste erronée).
     * Si plusieurs artistes Discogs partagent EXACTEMENT ce nom (homonymes réels, ex. plusieurs
     * groupes "Nirvana"), retourne null par abstention plutôt que de deviner arbitrairement.
     */
    private JsonNode findExactArtist(String artist, MetadataCache cache) throws Exception {
        String cacheKey = "discogs:artistsearch:" + MetadataCache.queryHash(artist, "");
        String cachedJson = cache.getLookup(cacheKey);
        JsonNode results;
        if (cachedJson != null) {
            results = mapper.readTree(cachedJson).path("results");
        } else {
            String url = BASE_URL + "/database/search?type=artist&per_page=10&q=" + encode(artist);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent",    Config.get().userAgent())
                    .header("Authorization", authHeader())
                    .timeout(HttpTimeouts.apiCall())
                    .GET()
                    .build();
            HttpResponse<String> response = sendWithThrottleRetry(request);
            if (response == null || response.statusCode() != 200) return null;
            cache.putLookup(cacheKey, response.body());
            results = mapper.readTree(response.body()).path("results");
        }
        if (!results.isArray()) return null;

        String norm = normalizeArtistName(artist);
        JsonNode match = null;
        for (JsonNode r : results) {
            if (normalizeArtistName(r.path("title").asText("")).equals(norm)) {
                if (match != null) return null; // homonyme ambigu -> abstention, pas de choix arbitraire
                match = r;
            }
        }
        return match;
    }

    private JsonNode fetchArtistDetail(long id, MetadataCache cache) throws Exception {
        String cacheKey = "discogs:artist:" + id;
        String cachedJson = cache.getLookup(cacheKey);
        if (cachedJson != null) return mapper.readTree(cachedJson);

        String url = BASE_URL + "/artists/" + id;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",    Config.get().userAgent())
                .header("Authorization", authHeader())
                .timeout(HttpTimeouts.apiCall())
                .GET()
                .build();
        HttpResponse<String> response = sendWithThrottleRetry(request);
        if (response == null || response.statusCode() != 200) return null;
        cache.putLookup(cacheKey, response.body());
        return mapper.readTree(response.body());
    }

    private String normalizeArtistName(String s) {
        return s.trim().toLowerCase().replaceAll("\\s*\\(\\d+\\)$", "");
    }

    /** Le champ "profile" Discogs utilise un balisage façon BBCode ([b]/[i]/[a=Nom]/[l=Label]...) —
     *  [a=Nom] et [l=Nom] sont remplacés par leur texte (perdre la mise en forme est acceptable,
     *  perdre le nom lui-même ne l'est pas), le reste du balisage est simplement retiré. */
    private String cleanProfile(String profile) {
        String s = profile.replaceAll("\\[(?:a|l)=([^\\]]*)\\]", "$1");
        s = s.replaceAll("\\[/?[a-zA-Z]+[^\\]]*\\]", "");
        return s.trim();
    }

    private JsonNode searchBest(String artist, String album, MetadataCache cache) throws Exception {
        // Mise en cache façon Picard (un seul cache réseau pour tout, pas seulement MusicBrainz) —
        // avant ce fix, chaque piste d'un même album refaisait cet appel Discogs à l'identique.
        String cacheKey = "discogs:search:" + MetadataCache.queryHash(artist, album);
        String cachedJson = cache.getLookup(cacheKey);
        if (cachedJson != null) {
            JsonNode results = mapper.readTree(cachedJson).path("results");
            return (results.isArray() && !results.isEmpty()) ? results.get(0) : null;
        }

        String url = BASE_URL + "/database/search?type=release&per_page=5"
                + "&artist=" + encode(artist)
                + (album.isBlank() ? "" : "&release_title=" + encode(album));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",    Config.get().userAgent())
                .header("Authorization", authHeader())
                .timeout(HttpTimeouts.apiCall())
                .GET()
                .build();

        HttpResponse<String> response = sendWithThrottleRetry(request);
        if (response == null || response.statusCode() != 200) return null;

        cache.putLookup(cacheKey, response.body());
        JsonNode results = mapper.readTree(response.body()).path("results");
        return (results.isArray() && !results.isEmpty()) ? results.get(0) : null;
    }

    /**
     * Avant ce correctif : un 429 (quota Discogs dépassé — 60 req/min pour une clé authentifiée)
     * ou toute autre erreur HTTP se traduisait en simple `return null`, indiscernable dans les
     * logs d'un "genre non trouvé" légitime. Une seule retentative après le délai indiqué par
     * Retry-After (ou 5s à défaut) suffit ici : contrairement à MusicBrainz, Discogs n'est qu'un
     * fournisseur de genre parmi d'autres (repli Last.fm ensuite), pas la source d'identification
     * principale — pas besoin du backoff exponentiel complet de MusicBrainzClient.getWithRetry().
     */
    private HttpResponse<String> sendWithThrottleRetry(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            long waitMs = 5000;
            try {
                waitMs = Long.parseLong(response.headers().firstValue("Retry-After").orElse("5")) * 1000L;
            } catch (NumberFormatException ignored) {}
            LOG.info("Discogs 429 (quota dépassé) — nouvelle tentative dans " + (waitMs / 1000) + "s");
            Thread.sleep(waitMs);
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200)
            LOG.warning("Discogs HTTP " + response.statusCode() + " : " + request.uri());
        return response;
    }

    private List<String> extraireTableau(JsonNode node) {
        List<String> liste = new ArrayList<>();
        if (node.isArray()) node.forEach(n -> liste.add(n.asText()));
        return liste;
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
