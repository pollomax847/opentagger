package com.opentagger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Accès LECTURE SEULE à la vraie bibliothèque iTunes (Windows uniquement) par son interface COM
 * « iTunes.Application » — la même voie que Tune Sweeper (Interop.iTunesLib). Aucune dépendance
 * Java supplémentaire : on lance PowerShell, qui sait piloter COM. Le fichier XML n'est PAS utilisé :
 * iTunes le régénère depuis sa base .itl, le modifier ne change rien à la vraie bibliothèque.
 *
 * Étape 1 (ce fichier) : uniquement des LECTURES — Kind, Location, Artist, Name, Album. Aucune
 * propriété n'est écrite, aucune méthode d'ajout/suppression n'est appelée. Voir ITunesReport.
 */
public final class ITunesCom {

    private ITunesCom() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static volatile Boolean available;

    /** Windows + interface COM iTunes enregistrée. Résultat mis en cache (un appel PowerShell). */
    public static boolean isAvailable() {
        if (!isWindows()) return false;
        Boolean a = available;
        if (a != null) return a;
        boolean ok = false;
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", "if([type]::GetTypeFromProgID('iTunes.Application')){'1'}else{'0'}")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
            ok = out.endsWith("1");
        } catch (Exception ignored) { /* PowerShell absent ou bloqué : indisponible */ }
        available = ok;
        return ok;
    }

    /** Parcourt toute la bibliothèque (environ 17 ms par piste : une trentaine de minutes pour
     *  100 000 pistes) — appelez-le HORS du thread Swing. {@code progress} reçoit un pourcentage 0-100.
     *  {@code cancel} à vrai interrompt proprement (le processus PowerShell est arrêté). */
    public static ITunesReport.ScanResult scan(IntConsumer progress, AtomicBoolean cancel) throws IOException {
        return scan(progress, cancel, 0);
    }

    /** Comme {@link #scan(IntConsumer, AtomicBoolean)} mais ne lit que les {@code limit} premières
     *  pistes si {@code limit > 0} (essais rapides ; 0 = toute la bibliothèque). */
    public static ITunesReport.ScanResult scan(IntConsumer progress, AtomicBoolean cancel, int limit) throws IOException {
        if (!isAvailable()) throw new IOException("iTunes (COM) n'est pas disponible sur cet ordinateur.");
        Path script = Files.createTempFile("opentagger_itunes_scan", ".ps1");
        // BOM UTF-8 : PowerShell 5.1 lit sinon le script en ANSI. Le script lui-même est ASCII.
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = SCAN_SCRIPT.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(script, all);

        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-Limit", String.valueOf(Math.max(0, limit)))
                .redirectErrorStream(true).start();
        ITunesReport.Builder b = new ITunesReport.Builder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cancel != null && cancel.get()) { p.destroyForcibly(); throw new IOException("Analyse annulée."); }
                int[] pr = b.accept(line);
                if (pr != null && progress != null && pr[1] > 0) progress.accept((int) (100L * pr[0] / pr[1]));
            }
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            try { Files.deleteIfExists(script); } catch (IOException ignored) {}
        }
        if (cancel != null && cancel.get()) throw new IOException("Analyse annulée.");
        if (!b.isDone()) {
            ITunesReport.ScanResult partial = b.build();
            throw new IOException("L'analyse s'est arrêtée avant la fin (iTunes fermé ?)"
                    + (partial.diagnostics().isEmpty() ? "" : " — " + String.join(" | ", partial.diagnostics())));
        }
        return b.build();
    }

    /**
     * Script de lecture. Kind 1 = piste « fichier » (ITTrackKindFile) ; les pistes Apple Music /
     * bibliothèque partagée (Kind 5) n'ont pas de fichier local et sont ignorées. Une piste fichier
     * dont {@code Location} est vide a perdu son fichier (convention de l'exemple « dead tracks » du
     * SDK iTunes) ; un {@code Location} qui n'existe plus sur le disque est traité de même.
     */
    private static final String SCAN_SCRIPT = String.join("\r\n",
        "param([int]$Limit = 0)",
        "$ErrorActionPreference = 'Stop'",
        "[Console]::OutputEncoding = [Text.Encoding]::UTF8",
        "function Clean($s) { if ($null -eq $s) { return '' }; ([string]$s) -replace \"[\\t\\r\\n]\", ' ' }",
        "$it = New-Object -ComObject iTunes.Application",
        "$tr = $it.LibraryPlaylist.Tracks",
        "$n = $tr.Count",
        "\"N`t$n\"",
        "if ($Limit -gt 0 -and $Limit -lt $n) { $n = $Limit }",
        "$step = [Math]::Max(1, [int]($n / 200))",
        "for ($i = 1; $i -le $n; $i++) {",
        "  try {",
        "    $t = $tr.Item($i)",
        "    if ($t.Kind -eq 1) {",
        "      $loc = [string]$t.Location",
        "      if ($loc) { \"L`t$(Clean $loc)\" }",
        "      if ((-not $loc) -or (-not [IO.File]::Exists($loc))) {",
        "        \"D`t$(Clean $loc)`t$(Clean $t.Artist)`t$(Clean $t.Name)`t$(Clean $t.Album)\"",
        "      }",
        "    }",
        "  } catch { \"E`t$i`t$(Clean $_.Exception.Message)\" }",
        "  if ($i % $step -eq 0) { \"P`t$i`t$n\" }",
        "}",
        "\"P`t$n`t$n\"",
        "\"DONE\"",
        "");
}
