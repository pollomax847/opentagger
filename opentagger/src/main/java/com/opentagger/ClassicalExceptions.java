package com.opentagger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/**
 * Releases/release-groups MusicBrainz connus pour déclencher à tort une détection "classique" —
 * héritage SongKong/Jaikoz (fichiers {@code not_classical_release.txt}/{@code
 * not_classical_release_group.txt}, présents dans les ressources du jar depuis le tout premier
 * commit du projet mais jamais chargés ni référencés nulle part dans le code — trouvé en audit
 * (2026-09-18) en comparant à la dernière version de SongKong téléchargée par l'utilisateur).
 *
 * Complémentaire, pas redondant, avec le garde à bornes de mot de {@link LocalCorrector#detectClassical}
 * et la preuve par mouvement/opus de {@link MusicBrainzClient#resolveClassicalWork} : ces deux-là
 * couvrent des heuristiques (nom d'artiste qui ressemble, Work MB avec structure de mouvements),
 * cette liste couvre le cas où une release précise a structurellement un crédit "classique" (chef
 * d'orchestre, orchestre...) sans être une œuvre classique — irrattrapable par une heuristique
 * générique, seulement par exception ciblée au cas par cas comme le fait SongKong.
 */
public final class ClassicalExceptions {

    private ClassicalExceptions() {}

    private static final Set<String> RELEASES       = load("/not_classical_release.txt");
    private static final Set<String> RELEASE_GROUPS = load("/not_classical_release_group.txt");

    private static Set<String> load(String resource) {
        Set<String> ids = new HashSet<>();
        try (InputStream in = ClassicalExceptions.class.getResourceAsStream(resource);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                String id = (eq > 0 ? line.substring(0, eq) : line).trim();
                if (!id.isBlank()) ids.add(id);
            }
        } catch (Exception ignored) {
            // fichier absent/illisible : liste vide, dégradation silencieuse comme le reste des
            // listes de référence de LocalCorrector.
        }
        return ids;
    }

    /** @return true si cette release ou ce release-group est une exception connue — la détection
     *  classique (nom d'artiste ou Work MB) ne doit alors PAS marquer {@code isClassical="1"}. */
    public static boolean isException(String releaseMbid, String releaseGroupMbid) {
        return (releaseMbid != null && !releaseMbid.isBlank() && RELEASES.contains(releaseMbid))
            || (releaseGroupMbid != null && !releaseGroupMbid.isBlank() && RELEASE_GROUPS.contains(releaseGroupMbid));
    }
}
