package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrait artiste/titre depuis une URL YouTube trouvée dans le tag Commentaire, via l'endpoint
 * public oEmbed (youtube.com/oembed) — gratuit, sans clé API, prévu pour cet usage (contrairement
 * au scraping). Beaucoup de fichiers rippés depuis YouTube (yt-dlp et consorts) gardent l'URL
 * d'origine dans ce tag. Demande utilisateur (2026-08-16), pensé au départ pour les mixs DJ/longs
 * formats (voir TaggingWorker étape 0.8) mais utile pour tout fichier ripé depuis YouTube.
 *
 * Ne remplace jamais une identification déjà confirmée (MB/SongRec/AcoustID/AudD) — n'intervient
 * que dans les replis de dernier recours (voir TaggingWorker), le titre de vidéo YouTube n'étant
 * pas un identifiant fiable au même titre qu'une empreinte audio.
 */
public final class YouTubeOEmbedClient {

    private YouTubeOEmbedClient() {}

    private static final HttpClient http = HttpTimeouts.client();
    private static final ObjectMapper mapper = new ObjectMapper();

    // Couvre youtube.com/watch?v=ID, youtu.be/ID, youtube.com/embed/ID, m.youtube.com/watch?v=ID,
    // avec ou sans paramètres additionnels (&t=, ?si=...) — le groupe capturé s'arrête au premier
    // caractère qui ne fait pas partie d'un ID vidéo YouTube valide (11 caractères alphanumériques
    // + "-"/"_", mais on reste tolérant sur la longueur exacte plutôt que de la figer à 11).
    private static final Pattern YOUTUBE_URL = Pattern.compile(
        "https?://(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([\\w-]+)",
        Pattern.CASE_INSENSITIVE);

    /** Cherche une URL YouTube dans {@code comment} et renvoie l'URL complète (pas juste l'ID) si
     *  trouvée — le endpoint oEmbed veut l'URL entière, pas l'ID seul. */
    public static String extractUrl(String comment) {
        if (comment == null || comment.isBlank()) return null;
        Matcher m = YOUTUBE_URL.matcher(comment);
        return m.find() ? m.group() : null;
    }

    /** Interroge oEmbed pour cette URL — renvoie un TagInfo avec title/artist renseignés, ou null
     *  si l'appel échoue ou que la vidéo n'existe plus (privée/supprimée). Jamais d'exception levée
     *  vers l'appelant : un souci réseau ici ne doit jamais faire échouer tout le taguage. */
    public static TagInfo fetch(String youtubeUrl) {
        if (youtubeUrl == null || youtubeUrl.isBlank()) return null;
        try {
            String oembedUrl = "https://www.youtube.com/oembed?format=json&url="
                    + java.net.URLEncoder.encode(youtubeUrl, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(oembedUrl))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonNode root = mapper.readTree(resp.body());
            String title  = root.path("title").asText("").trim();
            String author = root.path("author_name").asText("").trim();
            if (title.isBlank()) return null;

            TagInfo ti = new TagInfo();
            ti.title       = title;
            // Beaucoup de titres YouTube suivent la convention "Artiste - Titre" — si présent, on
            // préfère cette lecture à author_name (souvent le nom de la CHAÎNE, pas de l'artiste :
            // "Trap Nation", "Majestic Casual"... pas des noms d'artiste exploitables). On ne
            // découpe que sur le PREMIER "-" entouré d'espaces pour éviter de couper un titre
            // contenant lui-même des tirets (remixes, featurings).
            int sep = title.indexOf(" - ");
            if (sep > 0 && sep < title.length() - 3) {
                ti.artist = title.substring(0, sep).trim();
                ti.title  = title.substring(sep + 3).trim();
            } else if (!author.isBlank()) {
                ti.artist = author;
            }
            ti.albumArtist = ti.artist;
            return ti;
        } catch (Exception e) {
            return null;
        }
    }
}
