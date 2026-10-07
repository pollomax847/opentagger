package com.opentagger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Client CDDB pour GnuDB (https://gnudb.org), lecture seule : un CD inséré → UNE requête {@code query}, puis un {@code read} par
 * correspondance choisie. Conforme aux règles communiquées par GnuDB (octobre 2026) :
 * <ul>
 *   <li>HTTP sur {@code gnudb.gnudb.org:80}, protocole niveau 6 ; le {@code hello} est un paramètre de la requête
 *       ({@code hello=user+host+client+version}), pas une commande ;</li>
 *   <li>une application inconnue doit fournir l'e-mail réel du développeur/utilisateur dans le {@code hello} (réglage
 *       {@code gnudb.email}, à défaut {@code opentagger@gnudb.org}) ;</li>
 *   <li>environ UNE connexion à la fois par IP, jamais de rafale : les requêtes sont sérialisées et espacées, et les réponses mises en
 *       cache pour la session — comme un humain qui change de disque, pas un robot qui parcourt la base.</li>
 * </ul>
 */
public class GnuDbClient {

    public static final String DEFAULT_EMAIL = "opentagger@gnudb.org";
    private static final String ENDPOINT = "http://gnudb.gnudb.org/~cddb/cddb.cgi";
    private static final long MIN_GAP_MS = 1500;

    /** Une fiche CDDB (catégorie + identifiant), telle que renvoyée par {@code query}. */
    public record Match(String category, String discId, String title, boolean exact) {}

    /** Le contenu d'une fiche après {@code read}. */
    public record Disc(String category, String discId, String artist, String album, String year, String genre, List<String> tracks) {}

    private static final Object GATE = new Object();           // une seule requête à la fois pour toute l'application
    private static long lastRequestMs = 0;
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private final HttpClient http = HttpTimeouts.client();

    // ── Outils purs (testables sans réseau) ──────────────────────────────────────────────────────────────────────────────

    /** {@code user+host} d'une adresse e-mail ; l'adresse par défaut si elle est vide ou invalide. */
    static String helloIdentity(String email) {
        String e = email == null ? "" : email.trim();
        int at = e.indexOf('@');
        if (at <= 0 || at == e.length() - 1 || e.contains(" ") || e.indexOf('@', at + 1) >= 0) e = DEFAULT_EMAIL;
        return e.substring(0, e.indexOf('@')) + "+" + e.substring(e.indexOf('@') + 1);
    }

    static String helloParam(String email, String version) {
        String v = version == null || version.isBlank() ? "0" : version.trim().replace(' ', '_');
        return helloIdentity(email) + "+OpenTagger+" + v;
    }

    /** Commande {@code cddb query} pour un disque : discid, nombre de pistes, offsets (secteurs), durée en secondes. */
    static String queryCommand(String discId, int[] offsets, int totalSeconds) {
        StringBuilder sb = new StringBuilder("cddb query ").append(discId).append(' ').append(offsets.length);
        for (int o : offsets) sb.append(' ').append(o);
        return sb.append(' ').append(totalSeconds).toString();
    }

