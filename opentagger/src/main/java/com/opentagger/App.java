package com.opentagger;

import com.opentagger.model.TagInfo;
import com.opentagger.ui.MainFrame;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {

    public static void main(String[] args) throws Exception {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);

        if (args.length == 0) {
            MainFrame.launch();
            return;
        }

        // --- Mode dossier : opentagger --dossier /chemin [--acoustid] [--masque N] ---
        if ("--dossier".equals(args[0])) {
            if (args.length < 2) { printUsage(); return; }

            File dossier = new File(args[1]);
            if (!dossier.isDirectory()) {
                System.out.println("Ce chemin n'est pas un dossier : " + args[1]);
                return;
            }

            boolean useAcoustId = false;
            int maskIndex = -1; // -1 = pas de renommage

            for (int i = 2; i < args.length; i++) {
                if ("--acoustid".equals(args[i]))  useAcoustId = true;
                if ("--masque".equals(args[i]) && i + 1 < args.length) {
                    try { maskIndex = Integer.parseInt(args[++i]); }
                    catch (NumberFormatException ignored) {}
                }
            }

            System.out.println("Scan du dossier : " + dossier.getAbsolutePath());
            System.out.println("Mode            : " + (useAcoustId ? "AcoustID + MusicBrainz" : "MusicBrainz (tags texte)"));
            System.out.println("Renommage       : " + (maskIndex >= 0 ? "masque " + maskIndex : "non"));
            System.out.println();

            AudioScanner scanner = new AudioScanner();
            List<File> fichiers = scanner.scan(dossier);

            if (fichiers.isEmpty()) {
                System.out.println("Aucun fichier audio trouvé dans ce dossier.");
                return;
            }

            BatchProcessor processor = new BatchProcessor(useAcoustId, maskIndex);
            processor.process(fichiers);
            return;
        }

        // --- Mode fichier unique : opentagger /chemin/fichier.mp3 [--acoustid] ---
        File fichier = new File(args[0]);
        if (!fichier.exists()) {
            System.out.println("Fichier introuvable : " + args[0]);
            return;
        }

        boolean useAcoustId = args.length >= 2 && "--acoustid".equals(args[1]);

        // 1. Lire les tags existants
        AudioFile audio = AudioFileIO.read(fichier);
        Tag tag = audio.getTag();

        String titre   = tag != null ? tag.getFirst(FieldKey.TITLE)  : "";
        String artiste = tag != null ? tag.getFirst(FieldKey.ARTIST) : "";
        String album   = tag != null ? tag.getFirst(FieldKey.ALBUM)  : "";
        String annee   = tag != null ? tag.getFirst(FieldKey.YEAR)   : "";

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  Tags actuels : " + fichier.getName());
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║  Titre   : " + titre);
        System.out.println("║  Artiste : " + artiste);
        System.out.println("║  Album   : " + album);
        System.out.println("║  Année   : " + annee);
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // 2. Identifier la chanson
        List<TagInfo> resultats;

        if (useAcoustId) {
            System.out.println("Analyse audio (AcoustID)...");
            AcoustIdClient acoustId = new AcoustIdClient();
            resultats = acoustId.identify(fichier);
            if (resultats.isEmpty()) {
                System.out.println("AcoustID sans résultat, essai MusicBrainz par tags...");
                resultats = new MusicBrainzClient().searchRecording(artiste, titre);
            }
        } else {
            System.out.println("Recherche sur MusicBrainz...");
            resultats = new MusicBrainzClient().searchRecording(artiste, titre);
        }

        if (resultats.isEmpty()) {
            System.out.println("Aucun résultat trouvé.");
            return;
        }

        // 3. Afficher les résultats
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  Résultats trouvés                           ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        for (int i = 0; i < resultats.size(); i++) {
            TagInfo r = resultats.get(i);
            System.out.printf("║  [%d] %3d%%  %s - %s%n", i + 1, r.score, r.artist, r.title);
            System.out.printf("║       Album : %s (%s)   Piste : %s%n", r.album, r.year, r.track);
            System.out.println("║");
        }
        System.out.println("║  [0] Ne rien modifier                        ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // 4. Choix utilisateur
        System.out.print("Ton choix (0-" + resultats.size() + ") : ");
        Scanner scanner = new Scanner(System.in);
        int choix;
        try {
            choix = Integer.parseInt(scanner.nextLine().trim());
        } catch (NumberFormatException e) {
            System.out.println("Choix invalide, abandon.");
            return;
        }

        if (choix == 0) { System.out.println("Aucune modification."); return; }
        if (choix < 1 || choix > resultats.size()) { System.out.println("Hors limite, abandon."); return; }

        TagInfo choisi = resultats.get(choix - 1);

        // 5. Corrections locales (capitalisation, feat., genre)
        new LocalCorrector().correct(choisi, fichier.toPath());

        // 7. Enrichir le genre : Discogs → Last.fm (cascade)
        if (choisi.genre.isBlank()) {
            System.out.println("Recherche genres sur Discogs...");
            try { new DiscogsClient().enrichGenres(choisi); } catch (Exception e) { /* ignore */ }
        }
        if (choisi.genre.isBlank()) {
            System.out.println("Recherche genres sur Last.fm...");
            try { new LastFmClient().enrichGenres(choisi); } catch (Exception e) { /* ignore */ }
        }
        if (!choisi.genre.isBlank()) System.out.println("  Genre trouvé : " + choisi.genre);

        // 8. Télécharger la pochette FanArt.tv
        java.nio.file.Path cover = null;
        if (!choisi.artistMbid.isBlank()) {
            System.out.println("Téléchargement pochette (FanArt.tv)...");
            try {
                cover = new FanArtClient().downloadCover(choisi);
                System.out.println(cover != null ? "  Pochette téléchargée." : "  Aucune pochette trouvée.");
            } catch (Exception e) {
                System.out.println("  FanArt.tv indisponible : " + e.getMessage());
            }
        }

        // 9. Écrire les tags + pochette
        new TagWriter().write(fichier, choisi, cover);

        System.out.println();
        System.out.println("✓ Tags mis à jour !");
        System.out.println("  Titre    : " + choisi.title);
        System.out.println("  Artiste  : " + choisi.artist);
        System.out.println("  Album    : " + choisi.album);
        System.out.println("  Année    : " + choisi.year);
        System.out.println("  Piste    : " + choisi.track);
        System.out.println("  Genre    : " + (choisi.genre.isBlank() ? "—" : choisi.genre));
        System.out.println("  Pochette : " + (cover != null          ? "✓" : "—"));

        // 8. Renommage optionnel
        System.out.println();
        FileRenamer renamer = new FileRenamer();
        renamer.printMaskList();

        // Prévisualiser le masque par défaut
        String ext = fichier.getName().contains(".")
                ? fichier.getName().substring(fichier.getName().lastIndexOf('.')) : "";
        int maskDefaut = Config.get().defaultRenameMask();
        System.out.println("Aperçu masque [" + maskDefaut + "] : " + renamer.preview(choisi, maskDefaut, ext));
        System.out.print("Masque à appliquer (numéro ou Entrée pour ignorer) : ");

        String ligne = scanner.nextLine().trim();
        if (!ligne.isEmpty()) {
            try {
                int maskChoisi = Integer.parseInt(ligne);
                java.nio.file.Path nouveauChemin = renamer.rename(fichier.toPath(), choisi, maskChoisi);
                if (nouveauChemin != null)
                    System.out.println("✓ Fichier déplacé : " + nouveauChemin);
            } catch (NumberFormatException e) {
                System.out.println("Masque invalide, renommage ignoré.");
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  Fichier unique  : opentagger <fichier.mp3> [--acoustid]");
        System.out.println("  Dossier entier  : opentagger --dossier <dossier/> [--acoustid] [--masque N]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --acoustid    Identifie par empreinte audio");
        System.out.println("  --masque N    Renomme les fichiers avec le masque N après tagging");
        System.out.println("                (voir ~/.opentagger/renamemask.properties pour la liste)");
    }
}
