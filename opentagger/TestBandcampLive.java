import com.opentagger.BandcampClient;

// Test manuel a lancer DEPUIS TA MACHINE (pas depuis l'environnement de dev, bloque par
// Bandcamp) pour verifier que le scraping/JSON-LD fonctionne reellement avant de cabler l'UI.
//
// Compilation :   javac -cp target/classes:$(find ~/.m2 -name "jsoup-1.17.2.jar" -o -name "jackson-databind-2.17.0.jar" -o -name "jackson-core-2.17.0.jar" -o -name "jackson-annotations-2.17.0.jar" | tr '\n' ':') TestBandcampLive.java
// Execution :     java  -cp .:target/classes:$(find ~/.m2 -name "jsoup-1.17.2.jar" -o -name "jackson-databind-2.17.0.jar" -o -name "jackson-core-2.17.0.jar" -o -name "jackson-annotations-2.17.0.jar" | tr '\n' ':') TestBandcampLive
public class TestBandcampLive {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Diagnostic brut de la page de recherche ===");
        org.jsoup.nodes.Document raw = org.jsoup.Jsoup.connect(
                "https://bandcamp.com/search?q=Nirvana+Nevermind&item_type=a")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .timeout(20000)
                .get();
        System.out.println("Titre de la page : " + raw.title());
        System.out.println("Longueur HTML : " + raw.html().length() + " caracteres");
        System.out.println("Contient 'searchresult' : " + raw.html().contains("searchresult"));
        System.out.println("Contient 'result-items' : " + raw.html().contains("result-items"));
        System.out.println("Contient 'Client Challenge' : " + raw.html().contains("Client Challenge"));
        System.out.println("Contient '.bandcamp.com/album/' : " + raw.html().contains(".bandcamp.com/album/"));
        System.out.println("Contient 'application/ld+json' : " + raw.html().contains("application/ld+json"));
        System.out.println();
        System.out.println("=== Premiers 1500 caracteres du <body> ===");
        System.out.println(raw.body().html().substring(0, Math.min(1500, raw.body().html().length())));
        System.out.println();

        System.out.println("=== Verification apres correctif duree/credits (Iglooghost) ===");
        var album = BandcampClient.fetchAlbum("https://iglooghost.bandcamp.com/album/clear-tamei");
        System.out.println(album.artist() + " - " + album.title() + " (" + album.tracks().size() + " pistes)");
        for (var t : album.tracks()) System.out.println("  " + t.position() + ". " + t.title() + " (" + t.durationSec() + "s)");
        System.out.println("Credits : " + album.credits());

        System.out.println();
        System.out.println("=== Test page PISTE individuelle (URL connue reelle) ===");
        var track = BandcampClient.fetchTrack("https://iglooghost.bandcamp.com/track/pa-leo-mamu");
        if (track == null) {
            System.out.println("AUCUN JSON-LD MusicRecording trouve — structure a revoir.");
        } else {
            System.out.println("titre=" + track.title() + " artiste=" + track.artist()
                    + " duree=" + track.durationSec() + "s album=" + track.album());
        }

        System.out.println();
        System.out.println("=== Test devinage d'URL (artiste+titre connus -> URL) ===");
        String guessed = BandcampClient.guessTrackUrl("Iglooghost", "Clear Tamei");
        System.out.println("Devine : " + guessed + " (attendu : https://iglooghost.bandcamp.com/track/clear-tamei)");
        var guessedTrack = BandcampClient.fetchTrack(guessed);
        System.out.println(guessedTrack == null ? "Pas trouve (peut-etre normal si ce titre n'est pas aussi une piste seule)"
                : ("Trouve : " + guessedTrack.title() + " - " + guessedTrack.artist()));
    }
}
