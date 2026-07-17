package com.opentagger;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

/**
 * Parse un flux RSS podcast et retourne les épisodes.
 *
 * Supporte les namespaces iTunes (itunes:duration, itunes:episode, itunes:season…)
 * et le format Atom/RSS2 standard.
 */
public class PodcastRssClient {

    /** Informations sur le show (channel). */
    public record PodcastFeed(
        String showTitle,
        String author,
        String description,
        String artworkUrl,
        String feedUrl,
        List<PodcastEpisode> episodes
    ) {}

    /** Informations sur un épisode (item). */
    public record PodcastEpisode(
        String title,
        String pubDate,        // ISO-8601 : "2024-03-15"
        int    durationSec,    // durée en secondes (-1 si inconnue)
        int    episodeNumber,  // numéro d'épisode (-1 si inconnu)
        int    season,         // numéro de saison (-1 si inconnu)
        String episodeType,    // "full" / "trailer" / "bonus"
        String description,
        String episodeUrl,
        String author,
        String keywords,       // itunes:keywords, séparés par virgules — "" si absent
        String guid            // identifiant unique RSS — plus fiable que le titre pour dédupliquer
    ) {}

    public static PodcastFeed fetch(String feedUrl) throws Exception {
        String xml = downloadXml(feedUrl);
        return parseXml(xml, feedUrl);
    }

    // ── Download ─────────────────────────────────────────────────────────────

    private static String downloadXml(String url) throws Exception {
        HttpClient client = HttpTimeouts.client();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.largeDownload())
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new Exception("HTTP " + resp.statusCode() + " pour " + url);
        String body = resp.body();

        // Signe classique d'une page HTML reçue au lieu d'un flux XML (URL de la page du podcast
        // au lieu du lien direct vers le flux, redirection vers une page de blocage/CAPTCHA...).
        // Sans ce contrôle, l'utilisateur ne voit qu'une erreur SAX cryptique ("guillemets
        // ouvrants attendus pour l'attribut...") sans comprendre que l'URL elle-même est en cause.
        String start = body.stripLeading();
        if (start.regionMatches(true, 0, "<!doctype html", 0, 14)
                || start.regionMatches(true, 0, "<html", 0, 5)) {
            throw new Exception("L'URL renvoie une page HTML, pas un flux RSS — utilisez le lien "
                + "direct vers le fichier XML du flux (pas la page web du podcast) : " + url);
        }
        return body;
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private static PodcastFeed parseXml(String xml, String feedUrl) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        Document doc = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        Element channel = (Element) doc.getElementsByTagName("channel").item(0);
        if (channel == null) throw new Exception("Aucun élément <channel> dans le flux RSS");

        String showTitle   = text(channel, "title");
        String author      = firstOf(channel, "itunes:author", "managingEditor");
        String description = firstOf(channel, "itunes:summary", "description");
        String artworkUrl  = itunesImageHref(channel);

        List<PodcastEpisode> episodes = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            episodes.add(parseEpisode(item));
        }

        return new PodcastFeed(showTitle, author, description, artworkUrl, feedUrl, episodes);
    }

    private static PodcastEpisode parseEpisode(Element item) {
        String title       = text(item, "title");
        String pubDate     = parsePubDate(text(item, "pubDate"));
        int    duration    = parseDuration(firstOf(item, "itunes:duration", "duration"));
        int    episodeNum  = parseInt(text(item, "itunes:episode"));
        int    season      = parseInt(text(item, "itunes:season"));
        String type        = firstOf(item, "itunes:episodeType", "");
        if (type.isBlank()) type = "full";
        String desc        = firstOf(item, "itunes:summary", "description");
        String epUrl       = text(item, "link");
        String epAuthor    = firstOf(item, "itunes:author", "author");
        String keywords    = text(item, "itunes:keywords");
        String guid        = text(item, "guid");
        return new PodcastEpisode(title, pubDate, duration, episodeNum, season, type, desc, epUrl, epAuthor,
                keywords, guid);
    }

    // ── Helpers XML ──────────────────────────────────────────────────────────

    private static String text(Element parent, String tag) {
        // cherche avec et sans namespace
        NodeList nl = parent.getElementsByTagNameNS("*", localName(tag));
        if (nl.getLength() == 0) nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getParentNode() == parent) {
                String t = n.getTextContent();
                if (t != null) return t.trim();
            }
        }
        return "";
    }

    private static String firstOf(Element parent, String... tags) {
        for (String tag : tags) {
            String v = text(parent, tag);
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static String itunesImageHref(Element channel) {
        NodeList imgs = channel.getElementsByTagNameNS("*", "image");
        for (int i = 0; i < imgs.getLength(); i++) {
            Node n = imgs.item(i);
            if (n instanceof Element e) {
                String href = e.getAttribute("href");
                if (!href.isBlank()) return href;
            }
        }
        // fallback : élément <image><url>
        String url = text(channel, "url");
        return url;
    }

    private static String localName(String tag) {
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    // ── Conversion durée ─────────────────────────────────────────────────────

    static int parseDuration(String s) {
        if (s == null || s.isBlank()) return -1;
        s = s.trim();
        try {
            if (s.contains(":")) {
                String[] parts = s.split(":");
                if (parts.length == 2) {
                    return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                } else if (parts.length == 3) {
                    return Integer.parseInt(parts[0]) * 3600
                         + Integer.parseInt(parts[1]) * 60
                         + Integer.parseInt(parts[2]);
                }
            }
            return (int) Double.parseDouble(s);
        } catch (Exception e) { return -1; }
    }

    // ── Conversion date ───────────────────────────────────────────────────────

    private static final DateTimeFormatter[] DATE_FMTS = {
        DateTimeFormatter.RFC_1123_DATE_TIME,                        // RFC 2822 : "Fri, 15 Mar 2024 10:00:00 +0000"
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z",  Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),    // ISO-8601
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
    };

    static String parsePubDate(String s) {
        if (s == null || s.isBlank()) return "";
        s = s.trim();
        for (DateTimeFormatter fmt : DATE_FMTS) {
            try {
                ZonedDateTime zdt = ZonedDateTime.parse(s, fmt);
                return zdt.toLocalDate().toString(); // "YYYY-MM-DD"
            } catch (Exception ignored) {}
            try {
                LocalDate ld = LocalDate.parse(s, fmt);
                return ld.toString();
            } catch (Exception ignored) {}
        }
        // fallback : retourner les 10 premiers caractères si ça ressemble à une date
        if (s.length() >= 10 && s.charAt(4) == '-') return s.substring(0, 10);
        return "";
    }

    private static int parseInt(String s) {
        if (s == null || s.isBlank()) return -1;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }
}
