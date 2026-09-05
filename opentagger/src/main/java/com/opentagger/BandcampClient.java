package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Extraction JSON-LD (schema.org MusicAlbum) d'une page album Bandcamp — usage MANUEL uniquement
 * (jamais dans la cascade automatique de TaggingWorker), sur une URL que l'UTILISATEUR trouve
 * lui-même dans son navigateur, pas via recherche automatique.
 *
 * Recherche automatique (bandcamp.com/search) DÉLIBÉRÉMENT ABSENTE de cette classe : testé en
 * direct le 2026-08-16, `/search` sert un défi JavaScript ("Client Challenge", protection Fastly)
 * qui bloque toute requête HTTP simple (jsoup, curl) INCONDITIONNELLEMENT — confirmé identique
 * depuis l'IP de développement ET depuis l'IP résidentielle réelle de l'utilisateur, donc pas un
 * problème de réputation d'IP mais un mur JS infranchissable sans navigateur complet. Les pages
 * ALBUM individuelles, elles, passent normalement (vérifié en direct sur un vrai album Bandcamp,
 * durées/crédits confirmés exacts) — d'où la conception "coller l'URL trouvée soi-même" plutôt que
 * "chercher depuis OpenTagger".
 *
 * Format de durée NON standard constaté en direct (ex. "P00H00M38S", sans le séparateur "T" qu'
 * exige le vrai ISO-8601) — voir parseIso8601Duration(), qui utilise un regex dédié plutôt que
 * java.time.Duration.parse() (échouait silencieusement sur ce format).
 */
public final class BandcampClient {

    private BandcampClient() {}

    private static final ObjectMapper mapper = new ObjectMapper();

    // Rate-limit — même philosophie que MusicBrainzClient.mbRateLimit() : un site tiers scrapé
    // sans API officielle mérite d'autant plus de ne jamais être martelé. 2s, plus prudent que le
    // 1,1s de MusicBrainz (usage manuel/ponctuel ici, pas de contrat d'usage documenté par
    // Bandcamp contrairement à MB).
    private static final AtomicLong LAST_REQUEST_MS = new AtomicLong(0);
    private static final long MIN_INTERVAL_MS = 2000;

