package com.opentagger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Retrouve le nom d'un CD par son AUDIO quand la table des pistes ne suffit pas (disque absent de MusicBrainz sous cette
 * parution, compilation, etc.). Extrait quelques pistes échantillons, les identifie par empreinte (AcoustID), puis cherche la
 * release commune à plusieurs d'entre elles : une compilation ressort ainsi même si chaque titre renvoie d'abord son album
 * d'origine. Seules les releases au bon nombre de pistes sont retenues. Résultat à signaler comme « identifié par l'audio ».
 */
public final class CdAudioIdentifier {
    private CdAudioIdentifier() {}

    /** Nombre maximal de pistes échantillons extraites (≈ 15 s chacune). */
    static final int MAX_SAMPLES = 3;
    /** Délai maximal par piste échantillon : une piste de CD s'extrait en ~15 s, au-delà c'est que le lecteur est bloqué. */
    static final int SAMPLE_TIMEOUT_SEC = 120;

    /** Écart toléré par piste (secondes) entre le disque et une release proposée par une base de données. */
    public static final int TRACK_TOLERANCE_SEC = 3;
    /** Part minimale de pistes concordantes pour accepter une release trouvée par durées approximatives. */
    public static final double MIN_MATCHING_SHARE = 0.9;

    /** Nombre de pistes dont la durée concorde à {@code tolSec} près (comparées dans l'ordre). */
    public static int tracksWithinTolerance(List<Integer> cdSeconds, List<Integer> releaseSeconds, int tolSec) {
        int n = Math.min(cdSeconds.size(), releaseSeconds.size()), ok = 0;
        for (int i = 0; i < n; i++) if (Math.abs(cdSeconds.get(i) - releaseSeconds.get(i)) <= tolSec) ok++;
        return ok;
    }

    /** Vrai si la release proposée décrit bien CE disque : même nombre de pistes et presque toutes de la bonne durée. Un même
     *  total et un même nombre de pistes ne suffisent pas : sur un CD de 80 minutes, cela arrive par hasard. */
    public static boolean describesDisc(List<Integer> cdSeconds, List<Integer> releaseSeconds) {
        if (cdSeconds.isEmpty() || cdSeconds.size() != releaseSeconds.size()) return false;
        return tracksWithinTolerance(cdSeconds, releaseSeconds, TRACK_TOLERANCE_SEC)
                >= Math.ceil(cdSeconds.size() * MIN_MATCHING_SHARE);
    }

    /** Numéros des pistes à échantillonner : la première, la deuxième, puis une du milieu — distinctes. */
    static List<Integer> sampleTracks(int trackCount) {
        Set<Integer> s = new java.util.LinkedHashSet<>();
        if (trackCount >= 1) s.add(1);
        if (trackCount >= 2) s.add(2);
        if (trackCount >= 3) s.add(trackCount / 2 + 1 > 2 ? trackCount / 2 + 1 : trackCount);
        List<Integer> out = new ArrayList<>(s);
        return out.size() > MAX_SAMPLES ? out.subList(0, MAX_SAMPLES) : out;
    }

    /**
     * Choisit la release la plus souvent citée parmi les échantillons (une liste de releases candidates par piste).
     * Il faut au moins 2 pistes d'accord quand 2 échantillons ou plus ont donné un résultat ; avec un seul échantillon
     * exploitable, on n'accepte que s'il ne reste qu'une seule release candidate. Égalité : le premier cité.
     * @return le MBID de release, ou {@code null} si rien de sûr
     */
    static String pickRelease(List<Set<String>> perSample) {
        List<String> ranked = rankReleases(perSample);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /** Toutes les releases recevables, de la plus citée à la moins citée (mêmes règles que {@link #pickRelease}) : sert à proposer
     *  plusieurs versions à l'utilisateur. Égalité : ordre de première citation. */
    static List<String> rankReleases(List<Set<String>> perSample) {
        List<Set<String>> usable = new ArrayList<>();
        for (Set<String> s : perSample) if (s != null && !s.isEmpty()) usable.add(s);
        if (usable.isEmpty()) return List.of();
        if (usable.size() == 1) return usable.get(0).size() == 1 ? List.of(usable.get(0).iterator().next()) : List.of();
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (Set<String> s : usable) for (String r : s) votes.merge(r, 1, Integer::sum);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(votes.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue())); // tri stable : l'égalité garde l'ordre de citation
        List<String> out = new ArrayList<>();
        for (var e : entries) if (e.getValue() >= 2) out.add(e.getKey());
        return out;
    }

