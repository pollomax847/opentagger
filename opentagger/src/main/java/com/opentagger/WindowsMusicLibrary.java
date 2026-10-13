package com.opentagger;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Dossiers de la bibliothèque « Musique » de Windows (2026-10-13, demande utilisateur : « sur Windows, tous les dossiers
 * musique qu'on inclut dans la bibliothèque »). Windows ne range pas une bibliothèque dans un dossier : c'est une liste de
 * dossiers décrite dans {@code %APPDATA%\Microsoft\Windows\Libraries\*.library-ms} (XML). Jusqu'ici OpenTagger ne
 * scannait que les dossiers ajoutés à la main dans Préférences → Démarrage ; il lit maintenant aussi cette liste.
 *
 * <p>Bibliothèques retenues : celle nommée « Music » (nom de fichier identique quelle que soit la langue de Windows) et
 * toute bibliothèque créée par l'utilisateur dont le type est « Musique ». Un emplacement noté comme dossier connu
 * ({@code knownfolder:{GUID}}) est traduit : Musique de l'utilisateur (y compris s'il a été déplacé, lu dans le
 * registre), Musique publique.
 */
public final class WindowsMusicLibrary {
    private WindowsMusicLibrary() {}

    /** Type de dossier « Musique » d'une bibliothèque Windows. */
    private static final String MUSIC_FOLDER_TYPE = "{94d6ddcc-4a68-4175-a374-bd584a510b78}";
    private static final String FOLDERID_MUSIC = "{4bd8d571-6d19-48d3-be97-422220080e43}";
    private static final String FOLDERID_PUBLIC_MUSIC = "{3214fab5-9757-4298-bb61-92a9deaa44ff}";

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Dossiers des bibliothèques musique de Windows ; liste vide ailleurs ou en cas de souci (jamais d'exception). */
    public static List<String> folders() {
        if (!IS_WINDOWS) return List.of();
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isBlank()) return List.of();
        Path dir = Paths.get(appdata, "Microsoft", "Windows", "Libraries");
        Set<String> out = new LinkedHashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.library-ms")) {
            for (Path p : ds) {
                String xml = Files.readString(p, StandardCharsets.UTF_8);
                boolean music = p.getFileName().toString().equalsIgnoreCase("Music.library-ms")
                        || xml.toLowerCase(Locale.ROOT).contains(MUSIC_FOLDER_TYPE);
                if (music) out.addAll(parse(xml, WindowsMusicLibrary::knownFolder));
            }
        } catch (Exception e) {
            return new ArrayList<>(out);
        }
        return new ArrayList<>(out);
    }

    /** Emplacements ({@code <url>}) d'une bibliothèque. {@code known} traduit un GUID de dossier connu (null = ignoré). */
    static List<String> parse(String xml, Function<String, String> known) {
        List<String> out = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.NodeList urls = f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagNameNS("*", "url");
            for (int i = 0; i < urls.getLength(); i++) {
                String u = urls.item(i).getTextContent().trim();
                if (u.isEmpty()) continue;
                if (u.toLowerCase(Locale.ROOT).startsWith("knownfolder:")) {
                    String path = known.apply(u.substring("knownfolder:".length()).trim().toLowerCase(Locale.ROOT));
                    if (path != null && !path.isBlank()) out.add(path);
                } else {
                    out.add(expandEnv(u, System::getenv));
                }
            }
        } catch (Exception e) {
            // XML illisible : rien de cette bibliothèque
        }
        return out;
    }

    static String expandEnv(String s, Function<String, String> env) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([^%]+)%").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = env.apply(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String knownFolder(String guid) {
        if (FOLDERID_MUSIC.equals(guid)) {
            String redirected = userShellFolder("My Music");
            if (redirected != null) return redirected;
            String home = System.getenv("USERPROFILE");
            return home == null ? null : Paths.get(home, "Music").toString();
        }
        if (FOLDERID_PUBLIC_MUSIC.equals(guid)) {
            String pub = System.getenv("PUBLIC");
            return pub == null ? null : Paths.get(pub, "Music").toString();
        }
        return null;
    }

    /** Emplacement réel d'un dossier utilisateur (déplacé par « Propriétés → Emplacement » ou OneDrive). */
    private static String userShellFolder(String name) {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders", "/v", name)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                String t = line.trim();
                int i = t.indexOf("REG_");
                if (t.startsWith(name) && i > 0) {
                    String v = t.substring(t.indexOf(' ', i)).trim();
                    return expandEnv(v, System::getenv);
                }
            }
        } catch (Exception ignored) { /* registre illisible : repli sur %USERPROFILE%\Music */ }
        return null;
    }

    /** Pour les tests : mêmes règles sans dépendre de la machine. */
    static Map<String, String> knownGuidsForTest() {
        return Map.of("music", FOLDERID_MUSIC, "public", FOLDERID_PUBLIC_MUSIC);
    }
}