    /** Analyse la réponse d'un {@code query} : 200 (une correspondance), 210/211 (liste), 202 (rien) — sinon liste vide. */
    static List<Match> parseQuery(String body) {
        List<Match> out = new ArrayList<>();
        if (body == null) return out;
        String[] lines = body.replace("\r", "").split("\n");
        if (lines.length == 0) return out;
        String first = lines[0].trim();
        if (first.startsWith("200 ")) {
            Match m = parseMatchLine(first.substring(4), true);
            if (m != null) out.add(m);
        } else if (first.startsWith("210 ") || first.startsWith("211 ")) {
            boolean exact = first.startsWith("210 ");
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.equals(".")) break;
                Match m = parseMatchLine(l, exact);
                if (m != null) out.add(m);
            }
        }
        return out;
    }

    private static Match parseMatchLine(String line, boolean exact) {
        String[] p = line.trim().split("\\s+", 3);
        if (p.length < 3) return null;
        return new Match(p[0], p[1], p[2].trim(), exact);
    }

    /** Analyse la réponse d'un {@code read} (fiche xmcd) : 210 puis lignes CLÉ=valeur jusqu'au point final. */
    static Disc parseRead(String category, String discId, String body) {
        if (body == null) return null;
        String[] lines = body.replace("\r", "").split("\n");
        if (lines.length == 0 || !lines[0].trim().startsWith("210")) return null;
        StringBuilder dtitle = new StringBuilder();
        String year = "", genre = "";
        Map<Integer, StringBuilder> tr = new java.util.TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().equals(".")) break;
            int eq = l.indexOf('=');
            if (l.startsWith("#") || eq < 0) continue;
            String k = l.substring(0, eq), v = l.substring(eq + 1);
            if (k.equals("DTITLE")) dtitle.append(v);
            else if (k.equals("DYEAR")) year = v.trim();
            else if (k.equals("DGENRE")) genre = v.trim();
            else if (k.startsWith("TTITLE")) {
                try { tr.computeIfAbsent(Integer.parseInt(k.substring(6)), x -> new StringBuilder()).append(v); }
                catch (NumberFormatException ignored) {}
            }
        }
        String title = dtitle.toString().trim(), artist = title, album = title;
        int sep = title.indexOf(" / ");
        if (sep >= 0) { artist = title.substring(0, sep).trim(); album = title.substring(sep + 3).trim(); }
        List<String> tracks = new ArrayList<>();
        for (StringBuilder sb : tr.values()) tracks.add(sb.toString().trim());
        return new Disc(category, discId, artist, album, year, genre, tracks);
    }

    /** La fiche sous la forme que l'import de CD sait utiliser. {@code null} si le nombre de pistes ne correspond pas au disque inséré. */
    public static MusicBrainzClient.ReleaseTracklist toTracklist(Disc d, CdRipper.Toc toc) {
        if (d == null || toc == null || d.tracks().size() != toc.tracks().size() || d.album().isBlank()) return null;
        String a = d.artist().trim();
        boolean various = a.equalsIgnoreCase("various") || a.equalsIgnoreCase("various artists") || a.equalsIgnoreCase("va");
        List<MusicBrainzClient.ReleaseTrack> tracks = new ArrayList<>();
        for (int i = 0; i < d.tracks().size(); i++) {
            String title = d.tracks().get(i), artist = a;
            int sep = title.indexOf(" / ");
            if (various && sep > 0) { artist = title.substring(0, sep).trim(); title = title.substring(sep + 3).trim(); }
            tracks.add(new MusicBrainzClient.ReleaseTrack(1, i + 1, d.tracks().size(), title, artist, "",
                    toc.tracks().get(i).lengthSectors() * 1000 / 75, "", "", ""));
        }
        String year = d.year() == null ? "" : d.year().trim();
        return new MusicBrainzClient.ReleaseTracklist("", d.album(), a, "", year, "", various, tracks,
                "", "", "", "", "", "", "", "", year, null);
    }

    // ── Réseau ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Les fiches GnuDB pour ce CD (vide si aucune, ou si le service est injoignable). Une seule requête, mise en cache. */
    public List<Match> query(CdRipper.Toc toc) throws IOException, InterruptedException {
        String id = toc.cddbDiscId();
        if (id.isEmpty()) return List.of();
        return parseQuery(call(queryCommand(id, toc.cddbOffsets(), toc.cddbTotalSeconds())));
    }

    /** Le contenu d'une fiche trouvée par {@link #query}. */
    public Disc read(Match m) throws IOException, InterruptedException {
        return parseRead(m.category(), m.discId(), call("cddb read " + m.category() + " " + m.discId()));
    }

    private String call(String cmd) throws IOException, InterruptedException {
        String cached = CACHE.get(cmd);
        if (cached != null) return cached;
        String url = ENDPOINT + "?cmd=" + URLEncoder.encode(cmd, StandardCharsets.UTF_8)
                + "&hello=" + helloParam(Config.get().str("gnudb.email", ""), Config.get().appVersion()) + "&proto=6";
        String body;
        synchronized (GATE) {
            long wait = MIN_GAP_MS - (System.currentTimeMillis() - lastRequestMs);
            if (wait > 0) Thread.sleep(wait);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "OpenTagger/" + Config.get().appVersion())
                        .timeout(java.time.Duration.ofSeconds(20)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) throw new IOException("GnuDB HTTP " + resp.statusCode());
                body = resp.body();
            } finally {
                lastRequestMs = System.currentTimeMillis();
            }
        }
        if (body.startsWith("5")) throw new IOException("GnuDB : " + body.lines().findFirst().orElse("erreur").trim());
        CACHE.put(cmd, body);
        return body;
    }
}