    private static synchronized void rateLimit() {
        long wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - LAST_REQUEST_MS.get());
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        LAST_REQUEST_MS.set(System.currentTimeMillis());
    }

    public record BandcampTrack(int position, String title, int durationSec) {}
    public record BandcampAlbum(String title, String artist, String artistUrl, String albumUrl,
                                 String imageUrl, String releaseDate, List<BandcampTrack> tracks,
                                 String credits) {}
    public record BandcampTrackPage(String title, String artist, String artistUrl, String trackUrl,
                                     int durationSec, String album, String imageUrl, String releaseDate) {}

    /**
     * Devine l'URL d'une page piste Bandcamp depuis artiste+titre déjà connus — AUCUNE recherche
     * réelle, juste une approximation de la convention de nommage constatée en direct (2026-08-16) :
     *   - sous-domaine artiste : minuscules, alphanumériques concaténés SANS séparateur
     *     (ex. "Godspeed You! Black Emperor" → "godspeedyoublackemperor", "Iglooghost" →
     *     "iglooghost")
     *   - segment piste : minuscules, mots séparés par un tiret, ponctuation retirée
     *     (ex. "Shrine Hacker (ft. Babii)" → "shrine-hacker-ft-babii", "Clear Tamei" → "clear-tamei")
     * Approximation UNIQUEMENT — beaucoup d'artistes personnalisent leur sous-domaine, la
     * transformation exacte de Bandcamp sur les caractères spéciaux n'est pas garantie identique
     * (voir BandcampClient — même les crochets/parenthèses/accents ne suivent pas toujours une
     * règle simple). L'appelant DOIT vérifier que le résultat de fetchTrack() correspond bien
     * (voir TaggingWorker) avant d'appliquer quoi que ce soit — un mauvais essai échoue
     * silencieusement (404 ou titre/artiste trop différent), jamais de fausse donnée écrite.
     */
    public static String guessTrackUrl(String artist, String title) {
        if (artist == null || artist.isBlank() || title == null || title.isBlank()) return null;
        String artistSlug = slugifyArtist(artist);
        String titleSlug  = slugifyTitle(title);
        if (artistSlug.isBlank() || titleSlug.isBlank()) return null;
        return "https://" + artistSlug + ".bandcamp.com/track/" + titleSlug;
    }

    private static String slugifyArtist(String s) {
        String noDiacritics = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noDiacritics.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String slugifyTitle(String s) {
        String noDiacritics = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noDiacritics.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /**
     * Récupère le JSON-LD (schema.org MusicRecording) d'une page PISTE Bandcamp individuelle —
     * structure non vérifiée en direct au moment de l'écriture (seule la structure MusicAlbum d'une
     * page ALBUM a été confirmée réelle) : à tester contre une vraie page /track/ avant de faire
     * confiance à ce parsing. Renvoie {@code null} si aucun bloc JSON-LD MusicRecording trouvé.
     */
    public static BandcampTrackPage fetchTrack(String trackUrl) throws Exception {
        rateLimit();
        Document doc = Jsoup.connect(trackUrl)
                .userAgent(Config.get().userAgent())
                .timeout((int) HttpTimeouts.apiCall().toMillis())
                .get();

        for (Element script : doc.select("script[type=application/ld+json]")) {
            JsonNode root = mapper.readTree(script.html());
            if ("MusicRecording".equalsIgnoreCase(root.path("@type").asText(""))) {
                JsonNode byArtist = root.path("byArtist");
                JsonNode inAlbum  = root.path("inAlbum");
                return new BandcampTrackPage(
                        root.path("name").asText(""),
                        byArtist.path("name").asText(""),
                        byArtist.path("@id").asText(""),
                        trackUrl,
                        parseIso8601Duration(root.path("duration").asText("")),
                        inAlbum.path("name").asText(""),
                        root.path("image").asText(""),
                        root.path("datePublished").asText(""));
            }
        }
        return null;
    }

    /**
     * Récupère le JSON-LD (schema.org MusicAlbum) d'une page album Bandcamp — voir avertissement
     * de classe pour la fiabilité non vérifiée de ce parsing. Renvoie {@code null} si aucun bloc
     * JSON-LD de type MusicAlbum n'est trouvé (page invalide, structure différente de l'attendu).
     */
    public static BandcampAlbum fetchAlbum(String albumUrl) throws Exception {
        rateLimit();
        Document doc = Jsoup.connect(albumUrl)
                .userAgent(Config.get().userAgent())
                .timeout((int) HttpTimeouts.apiCall().toMillis())
                .get();

        Elements scripts = doc.select("script[type=application/ld+json]");
        for (Element script : scripts) {
            JsonNode root = mapper.readTree(script.html());
            if ("MusicAlbum".equalsIgnoreCase(root.path("@type").asText(""))) {
                return parseAlbum(root, albumUrl);
            }
        }
        return null;
    }

    private static BandcampAlbum parseAlbum(JsonNode root, String albumUrl) {
        JsonNode byArtist = root.path("byArtist");

        List<BandcampTrack> tracks = new ArrayList<>();
        for (JsonNode item : root.path("track").path("itemListElement")) {
            JsonNode rec = item.path("item");
            tracks.add(new BandcampTrack(
                    item.path("position").asInt(0),
                    rec.path("name").asText(""),
                    parseIso8601Duration(rec.path("duration").asText(""))));
        }

        // Crédits : "creditText" — vérifié en direct le 2026-08-16 sur un vrai album (Iglooghost,
        // "Clear Tamei") : "Guitar on track 4 by Christy Carey." "description" est en réalité le
        // texte de présentation de l'album (souvent long, narratif), pas les crédits techniques.
        return new BandcampAlbum(
                root.path("name").asText(""),
                byArtist.path("name").asText(""),
                byArtist.path("@id").asText(""),
                albumUrl,
                root.path("image").asText(""),
                root.path("datePublished").asText(""),
                tracks,
                root.path("creditText").asText(""));
    }

    // Format RÉEL constaté sur une vraie page Bandcamp le 2026-08-16 ("P00H00M38S",
    // "P00H03M32S"…) — PAS le ISO-8601 standard (qui exige un séparateur "T" avant la partie
    // temps, ex. "PT5M1S") : java.time.Duration.parse() échouait silencieusement dessus. Bandcamp
    // semble avoir home-brewé un format qui RESSEMBLE à de l'ISO-8601 sans en être, toujours avec
    // les 3 segments H/M/S présents (zéro-paddés) même quand nuls.
    private static final java.util.regex.Pattern BANDCAMP_DURATION =
            java.util.regex.Pattern.compile("P(\\d+)H(\\d+)M(\\d+)S");

    /** 0 si absente/illisible, jamais d'exception propagée (un champ durée manquant ne doit pas
     *  faire échouer tout l'album). */
    private static int parseIso8601Duration(String s) {
        if (s == null || s.isBlank()) return 0;
        var m = BANDCAMP_DURATION.matcher(s);
        if (!m.matches()) return 0;
        return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
    }
}
