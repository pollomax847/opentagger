package com.opentagger;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class BatchProcessor {

    private final int SEUIL_AUTO = Config.get().minScoreAuto();

    private final MusicBrainzClient mbClient    = new MusicBrainzClient();
    private final AcoustIdClient    acoustId    = new AcoustIdClient();
    private final DiscogsClient     discogs     = new DiscogsClient();
    private final LastFmClient      lastFm      = new LastFmClient();
    private final FanArtClient      fanArt      = new FanArtClient();
    private final LocalCorrector    corrector   = new LocalCorrector();
    private final TagWriter         writer      = new TagWriter();
    private final FileRenamer       renamer     = new FileRenamer();
    private final boolean           useAcoustId;
    private final int               maskIndex;

    // Compteurs pour le résumé final
    private int total      = 0;
    private int appliques  = 0;
    private int renommes   = 0;
    private int incertains = 0;
    private int erreurs    = 0;

    public BatchProcessor(boolean useAcoustId, int maskIndex) {
        this.useAcoustId = useAcoustId;
        this.maskIndex   = maskIndex;
    }

    public void process(List<File> fichiers) {
        total = fichiers.size();
        System.out.println("Fichiers trouvés : " + total);
        System.out.println();

        for (int i = 0; i < fichiers.size(); i++) {
            File f = fichiers.get(i);
            System.out.printf("[%d/%d] %s%n", i + 1, total, f.getName());
            processOne(f);
            System.out.println();

            // Pause pour respecter le rate-limit de MusicBrainz (1 req/s)
            sleep(1100);
        }

        printSummary();
    }

    private void processOne(File fichier) {
        try {
            List<TagInfo> resultats = findTags(fichier);

            if (resultats.isEmpty()) {
                System.out.println("  ✗ Aucun résultat trouvé.");
                incertains++;
                return;
            }

            TagInfo best = resultats.get(0);

            if (best.score >= SEUIL_AUTO) {
                // Corrections locales (capitalisation, feat., filename, genre)
                corrector.correct(best, fichier.toPath());

                // Enrichir le genre : Discogs → Last.fm (cascade)
                if (best.genre.isBlank()) {
                    System.out.println("  → Genres via Discogs...");
                    try { discogs.enrichGenres(best); } catch (Exception e) { /* ignore */ }
                }
                if (best.genre.isBlank()) {
                    System.out.println("  → Genres via Last.fm...");
                    try { lastFm.enrichGenres(best); } catch (Exception e) { /* ignore */ }
                }
                // Télécharger la pochette FanArt.tv
                Path cover = null;
                if (!best.artistMbid.isBlank()) {
                    System.out.println("  → Pochette via FanArt.tv...");
                    try { cover = fanArt.downloadCover(best); } catch (Exception e) { /* ignore */ }
                }
                writer.write(fichier, best, cover);
                appliques++;

                // Renommer si un masque est sélectionné
                String renomme = "—";
                if (maskIndex >= 0) {
                    try {
                        Path nouveau = renamer.rename(fichier.toPath(), best, maskIndex);
                        if (nouveau != null) { renommes++; renomme = nouveau.getFileName().toString(); }
                    } catch (Exception e) { /* ignore */ }
                }

                System.out.printf("  ✓ (%d%%) %s - %s | Genre: %s | Pochette: %s | Renommé: %s%n",
                        best.score, best.artist, best.title,
                        best.genre.isBlank() ? "—" : best.genre,
                        cover != null ? "✓" : "—",
                        renomme);
            } else {
                System.out.printf("  ? Incertain (%d%%) : %s - %s — ignoré%n",
                        best.score, best.artist, best.title);
                incertains++;
            }

        } catch (Exception e) {
            System.out.println("  ✗ Erreur : " + e.getMessage());
            erreurs++;
        }
    }

    private List<TagInfo> findTags(File fichier) throws Exception {
        // Stratégie 1 : AcoustID (empreinte audio) si activé
        if (useAcoustId) {
            System.out.println("  → Analyse audio (AcoustID)...");
            List<TagInfo> resultats = acoustId.identify(fichier);
            if (!resultats.isEmpty()) return resultats;
            System.out.println("  → AcoustID sans résultat, essai par tags texte...");
        }

        // Stratégie 2 : recherche MusicBrainz par tags existants
        String artiste = getTag(fichier, FieldKey.ARTIST);
        String titre   = getTag(fichier, FieldKey.TITLE);

        if (artiste.isBlank() && titre.isBlank()) {
            System.out.println("  → Aucun tag existant et AcoustID désactivé.");
            return List.of();
        }

        System.out.printf("  → Recherche MusicBrainz : \"%s - %s\"%n", artiste, titre);
        return mbClient.searchRecording(artiste, titre);
    }

    private String getTag(File fichier, FieldKey key) {
        try {
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTag();
            return tag != null ? tag.getFirst(key) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void printSummary() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  Résumé du traitement                        ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf( "║  Total traité  : %d%n", total);
        System.out.printf( "║  ✓ Appliqués   : %d%n", appliques);
        System.out.printf( "║  ✓ Renommés    : %d%n", renommes);
        System.out.printf( "║  ? Incertains  : %d (score < %d%%)%n", incertains, SEUIL_AUTO);
        System.out.printf( "║  ✗ Erreurs     : %d%n", erreurs);
        System.out.println("╚══════════════════════════════════════════════╝");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
