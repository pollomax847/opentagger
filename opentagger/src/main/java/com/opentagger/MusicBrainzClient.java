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
import java.util.concurrent.atomic.AtomicLong;

public class MusicBrainzClient {

    // Miroir MusicBrainz configurable (Config.mbServer()) — était codé en dur sur musicbrainz.org
    // jusqu'ici malgré l'existence de la clé de config musicbrainz.server, jamais réellement lue.
    private static String mbBaseUrl() { return Config.get().mbServer(); }
    // 3 → 1 (2026-07-27) : avec l'intervalle de cadence MB (1,1s, incompressible — voir
    // MB_MIN_INTERVAL_MS, à ne jamais réduire sous peine de bannissement IP) et le timeout par
    // requête (HttpTimeouts.apiCall(), 20s par défaut), un appel qui échoue systématiquement coûtait
    // jusqu'à ~87s (20s + 1+20 + 2+20 + 4+20) avant d'abandonner — constaté en direct : MB répondait
    // lentement (129 timeouts/retry en quelques minutes), rendant la passe album-first (une requête
    // MB par dossier candidat) extrêmement lente sur une bibliothèque avec beaucoup de dossiers
    // compilation. 1 retry ramène le pire cas à ~41s tout en gardant une chance de récupérer un
    // accroc réseau ponctuel.
    private static final int    MAX_RETRIES = 1;

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    // Rate-limit MB centralisé ici — seul point de passage réel (getWithRetry, juste en dessous)
    // de TOUS les appels réseau MB de l'appli, y compris via des instances distinctes (le
    // MusicBrainzClient interne d'AcoustIdClient, une instance par tâche dans un pool de threads
    // type BatchProcessor/TaggingWorker...). Centraliser ici évite qu'un appelant oublie de
    // cadencer ses requêtes — remplace les rate-limits dispersés qui existaient avant côté
    // appelant (un sleep approximatif par fichier dans TaggingWorker, un appel manuel unique dans
    // BatchProcessor.findTags()), et cadence désormais chaque requête réseau réelle individuellement.
    private static final AtomicLong LAST_MB_REQUEST_MS = new AtomicLong(0);
    // Intervalle configurable (Config.mbRateLimitMs(), défaut 1100 = valeur d'origine) — NE JAMAIS
    // descendre en dessous sur la vraie API publique musicbrainz.org, risque de bannissement IP.
    // À réduire (voire 0) UNIQUEMENT si mbServer() pointe vers un miroir tiers avec sa propre
    // capacité (ex. musicbrainz.codeshy.com, même logique que sleepytime=0 dans le mb.py de
    // Headphones pour ce même miroir) — la responsabilité de ne changer les deux ensemble revient
    // à l'utilisateur, voir l'infobulle dans les Préférences.
    private static synchronized void mbRateLimit() {
        long minInterval = Config.get().mbRateLimitMs();
        if (minInterval <= 0) return;
        long now  = System.currentTimeMillis();
        long wait = minInterval - (now - LAST_MB_REQUEST_MS.get());
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        LAST_MB_REQUEST_MS.set(System.currentTimeMillis());
    }

    private String  lastRawJson      = "";
    /** Album préféré fourni par TaggingWorker pour orienter pickBestRelease(). */
    private String  preferredAlbum   = "";
    /** Code HTTP d'un échec DÉFINITIF (301/400/404…) sur le dernier getWithRetry(), 0 sinon —
     *  distingue "le serveur a répondu, mais avec une erreur qui ne se résoudra pas toute seule"
     *  d'un simple épuisement de tentatives 503/429 (transitoire, potentiellement bon au prochain
     *  essai) ou d'une IOException réseau. Voir lookupRecordingReleases()/CompilationClusterWorker
     *  pour l'usage : sans cette distinction, un recording dont le mirror MB renvoie 301 en boucle
     *  n'était jamais mis en cache (lastRawJson restait vide, aucun corps de réponse jamais capturé)
     *  et se re-questionnait indéfiniment à chaque passage de "Grouper par compilations" — 332
     *  requêtes gaspillées vers le même recording constatées en direct sur une seule session
     *  (2026-08-20), le correctif du 2026-08-15 (!raw.isBlank()) ne couvrant que le cas où UN corps
     *  de réponse a effectivement été reçu, jamais celui-ci où getWithRetry() abandonne avant.
     */
    private int     lastHttpErrorStatus = 0;

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
            int lengthMsCached = rec.path("length").asInt(0);
            if (lengthMsCached > 0) info.mbDurationSec = lengthMsCached / 1000;
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

