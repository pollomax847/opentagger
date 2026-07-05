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
        // Supprimer tous les logs JAudioTagger (scan warnings + write GRAVE errors sur M4A)
        Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
        // Les loggers enfants sont souvent créés avant cette ligne → les silencer explicitement
        java.util.logging.LogManager.getLogManager().getLoggerNames().asIterator()
            .forEachRemaining(n -> {
                if (n.startsWith("org.jaudiotagger"))
                    Logger.getLogger(n).setLevel(Level.OFF);
            });

        // jaudiotagger encode par défaut en ISO-8859-1 (Latin-1) pour ID3v2.3 ET v2.4, y compris
        // pour tout caractère accenté (é, à, ü…) qui "tient" en Latin-1. Plex (et tout lecteur
        // qui suppose de l'UTF-8) interprète alors ces octets comme de l'UTF-8 invalide et plante
        // (cf. docs/plex-utf8-corruption.md — incident déjà vécu, jamais corrigé côté code).
        // On force ici l'unicode réellement standard pour chaque version ID3v2, et on force aussi
        // la ré-encodage des frames déjà présentes (pas seulement les nouvelles) pour ne jamais
        // laisser un fichier avec un mélange d'encodages.
        org.jaudiotagger.tag.TagOptionSingleton opts = org.jaudiotagger.tag.TagOptionSingleton.getInstance();
        opts.setId3v23DefaultTextEncoding(org.jaudiotagger.tag.id3.valuepair.TextEncoding.UTF_16);
        opts.setId3v24DefaultTextEncoding(org.jaudiotagger.tag.id3.valuepair.TextEncoding.UTF_8);
        opts.setResetTextEncodingForExistingFrames(true);

        if (args.length == 0) {
            MainFrame.launch(startupDirs(new File[0]));
            return;
        }

        // --- Mode UI lancé depuis clic-droit OS : opentagger /fichier.mp3 ou /dossier ---
        // Accepte fichiers audio ET dossiers. NE charge PAS les dossiers de démarrage
        // automatiques pour ne pas polluer la vue quand l'utilisateur ouvre un fichier spécifique.
        if (!args[0].startsWith("--")) {
            java.util.LinkedHashSet<File> targets = new java.util.LinkedHashSet<>();
            for (String arg : args) {
                File f = new File(arg);
                if (f.isDirectory()) {
                    targets.add(f);           // dossier → chargé en entier
                } else if (f.isFile()) {
                    targets.add(f);           // fichier seul → chargé tel quel (pas le parent)
                }
            }
            if (!targets.isEmpty()) {
                MainFrame.launch(targets.toArray(new File[0])); // sans dossiers de démarrage auto
                return;
            }
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

            BatchProcessor processor = new BatchProcessor(useAcoustId, maskIndex, dossier.toPath());
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
            moveIfConfiguredSkipped(fichier);
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
        new TaggerScript().apply(choisi);
        TagEnrichment.translateArtist(choisi, new MusicBrainzClient(), new java.util.HashMap<>());

        // 6. Empreinte AcoustID même si l'identification vient du texte (pas seulement d'AcoustID)
        if (Config.get().saveAcoustidFingerprints() && choisi.acoustidFingerprint.isBlank()
                && FpcalcInstaller.isAvailable()) {
            try { choisi.acoustidFingerprint = Fingerprinter.compute(fichier).fingerprint(); }
            catch (Exception ignored) {}
        }

        // 6b. BPM (ffmpeg) — présent dans le pipeline GUI (TaggingWorker/InfoCompleterWorker)
        // mais absent ici jusqu'à présent.
        if (choisi.bpm.isBlank() && BpmDetector.isAvailable()) {
            int bpm = new BpmDetector().detect(fichier.getAbsolutePath());
            if (bpm > 0) choisi.bpm = String.valueOf(bpm);
        }

        // 7. Enrichir le genre : Discogs → Last.fm (cascade)
        System.out.println("Recherche du genre (Discogs → Last.fm)...");
        LastFmClient lastFm = new LastFmClient();
        TagEnrichment.enrichGenre(choisi, new DiscogsClient(), lastFm);
        if (!choisi.genre.isBlank()) System.out.println("  Genre trouvé : " + choisi.genre);
        // Mood + URLs artiste Last.fm — même gap : absents du CLI jusqu'à présent.
        if (choisi.mood.isBlank()) { try { lastFm.enrichMood(choisi); } catch (Exception ignored) {} }
        try { lastFm.enrichArtistUrls(choisi); } catch (Exception ignored) {}

        // Paroles — même gap : absentes du CLI jusqu'à présent.
        try { new LyricsClient().enrich(choisi); } catch (Exception ignored) {}

        // 8. Pochette : Cover Art Archive → dossier local → FanArt.tv
        System.out.println("Recherche de la pochette (CAA → local → FanArt.tv)...");
        java.nio.file.Path cover = TagEnrichment.resolveCover(choisi, fichier, new CaaClient(), new FanArtClient());
        System.out.println(cover != null ? "  Pochette trouvée." : "  Aucune pochette trouvée.");

        // 9. Écrire les tags + pochette
        try {
            new TagWriter().write(fichier, choisi, cover);
            TagEnrichment.recordSuccess(new MetadataCache(), fichier, choisi);
        } catch (Exception e) {
            System.out.println();
            System.out.println("✗ Erreur lors de l'écriture des tags : " + e.getMessage());
            moveIfConfiguredSkipped(fichier);
            return;
        }

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

    /** Déplace un fichier non tagué (aucun résultat / erreur d'écriture) vers le dossier dédié
     *  si configuré — mêmes règles que le pipeline GUI (TaggingWorker) et BatchProcessor. */
    private static void moveIfConfiguredSkipped(File fichier) {
        if (!Config.get().skippedMoveEnabled()) return;
        String folder = Config.get().skippedMoveFolder();
        if (folder.isBlank()) return;
        try {
            FileRenamer.moveToFolder(fichier.toPath(), java.nio.file.Paths.get(folder));
        } catch (Exception ignored) {}
    }

    /** Fusionne les dossiers CLI avec les dossiers de démarrage configurés dans les préférences. */
    private static File[] startupDirs(File[] cliDirs) {
        java.util.LinkedHashSet<File> all = new java.util.LinkedHashSet<>(java.util.Arrays.asList(cliDirs));
        for (String p : com.opentagger.Config.get().startupFolders()) {
            File f = new File(p.trim());
            if (f.isDirectory()) all.add(f);
        }
        return all.toArray(new File[0]);
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