    /**
     * @param progress message d'avancement affiché à l'utilisateur (peut être {@code null})
     * @return la release trouvée, ou {@code null}
     */
    public static MusicBrainzClient.ReleaseTracklist identify(CdRipper.Toc toc, CdRipper ripper, MusicBrainzClient mb,
                                                              AcoustIdClient acoustId, Consumer<String> progress) throws Exception {
        String mbid = pickRelease(collectSamples(toc, ripper, mb, acoustId, progress));
        return mbid == null ? null : mb.lookupRelease(mbid);
    }

    /** Extrait les pistes échantillons, les identifie par empreinte, et renvoie pour chacune l'ensemble des releases (au bon
     *  nombre de pistes) qui la contiennent. Liste vide si AcoustID n'a pas de clé. */
    static List<Set<String>> collectSamples(CdRipper.Toc toc, CdRipper ripper, MusicBrainzClient mb,
                                            AcoustIdClient acoustId, Consumer<String> progress) throws Exception {
        if (Config.get().acoustidKey().isBlank()) return List.of(); // pas de clé : pas d'empreinte possible
        int n = toc.tracks().size();
        Path tmp = Files.createTempDirectory("opentagger-cdid");
        List<Set<String>> perSample = new ArrayList<>();
        try {
            List<Integer> samples = sampleTracks(n);
            for (int i = 0; i < samples.size(); i++) {
                int no = samples.get(i);
                if (progress != null) progress.accept("Identification par l'audio : piste " + no + " (" + (i + 1) + "/" + samples.size() + ")…");
                Path wav = null;
                try {
                    wav = ripper.ripTrackToWav(no, tmp, null, SAMPLE_TIMEOUT_SEC);
                    Set<String> recs = acoustId.knownRecordingIds(Fingerprinter.compute(wav.toFile()));
                    Set<String> releases = new HashSet<>();
                    if (recs != null) {
                        int looked = 0;
                        for (String rec : recs) {
                            if (looked++ >= 3) break; // quelques enregistrements suffisent, l'API MusicBrainz est limitée
                            for (var ref : mb.releasesOfRecording(rec))
                                if (!ref.releaseMbid().isBlank() && ref.trackCounts().contains(n)) releases.add(ref.releaseMbid());
                        }
                    }
                    perSample.add(releases);
                } catch (Exception e) {
                    perSample.add(Set.of()); // une piste illisible ne doit pas faire échouer les autres
                } finally {
                    if (wav != null) try { Files.deleteIfExists(wav); } catch (IOException ignored) {}
                }
            }
        } finally {
            try (var files = Files.list(tmp)) { for (Path f : (Iterable<Path>) files::iterator) Files.deleteIfExists(f); } catch (IOException ignored) {}
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
        return perSample;
    }

    /** Comme {@link #identify} mais renvoie jusqu'à {@code max} versions plausibles (la plus citée d'abord), pour que l'utilisateur
     *  choisisse. Les détails d'une release ne sont lus que pour celles qu'on renvoie. */
    public static List<MusicBrainzClient.ReleaseTracklist> identifyAll(CdRipper.Toc toc, CdRipper ripper, MusicBrainzClient mb,
                                                                       AcoustIdClient acoustId, Consumer<String> progress,
                                                                       int max) throws Exception {
        List<Set<String>> perSample = collectSamples(toc, ripper, mb, acoustId, progress);
        List<MusicBrainzClient.ReleaseTracklist> out = new ArrayList<>();
        for (String mbid : rankReleases(perSample)) {
            if (out.size() >= max) break;
            out.add(mb.lookupRelease(mbid));
        }
        return out;
    }
}