        String url = mbBaseUrl() + "/recording?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&fmt=json&limit=" + Config.get().num("musicbrainz.results_limit", 5)
                + "&inc=releases+release-groups+artist-credits+isrcs+artist-rels+work-rels+labels";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) return List.of();

        lastRawJson = response.body();
        return parseRecordings(mapper.readTree(lastRawJson));
    }

    /** Surcharge avec album pour les fichiers sans artiste mais avec release connue. */
    public List<TagInfo> searchRecording(String artist, String title, String album) throws Exception {
        String query = buildQuery(artist, title, album);
        if (query.isBlank()) return List.of();

        String url = mbBaseUrl() + "/recording?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&fmt=json&limit=" + Config.get().num("musicbrainz.results_limit", 5)
                + "&inc=releases+release-groups+artist-credits+isrcs+artist-rels+work-rels+labels";

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
                // Un guillemet dans la valeur était jusqu'ici SUPPRIMÉ plutôt qu'échappé — les
                // champs de la requête sont eux-mêmes entre guillemets (recording:"..."), donc un
                // titre/artiste contenant un guillemet littéral (ex. une référence "12"" vinyle,
                // ou un nom scrappé avec des guillemets anglais courbes normalisés en droits) était
                // silencieusement altéré au lieu d'être recherché tel quel — \" est la séquence
                // Lucene standard pour un guillemet littéral à l'intérieur d'une phrase.
                .replace("\"", "\\\"");
    }

    /**
     * GET avec retry exponentiel sur 503/429 (MB rate-limit ou surcharge).
     * Comme Picard ratecontrol.py : backoff jusqu'à ~30 secondes.
     */
    private HttpResponse<String> getWithRetry(String url) throws Exception {
        lastHttpErrorStatus = 0;
        int delayMs = 1000;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                System.out.println("  MB retry " + attempt + "/" + MAX_RETRIES + " dans " + (delayMs / 1000) + "s...");
                Thread.sleep(delayMs);
                delayMs = Math.min(delayMs * 2, 30_000);
            }
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET();
            // Authentification HTTP Basic optionnelle — miroir tiers type musicbrainz.codeshy.com
            // (Headphones Indexer VIP), voir Config.mbAuthUser()/mbAuthPass(). Vide par défaut =
            // aucun header, comportement identique à avant sur l'API publique.
            String authUser = Config.get().mbAuthUser();
            String authPass = Config.get().mbAuthPass();
            if (!authUser.isBlank() && !authPass.isBlank()) {
                String basic = java.util.Base64.getEncoder().encodeToString(
                        (authUser + ":" + authPass).getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + basic);
            }
            HttpRequest request = reqBuilder.build();
            mbRateLimit();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException ioe) {
                // Échec au niveau connexion/TLS (ex. "Remote host terminated the handshake") — pas
                // une réponse HTTP, donc jamais couvert par le contrôle status==503/429 ci-dessous ;
                // sans ce catch, un simple accroc réseau transitoire (constaté en direct : la même
                // requête réussit parfois quelques secondes plus tard) faisait échouer la recherche
                // MB définitivement dès le premier essai, sans jamais utiliser le backoff déjà prévu
                // ici pour 503/429. InterruptedException volontairement PAS attrapée ici : une
                // annulation utilisateur en cours de requête ne doit jamais déclencher de retry.
                System.out.println("  MB " + ioe.getClass().getSimpleName() + " (retry) : " + ioe.getMessage());
                continue;
            }
            int status = response.statusCode();
            if (status == 200) return response;
            if (status == 503 || status == 429) continue; // retry
            System.out.println("  MB HTTP " + status + " : " + url);
            lastHttpErrorStatus = status; // erreur définitive (voir son commentaire de champ)
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
            // Durée déclarée par MB pour CET enregistrement (ms) — voir TagInfo.mbDurationSec et
            // TaggingWorker.isDurationMismatch() pour la détection de rip tronqué/mauvais match.
            int lengthMs = rec.path("length").asInt(0);
            if (lengthMs > 0) info.mbDurationSec = lengthMs / 1000;

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

    /**
     * Extrait script, country, barcode, statut, label/catalogue et l'id MusicBrainz de l'artiste
     * de la parution depuis la release (disponibles en inline recording lookup). Avant ce
     * correctif, seuls script/country étaient lus — barcode/label/catalogNo/releaseStatus/
     * albumArtistMbid existaient déjà (barcode/catalogNo) ou ont été ajoutés au modèle
     * (label/releaseStatus/albumArtistMbid) mais restaient systématiquement vides : comparé à
     * Picard sur le même fichier, ces champs étaient présents côté MusicBrainz mais jamais lus ici.
     */
    private void extractReleaseDetails(JsonNode release, TagInfo info) {
        String sc = release.path("text-representation").path("script").asText("").trim();
        if (!sc.isBlank() && info.script.isBlank()) info.script = sc;

        String co = release.path("country").asText("").trim();
        if (!co.isBlank() && info.country.isBlank()) info.country = co;

        String bc = release.path("barcode").asText("").trim();
        if (!bc.isBlank() && info.barcode.isBlank()) info.barcode = bc;

        String status = release.path("status").asText("").trim();
        if (!status.isBlank() && info.releaseStatus.isBlank()) info.releaseStatus = status;

        // label-info : présent seulement si inc=labels a été demandé — un release peut avoir
        // plusieurs labels (co-productions), on garde le premier comme le fait Picard.
        JsonNode labelInfo = release.path("label-info");
        if (labelInfo.isArray() && !labelInfo.isEmpty()) {
            JsonNode first = labelInfo.get(0);
            String labelName = first.path("label").path("name").asText("").trim();
            String catNo     = first.path("catalog-number").asText("").trim();
            if (!labelName.isBlank() && info.label.isBlank())    info.label    = labelName;
            if (!catNo.isBlank()     && info.catalogNo.isBlank()) info.catalogNo = catNo;
        }

        // Id de l'artiste de la PARUTION — distinct de artistMbid (artiste de la PISTE, déjà
        // rempli par extractTrackArtists). Même nœud artist-credit que celui lu par les deux
        // appelants pour albumArtist/albumArtistSort, relu ici pour rester dans cette seule
        // méthode partagée plutôt que dupliquer l'extraction dans les deux call sites.
        JsonNode releaseCredits = release.path("artist-credit");
        if (releaseCredits.isArray() && !releaseCredits.isEmpty() && info.albumArtistMbid.isBlank()) {
            String raid = releaseCredits.get(0).path("artist").path("id").asText("").trim();
            if (!raid.isBlank()) info.albumArtistMbid = raid;
        }
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

                // Support (CD, Digital Media, Vinyl…) — jamais lu avant ce correctif alors que
                // "format" est présent sur ce même nœud medium déjà parcouru pour track/discNo.
                String fmt = medium.path("format").asText("").trim();
                if (!fmt.isBlank() && info.media.isBlank()) info.media = fmt;
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
        String url = mbBaseUrl() + "/release?query="
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

    // ── Identification d'album par TOC (façon "Albunack Disc IDs" de SongKong) ───────────────

    /** Candidat brut renvoyé par lookupByToc() — l'appelant filtre sur trackCount avant d'aller
     *  chercher le détail complet via lookupRelease(releaseMbid). */
    public record DiscIdCandidate(String releaseMbid, String album, int trackCount, int sectors) {}

    private static final int CDDA_SECTORS_PER_SEC = 75; // standard CD-DA, invariant
    private static final int CDDA_LEAD_IN_SECTORS = 150; // 2s, invariant sur tout CD Audio

    /**
     * Identifie un album COMPLET (dossier sans piste manquante ni surnuméraire, ordre connu) via
     * le lookup FLOU documenté par MusicBrainz (paramètre toc=, voir
     * wiki.musicbrainz.org/Disc_ID_Calculation) — sans CD physique, sans reproduire l'algorithme
     * de hash exact (SHA-1 modifié, non trivial bit-à-bit depuis des fichiers déjà encodés) :
     * les offsets sont approximés depuis les DURÉES DE FICHIER (secondes → secteurs CDDA, 75/s),
     * et MusicBrainz fait lui-même la tolérance côté serveur. Testé en direct le 2026-08-16 avec
     * le TOC d'exemple de leur documentation (Nirvana "Nevermind") : la release est bien retrouvée.
     *
     * Inspiré des "Albunack Disc IDs" de SongKong (même principe : identifier un album entier via
     * nombre+durée des pistes plutôt qu'une empreinte audio piste par piste) mais construit sur
     * l'API MusicBrainz publique existante — leur serveur Albunack lui-même est propriétaire, non
     * documenté, non accessible à un tiers.
     *
     * @param trackDurationsSec durées en secondes, DANS L'ORDRE des pistes (position 1..N)
     */
    public List<DiscIdCandidate> lookupByToc(List<Integer> trackDurationsSec) throws Exception {
        // MusicBrainz exige au moins 2 pistes pour un TOC — en dessous, aucune information
        // discriminante par rapport à une simple recherche texte/AcoustID déjà tentée avant ce
        // repli dans le pipeline appelant.
        if (trackDurationsSec == null || trackDurationsSec.size() < 2) return List.of();

        List<Integer> offsets = new ArrayList<>();
        int cursor = CDDA_LEAD_IN_SECTORS;
        for (int durSec : trackDurationsSec) {
            offsets.add(cursor);
            cursor += Math.max(1, durSec) * CDDA_SECTORS_PER_SEC;
        }
        int totalSectors = cursor;

        StringBuilder toc = new StringBuilder();
        toc.append(1).append(' ').append(trackDurationsSec.size()).append(' ').append(totalSectors);
        for (int off : offsets) toc.append(' ').append(off);

        // "-" : discid placeholder volontairement invalide — on ne connaît jamais le vrai hash
        // (jamais de CD physique ici), seul le paramètre toc= compte pour le lookup flou.
        String url = mbBaseUrl() + "/discid/-?toc="
                + URLEncoder.encode(toc.toString(), StandardCharsets.UTF_8)
                + "&fmt=json&cdstubs=no&inc=artist-credits";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) return List.of();

        JsonNode root = mapper.readTree(response.body());
        List<DiscIdCandidate> out = new ArrayList<>();
        for (JsonNode rel : root.path("releases")) {
            String relMbid = rel.path("id").asText("").trim();
            String title   = rel.path("title").asText("").trim();
            for (JsonNode medium : rel.path("media")) {
                int tc = medium.path("track-count").asInt(0);
                for (JsonNode disc : medium.path("discs")) {
                    out.add(new DiscIdCandidate(relMbid, title, tc, disc.path("sectors").asInt(0)));
                }
            }
        }
        return out;
    }

    // ── Lookup d'une release complète (tracklist) ─────────────────────────────

    public record ReleaseTrack(int disc, int trackNo, int trackTotal, String title,
                               String artist, String recordingMbid, int lengthMs, String artistMbid) {}

    public record ReleaseTracklist(String releaseMbid, String album, String albumArtist,
                                   String albumArtistSort, String year, String releaseGroupMbid,
                                   boolean isCompilation, List<ReleaseTrack> tracks,
                                   String country, String barcode, String releaseStatus,
                                   String label, String catalogNo, String script,
                                   String albumArtistMbid, String releaseType, String originalYear) {}

    public ReleaseTracklist lookupRelease(String releaseMbid) throws Exception {
        // +labels : sinon label/catalogNo restent structurellement vides pour tout fichier
        // identifié par ce chemin "album en bloc" (le principal sur un scan par dossier) — même
        // JSON déjà récupéré ici, juste jamais lu pour ces champs avant ce correctif (comparé à
        // Picard sur un même fichier : pays/code-barres/statut/label/catalogue/script/MBID artiste
        // release manquaient systématiquement alors que MusicBrainz les fournit bel et bien).
        String url = mbBaseUrl() + "/release/" + releaseMbid.trim()
                + "?fmt=json&inc=recordings+artist-credits+release-groups+labels";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) return null;

        JsonNode root = mapper.readTree(response.body());
        return parseReleaseTracklist(releaseMbid, root);
    }

    /** Extraction commune à lookupRelease() et parseReleaseFromCache() (même forme de JSON) —
     *  réutilise extractSecondaryTypes()/extractReleaseDetails() (mêmes champs, même logique que
     *  les deux autres chemins d'identification) via un TagInfo jetable plutôt que dupliquer leur
     *  contenu ici. */
    private ReleaseTracklist parseReleaseTracklist(String releaseMbid, JsonNode root) {
        String album     = root.path("title").asText("").trim();
        String date      = root.path("date").asText("");
        String year      = date.length() >= 4 ? date.substring(0, 4) : date;
        String rgMbid    = root.path("release-group").path("id").asText("").trim();

        String albumArtist     = "";
        String albumArtistSort = "";
        JsonNode ac = root.path("artist-credit");
        if (ac.isArray() && !ac.isEmpty()) {
            albumArtist     = ac.get(0).path("name").asText("").trim();
            albumArtistSort = ac.get(0).path("artist").path("sort-name").asText("").trim();
        }

        TagInfo tmp = new TagInfo();
        tmp.albumArtist = albumArtist;
        extractSecondaryTypes(root, tmp); // isCompilation/isLive/isSoundtrack/isGreatestHits + releaseType
        extractReleaseDetails(root, tmp); // script/country/barcode/status/label/catalogNo/albumArtistMbid
        String frd = root.path("release-group").path("first-release-date").asText("").trim();
        if (frd.length() >= 4) tmp.originalYear = frd.substring(0, 4);

        List<ReleaseTrack> tracks = parseTracks(root.path("media"), albumArtist);
        return new ReleaseTracklist(releaseMbid, album, albumArtist, albumArtistSort,
                                    year, rgMbid, "1".equals(tmp.isCompilation), tracks,
                                    tmp.country, tmp.barcode, tmp.releaseStatus,
                                    tmp.label, tmp.catalogNo, tmp.script,
                                    tmp.albumArtistMbid, tmp.releaseType, tmp.originalYear);
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
            String relMbid = root.path("id").asText("").trim();
            String album   = root.path("title").asText("").trim();
            if (relMbid.isBlank() || album.isBlank()) return null;
            return parseReleaseTracklist(relMbid, root);
        } catch (Exception e) { return null; }
    }

    // ── Lookup d'un recording par MBID ────────────────────────────────────────
    // Utilisé par AcoustIdClient après résolution AcoustID → MBID
    public TagInfo lookupRecording(String mbid) throws Exception {
        boolean mbGenres = Config.get().bool("mb.use_genres", false);
        // "labels" RETIRÉ : invalide pour la ressource recording côté API MB — "labels is not a
        // valid inc parameter for the recording resource" (HTTP 400), vérifié en direct, y compris
        // seul ou combiné à d'autres inc. Avant ce correctif, CET APPEL ÉCHOUAIT SYSTÉMATIQUEMENT
        // (100% des appels, silencieusement — getWithRetry() ne retente pas sur 400, renvoie juste
        // null, seul un System.out.println jamais visible en trace la cause) : aucun appelant de
        // lookupRecording() (vérification MBID existant, complétion album/année manquants) n'a
        // jamais reçu la moindre donnée. Contrepartie : label-info reste indisponible via cette
        // méthode (comme lookupRelease(), qui ne l'a jamais eu non plus) — pas un problème nouveau,
        // juste pas rattrapable ici sans casser l'appel entier pour tout le reste.
        String url = mbBaseUrl() + "/recording/" + mbid.trim()
                + "?fmt=json&inc=releases+artist-credits+release-groups+isrcs"
                + (mbGenres ? "+genres" : "")
                + "+artist-rels+recording-rels+work-rels";

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
        int lengthMsLookup = rec.path("length").asInt(0);
        if (lengthMsLookup > 0) info.mbDurationSec = lengthMsLookup / 1000;

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
                                    List<String> secondaryTypes, String country) {}

    /**
     * Comme {@link #lookupRecording}, mais retourne TOUTES les releases où l'enregistrement
     * apparaît (album studio original, compilations diverses…) au lieu d'en réduire une seule via
     * {@code findBestRelease}. Utilisé par {@code ui.CompilationClusterWorker} pour vérifier si un
     * recording déjà tagué existe aussi sur une compilation nommée que l'utilisateur a configurée.
     */
    public List<RecordingRelease> lookupRecordingReleases(String recordingMbid) throws Exception {
        if (recordingMbid == null || recordingMbid.isBlank()) return List.of();
        String url = mbBaseUrl() + "/recording/" + recordingMbid.trim()
                + "?fmt=json&inc=releases+release-groups";

        HttpResponse<String> response = getWithRetry(url);
        if (response == null) {
            // Échec DÉFINITIF (301/400/404…, pas un simple épuisement 503/429 ni une IOException
            // réseau, voir lastHttpErrorStatus) : marqueur JSON minimal plutôt que lastRawJson vide,
            // pour que CompilationClusterWorker.fetchRecordingReleasesCached() puisse quand même
            // mettre ce résultat (négatif) en cache et ne plus jamais requestionner ce recording
            // contre ce mirror — sinon un recording qui échoue en boucle est re-questionné à chaque
            // "Grouper par compilations" auto-déclenché après chaque sauvegarde, indéfiniment.
            lastRawJson = lastHttpErrorStatus != 0 ? "{\"releases\":[]}" : "";
            return List.of();
        }

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
                        secTypes,
                        release.path("country").asText("").trim()));
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
        return String.join(", ", GenreFilter.filter(candidates, Config.get().genreMaxCount()));
    }

    /**
     * Parse les relations MB (artist-rels, recording-rels, work-rels) pour remplir compositeur,
     * chef d'orchestre, producteur, arrangeur, ingénieur du son, parolier, et l'œuvre liée
     * (Work MB — titre + MBID, relation "performance").
     * Défensif : ignore tous les champs manquants/inattendus.
     */
    private void extractRelations(JsonNode relations, TagInfo info) {
        if (!relations.isArray()) return;
        for (JsonNode rel : relations) {
            String type = rel.path("type").asText("").toLowerCase().trim();
            if (type.isBlank()) continue;

            // "performance" a un nœud "work", pas "artist" — traité à part avant le switch
            // ci-dessous (qui suppose toujours un nœud "artist" et sinon ignore la relation).
            if ("performance".equals(type)) {
                JsonNode workNode = rel.path("work");
                String wTitle = workNode.path("title").asText("").trim();
                String wId    = workNode.path("id").asText("").trim();
                if (info.workMbid.isBlank() && !wTitle.isBlank() && !wId.isBlank()) {
                    info.work     = wTitle;
                    info.workMbid = wId;
                }
                continue;
            }

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

    private static final java.util.regex.Pattern CLASSICAL_CATALOG_PATTERN = java.util.regex.Pattern.compile(
        "\\b(BWV|BuxWV|HWV|RV|Wq\\.?|Hob\\.[^,:;\\n]*|K\\.?V?|D\\.)\\s*\\d+[a-zA-Z]?\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CLASSICAL_OPUS_PATTERN = java.util.regex.Pattern.compile(
        "\\bop\\.?\\s*\\d+[a-z]?(?:\\s*,?\\s*n[o°]\\.?\\s*\\d+)?\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Résout Opus/Catalogue (BWV, K., D....)/Mouvement/Œuvre globale à partir du Work MB déjà lié
     * à l'enregistrement ({@code info.workMbid}, rempli par {@link #extractRelations} via la
     * relation "performance" — voir son commentaire). Jusqu'à 2 requêtes MB supplémentaires
     * (cadencées comme tout le reste, voir {@link #getWithRetry}) : l'œuvre elle-même, puis son
     * œuvre parente si c'est un mouvement — la plupart des œuvres classiques hors mouvement (une
     * pièce pour piano seule, par ex.) n'en ont besoin que d'une.
     * <p>
     * Confirmé en direct sur l'API MB réelle (Beethoven Symphonie n°5, Brandebourgeois n°1 BWV
     * 1046) avant d'écrire ce code : la relation recording→work s'appelle "performance" ; un
     * mouvement pointe vers son œuvre parente via une relation "parts" de direction "backward"
     * (avec {@code ordering-key} = numéro du mouvement) ; l'œuvre parente liste ses mouvements via
     * les relations "parts" de direction "forward" (comptées pour le total) ; MusicBrainz n'a
     * PRESQUE JAMAIS d'attribut structuré "Opus number"/"Catalogue number" dans la pratique (les
     * deux œuvres testées n'avaient que l'attribut "Key") — l'opus/catalogue est en réalité
     * quasi-toujours intégré au TITRE de l'œuvre ("Symphony no. 5 in C minor, op. 67",
     * "Brandenburgisches Konzert Nr. 1 F-Dur, BWV 1046"), d'où l'extraction par regex en repli.
     * Limite connue : ne couvre que les catalogues les plus courants (BWV/K.V/D./Hob./RV/Wq/HWV/
     * BuxWV + "op."/"opus" générique) — un catalogue plus rare resterait vide, pas une erreur en
     * soi juste une couverture partielle assumée. {@code period}/{@code section}/{@code part*} ne
     * sont délibérément pas renseignés : rien dans le modèle de données MB ne les fournit de façon
     * fiable (période/époque musicale n'existe pas comme champ MB ; section d'opéra/partie sont un
     * niveau de granularité que "parts" ne distingue pas assez proprement pour être fiable).
     */
    public void resolveClassicalWork(TagInfo info, MetadataCache cache) throws Exception {
        if (info.workMbid.isBlank()) return;
        JsonNode work = fetchWork(info.workMbid, cache);
        if (work == null) return;
        info.isClassical = "1";

        String catalogSourceTitle = work.path("title").asText("").trim();
        JsonNode parentRel = findPartsRelation(work.path("relations"), "backward");
        if (parentRel != null) {
            JsonNode parent = parentRel.path("work");
            String parentTitle = parent.path("title").asText("").trim();
            if (!parentTitle.isBlank()) {
                if (info.overallWork.isBlank()) info.overallWork = parentTitle;
                catalogSourceTitle = parentTitle; // opus/catalogue se lit sur l'œuvre globale

                int orderingKey = parentRel.path("ordering-key").asInt(0);
                if (orderingKey > 0 && info.movementNo.isBlank()) info.movementNo = String.valueOf(orderingKey);

                if (info.titleMovement.isBlank()) {
                    String own = work.path("title").asText("").trim();
                    String prefix = parentTitle + ":";
                    info.titleMovement = own.startsWith(prefix) ? own.substring(prefix.length()).trim() : own;
                }

                String parentId = parent.path("id").asText("").trim();
                if (info.movementTotal.isBlank() && !parentId.isBlank()) {
                    JsonNode parentWork = fetchWork(parentId, cache);
                    if (parentWork != null) {
                        int total = countPartsRelations(parentWork.path("relations"), "forward");
                        if (total > 0) info.movementTotal = String.valueOf(total);
                    }
                }
            }
        }

        extractOpusCatalog(work.path("attributes"), catalogSourceTitle, info);
    }

    private JsonNode fetchWork(String workMbid, MetadataCache cache) throws Exception {
        // Avant ce correctif : seule requête MB de tout le projet à ne jamais passer par
        // MetadataCache (contrairement à searchRecording()/lookupRelease()/lookupRecording(), qui
        // le font tous systématiquement) — pour une bibliothèque classique (l'usage même que cette
        // méthode cible), une symphonie de 4 mouvements refaisait un aller-retour réseau complet
        // vers MusicBrainz pour CHAQUE mouvement (et même deux fois par mouvement : l'œuvre elle-
        // même, puis l'œuvre globale parente si besoin du nombre total de mouvements), sans jamais
        // réutiliser un résultat déjà obtenu.
        String cacheKey = "work:" + workMbid.trim();
        String cached = cache.getLookup(cacheKey);
        if (cached != null) return mapper.readTree(cached);

        String url = mbBaseUrl() + "/work/" + workMbid.trim() + "?fmt=json&inc=work-rels";
        HttpResponse<String> resp = getWithRetry(url);
        if (resp == null) return null;
        cache.putLookup(cacheKey, resp.body());
        return mapper.readTree(resp.body());
    }

    private JsonNode findPartsRelation(JsonNode relations, String direction) {
        if (!relations.isArray()) return null;
        for (JsonNode rel : relations) {
            if ("parts".equals(rel.path("type").asText(""))
                    && direction.equals(rel.path("direction").asText(""))) {
                return rel;
            }
        }
        return null;
    }

    private int countPartsRelations(JsonNode relations, String direction) {
        if (!relations.isArray()) return 0;
        int n = 0;
        for (JsonNode rel : relations) {
            if ("parts".equals(rel.path("type").asText(""))
                    && direction.equals(rel.path("direction").asText(""))) n++;
        }
        return n;
    }

    /** Attribut structuré MB si présent (rare en pratique), sinon extraction par regex du titre —
     *  voir le commentaire de {@link #resolveClassicalWork}. */
    private void extractOpusCatalog(JsonNode attributes, String title, TagInfo info) {
        if (attributes.isArray()) {
            for (JsonNode attr : attributes) {
                String type  = attr.path("type").asText("");
                String value = attr.path("value").asText("").trim();
                if (value.isBlank()) continue;
                if (info.classicalCatalog.isBlank() && "Catalogue number".equalsIgnoreCase(type))
                    info.classicalCatalog = value;
                if (info.opus.isBlank() && "Opus number".equalsIgnoreCase(type))
                    info.opus = value;
            }
        }
        if (title == null || title.isBlank()) return;
        if (info.classicalCatalog.isBlank()) {
            var m = CLASSICAL_CATALOG_PATTERN.matcher(title);
            if (m.find()) info.classicalCatalog = m.group().trim();
        }
        if (info.opus.isBlank()) {
            var m = CLASSICAL_OPUS_PATTERN.matcher(title);
            if (m.find()) info.opus = m.group().trim();
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
        String url = mbBaseUrl() + "/artist/" + artistMbid.trim() + "?fmt=json&inc=aliases";
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
        String url = mbBaseUrl() + "/artist?query=artist:"
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
