package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AcoustIdClient {

    private static final String LOOKUP_URL = "https://api.acoustid.org/v2/lookup";

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MusicBrainzClient mbClient = new MusicBrainzClient();

    // Règle d'usage publiée par AcoustID (https://acoustid.org/webservice) : "Do not make more than 3
    // requests per second". Jusqu'au 2026-09-20 RIEN ne la respectait : seul fpcalc était limité
    // (acoustid.fpcalc_threads=2 processus en parallèle), pas les requêtes — sur des fichiers courts en
    // disque local, 2 fpcalc rapides + jusqu'à 3 appels identify() par fichier dans la cascade dépassent
    // facilement 3 requêtes/seconde, avec le risque de voir la clé d'application bridée/bloquée. Créneau
    // partagé par TOUTES les instances (lookup ET soumissions) : 350 ms entre deux départs ≈ 2,9 req/s.
    private static final long MIN_INTERVAL_MS = 350;
    private static long nextSlotMs = 0;

    /** Bloque jusqu'au prochain créneau autorisé (≤ 3 requêtes/s tous threads confondus). */
    static void throttle() throws InterruptedException {
        long delay;
        synchronized (AcoustIdClient.class) {
            long now  = System.currentTimeMillis();
            long slot = Math.max(now, nextSlotMs);
            nextSlotMs = slot + MIN_INTERVAL_MS;
            delay = slot - now;
        }
        if (delay > 0) Thread.sleep(delay);
    }

    /** Corps compressé en GZip — préféré par AcoustID (voir sa doc : les empreintes sont longues), vérifié en
     *  direct contre l'API réelle le 2026-09-20 (HTTP 200, mêmes résultats, corps ~25 % plus petit). */
    static byte[] gzip(String body) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    // Mémo par instance (une instance = un fichier en cours de traitement, voir TaggingWorker) : la cascade
    // appelle identify() jusqu'à 3 fois pour le MÊME fichier (avis de confirmation, essai normal, dernier
    // recours) — même empreinte, donc même réponse : inutile de refaire fpcalc + une requête à chaque fois.
    private String   memoKey  = null;
    private String   memoFingerprint = "";
    private JsonNode memoRoot = null;

    // Fingerprint du dernier fichier identifié — réutilisé pour remplir TagInfo.acoustidFingerprint
    private String lastFingerprint = "";
    private String lastAcoustId    = "";

    private static final String META_FULL = "recordings+releaseids+releasegroups+sources+compress";

    /**
     * POST d'un lookup AcoustID (créneau ≤ 3 req/s, corps gzip, jusqu'à 3 essais sur 429/5xx). Renvoie la
     * racine JSON quand {@code status == "ok"}, sinon {@code null} (message déjà loggé). Les erreurs
     * réseau/timeout (IOException) remontent à l'appelant, comme avant.
     */
    private JsonNode postLookup(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LOOKUP_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Content-Encoding", "gzip")
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(body)))
                .build();

        // Avant le 2026-09-20, toute réponse non-200 était avalée comme "aucun résultat" — indiscernable d'un
        // vrai "fichier inconnu" : un simple pic de charge suffisait à laisser des fichiers non identifiés.
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int sc = response.statusCode();
            if (sc != 429 && sc < 500) break;
            System.out.println("  AcoustID : HTTP " + sc + " — nouvel essai " + (attempt + 1) + "/3");
            Thread.sleep(1000L << attempt);
        }
        if (response.statusCode() != 200) {
            System.out.println("  Erreur AcoustID : HTTP " + response.statusCode());
            return null;
        }
        JsonNode root = mapper.readTree(response.body());
        if (!"ok".equals(root.path("status").asText())) {
            System.out.println("  AcoustID erreur: " + root.path("error").path("message").asText("statut inconnu"));
            return null;
        }
        return root;
    }

    public List<TagInfo> identify(File fichier) throws Exception {
        lastFingerprint = "";
        lastAcoustId    = "";

        // Sans clé configurée, la requête AcoustID échouerait de toute façon — éviter le calcul
        // d'empreinte (fpcalc, coûteux en CPU) ET l'appel réseau inutiles sur chaque fichier,
        // même pattern que DiscogsClient/FanArtClient/LastFmClient.
        if (Config.get().acoustidKey().isBlank()) return List.of();

        String fileKey = fichier.getAbsolutePath() + "|" + fichier.length() + "|" + fichier.lastModified();
        JsonNode root;
        if (fileKey.equals(memoKey) && memoRoot != null) {
            root = memoRoot;                       // même fichier, même empreinte : réponse déjà en main
            lastFingerprint = memoFingerprint;
        } else {
            // 1. Générer l'empreinte audio avec fpcalc (JSON, max 120s comme Picard)
            Fingerprinter.Result fp;
            try {
                fp = Fingerprinter.compute(fichier);
            } catch (Exception e) {
                System.out.println("  " + e.getMessage());
                return List.of();
            }
            lastFingerprint = fp.fingerprint();

            // 2. Envoyer l'empreinte à AcoustID avec meta complet (comme Picard)
            root = postLookup("client=" + Config.get().acoustidKey()
                    + "&duration=" + fp.duration()
                    + "&fingerprint=" + URLEncoder.encode(fp.fingerprint(), StandardCharsets.UTF_8)
                    + "&meta=" + META_FULL);
            if (root == null) return List.of();
            memoKey = fileKey; memoFingerprint = fp.fingerprint(); memoRoot = root;
        }
        return resultsFrom(root);
    }

    /**
     * Identification depuis un AcoustID DÉJÀ présent dans les tags du fichier ("Lookup by track ID" de la doc
     * AcoustID, {@code trackid=}) : aucun fpcalc, aucune empreinte à envoyer — SongKong fait exactement ça
     * (AcoustidHelper.getMusicBrainzAcoustidResultsFromCacheOrDb → lookupRecordingsForAcoustIds, par lots de
     * 10 + cache disque), Picard non. Option {@code acoustid.lookup_by_track_id} (voir TaggingWorker.tryAcoustId) :
     * gain de vitesse au prix de ne plus RE-vérifier l'audio réel — l'identifiant du tag est cru sur parole.
     */
    public List<TagInfo> identifyByTrackId(String acoustId) throws Exception {
        lastFingerprint = "";
        lastAcoustId    = acoustId == null ? "" : acoustId.trim();
        if (Config.get().acoustidKey().isBlank() || lastAcoustId.isBlank()) return List.of();
        JsonNode root = postLookup("client=" + Config.get().acoustidKey()
                + "&trackid=" + URLEncoder.encode(lastAcoustId, StandardCharsets.UTF_8)
                + "&meta=" + META_FULL);
        return root == null ? List.of() : resultsFrom(root);
    }

    /**
     * Enregistrements qu'AcoustID associe à une empreinte, avec titre/artistes/durée fournis DIRECTEMENT par
     * AcoustID ({@code meta=recordings}) — aucun aller-retour MusicBrainz, contrairement à
     * {@link #identify(File)} : c'est ce qu'il faut pour AUDITER un fichier (AudioTagAudit), où seule compte
     * la question "qu'est-ce que cet audio ?", pas de reconstituer des tags complets. Un résultat vide est un
     * vrai "empreinte inconnue" ; une panne (HTTP/JSON) lève une exception, pour ne jamais être prise pour
     * "inconnue" (voir postLookup(), qui renvoie null dans ce cas).
     */
    public List<AudioTagAudit.Candidate> lookupRecordings(Fingerprinter.Result fp) throws Exception {
        if (Config.get().acoustidKey().isBlank()) throw new IllegalStateException("clé AcoustID absente");
        JsonNode root = postLookup("client=" + Config.get().acoustidKey()
                + "&duration=" + fp.duration()
                + "&fingerprint=" + URLEncoder.encode(fp.fingerprint(), StandardCharsets.UTF_8)
                + "&meta=recordings");
        if (root == null) throw new java.io.IOException("AcoustID indisponible (voir le journal)");
        List<AudioTagAudit.Candidate> out = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            double score = result.path("score").asDouble();
            for (JsonNode rec : result.path("recordings")) {
                String title = rec.path("title").asText("");
                if (title.isBlank()) continue;
                StringBuilder artists = new StringBuilder();
                for (JsonNode a : rec.path("artists")) {
                    if (artists.length() > 0) artists.append(" & ");
                    artists.append(a.path("name").asText(""));
                }
                out.add(new AudioTagAudit.Candidate(artists.toString(), title, score,
                        rec.path("duration").asInt(0), rec.path("id").asText("")));
            }
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /**
     * MBID des enregistrements qu'AcoustID associe DÉJÀ à cette empreinte (score ≥ 0.9, quasi-identique) —
     * sert à ne PAS soumettre ce qu'AcoustID connaît déjà, comme Picard (picard/acoustid/manager.py :
     * Submission.is_submitted = "recordingid == orig_recordingid", l'enregistrement que le lookup avait
     * renvoyé pour cette empreinte). Un seul appel léger (meta=recordingids). Renvoie {@code null} si le
     * lookup a échoué (l'appelant décide alors : ne pas bloquer la soumission sur une panne réseau).
     */
    public java.util.Set<String> knownRecordingIds(Fingerprinter.Result fp) throws Exception {
        if (Config.get().acoustidKey().isBlank()) return null;
        JsonNode root = postLookup("client=" + Config.get().acoustidKey()
                + "&duration=" + fp.duration()
                + "&fingerprint=" + URLEncoder.encode(fp.fingerprint(), StandardCharsets.UTF_8)
                + "&meta=recordingids");
        if (root == null) return null;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (JsonNode result : root.path("results")) {
            if (result.path("score").asDouble() < 0.9) continue;
            for (JsonNode rec : result.path("recordings")) {
                String id = rec.path("id").asText("").trim().toLowerCase(java.util.Locale.ROOT);
                if (!id.isBlank()) known.add(id);
            }
        }
        return known;
    }

    /** Suite commune à identify()/identifyByTrackId() : MBID candidats → tags complets MusicBrainz. */
    private List<TagInfo> resultsFrom(JsonNode root) {
        List<MbidCandidate> mbids = parseMbids(root);
        extractAcoustId(root);
        if (mbids.isEmpty()) return List.of();

        // 4. Récupérer les tags complets depuis MusicBrainz pour les premiers MBIDs
        List<TagInfo> results = fetchBestFromMusicBrainz(mbids);

        if (!lastAcoustId.isBlank())
            results.forEach(t -> { if (t.acoustidId.isBlank()) t.acoustidId = lastAcoustId; });
        if (!lastFingerprint.isBlank())
            results.forEach(t -> { if (t.acoustidFingerprint.isBlank()) t.acoustidFingerprint = lastFingerprint; });
        return results;
    }

    /** Un MBID candidat associé au score de confiance AcoustID du résultat qui l'a produit, et au
     *  nombre de soumissions indépendantes ayant lié cette empreinte à ce MBID précis ("sources",
     *  écart trouvé vs SongKong — AcoustidHelper.getRecordingsWithMostSources() — analyse du jar
     *  décompilé 2026-09-18). Déjà demandé dans la requête (meta=...+sources+...) mais jamais lu
     *  jusqu'ici — vérifié en direct (curl réel, 2026-09-18) : le champ existe bien à
     *  results[].recordings[].sources. Utilisé UNIQUEMENT comme second critère de tri, jamais
     *  comme filtre dur : un vrai test en direct sur un remix peu commun a donné sources=1 pour un
     *  match pourtant clairement correct (le nom du remix apparaît tel quel dans la réponse) — sur
     *  une bibliothèque avec beaucoup de contenu rare/remix comme celle-ci, rejeter ou pénaliser un
     *  score juste pour un faible nombre de sources produirait de vrais faux négatifs. */
    private record MbidCandidate(String mbid, double acoustidScore, int sources) {}

    private List<MbidCandidate> parseMbids(JsonNode root) {
        // MusicBrainz ne renvoie aucun score pour un lookup direct par ID (TagInfo.score y est
        // toujours 100) : le seul signal de confiance disponible pour départager plusieurs MBIDs
        // candidats est celui d'AcoustID lui-même, capturé ici avant d'être perdu.
        java.util.Map<String, Double> bestScoreByMbid   = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> sourcesByMbid    = new java.util.LinkedHashMap<>();
        for (JsonNode result : root.path("results")) {
            // Seuil abaissé à 0.5 comme Picard — AcoustID peut donner 0.6-0.8 sur fichiers bruités
            double score = result.path("score").asDouble();
            if (score < 0.5) continue;
            for (JsonNode recording : result.path("recordings")) {
                String mbid = recording.path("id").asText("");
                if (mbid.isBlank()) continue;
                bestScoreByMbid.merge(mbid, score, Math::max);
                sourcesByMbid.merge(mbid, recording.path("sources").asInt(0), Math::max);
            }
        }
        List<MbidCandidate> mbids = new ArrayList<>();
        bestScoreByMbid.forEach((mbid, score) ->
                mbids.add(new MbidCandidate(mbid, score, sourcesByMbid.getOrDefault(mbid, 0))));
        // Score AcoustID en premier (mesure la qualité acoustique du matching, le signal principal
        // établi de longue date) ; sources en second, seulement pour départager une VRAIE égalité
        // de score entre deux MBID différents pour la même empreinte — jamais pour reclasser un
        // score par ailleurs meilleur derrière un score moins bon juste parce que plus "corroboré".
        mbids.sort((a, b) -> {
            int byScore = Double.compare(b.acoustidScore(), a.acoustidScore());
            return byScore != 0 ? byScore : Integer.compare(b.sources(), a.sources());
        });
        return mbids;
    }

    /** Extrait le meilleur AcoustID track ID (résultat de score le plus élevé). */
    private void extractAcoustId(JsonNode root) {
        double bestScore = 0;
        for (JsonNode result : root.path("results")) {
            double score = result.path("score").asDouble();
            if (score > bestScore) {
                bestScore    = score;
                lastAcoustId = result.path("id").asText("");
            }
        }
    }

    /**
     * Essaie jusqu'à 3 MBIDs (par ordre de confiance AcoustID décroissante) et retourne le
     * meilleur résultat. TagInfo.score d'un lookup MB direct est toujours 100 (l'API n'a pas
     * de notion de score pour un lookup par ID) : le classement se fait donc sur le score
     * AcoustID de départ, pas sur ce score MB qui ne discrimine rien entre les candidats.
     */
    private List<TagInfo> fetchBestFromMusicBrainz(List<MbidCandidate> mbids) {
        TagInfo best = null;
        double bestAcoustidScore = -1;
        int tries = Math.min(mbids.size(), 3);
        for (int i = 0; i < tries; i++) {
            MbidCandidate candidate = mbids.get(i);
            try {
                // Utilise mbClient.lookupRecording qui inclut TOUS les inc= nécessaires
                // Rate-limit MB : centralisé dans MusicBrainzClient.getWithRetry(), plus besoin
                // de pause manuelle ici même si ce client a son propre MusicBrainzClient interne.
                TagInfo info = mbClient.lookupRecording(candidate.mbid());
                if (info != null && !info.title.isBlank()) {
                    if (candidate.acoustidScore() > bestAcoustidScore) {
                        bestAcoustidScore = candidate.acoustidScore();
                        info.acoustidConfidence = candidate.acoustidScore();
                        best = info;
                    }
                    if (candidate.acoustidScore() >= 0.9) break; // confiance déjà excellente, inutile d'essayer les autres
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.out.println("  MB lookup " + candidate.mbid() + " : " + e.getMessage());
            }
        }
        return best != null ? List.of(best) : List.of();
    }
}
