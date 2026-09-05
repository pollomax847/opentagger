package com.opentagger;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Répare un texte de tag UTF-8 mal réinterprété en Latin-1 lors d'une écriture antérieure par un
 * autre outil ("é" écrit en UTF-8 (0xC3 0xA9) puis relu comme deux caractères Latin-1 "Ã©").
 * Utilisé par MainFrame.fixEncoding() (Outils → Re-traitement).
 *
 * Approche round-trip (même principe que la bibliothèque Python ftfy) plutôt qu'une liste de motifs
 * connus ("Ã©", "â€™"...) : plus générale, et surtout auto-vérifiante — un texte réellement en
 * Latin-1 correct (ex. un seul caractère accentué isolé) échoue au ré-décodage UTF-8 strict et n'est
 * donc jamais touché, sans avoir à énumérer tous les cas.
 */
public class EncodingFixer {

    public static boolean isSuspect(String s) {
        return attemptFix(s) != null;
    }

    public static String fix(String s) {
        String fixed = attemptFix(s);
        return fixed != null ? fixed : s;
    }

    private static String attemptFix(String s) {
        if (s == null || s.isEmpty()) return null;

        // Rien à réparer sur du pur ASCII : la mojibake UTF-8-lu-en-Latin-1 nécessite au moins un
        // caractère >= 0x80 côté source.
        boolean hasHighByte = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) { hasHighByte = true; break; }
        }
        if (!hasHighByte) return null;

        byte[] latin1Bytes;
        try {
            CharsetEncoder enc = StandardCharsets.ISO_8859_1.newEncoder();
            if (!enc.canEncode(s)) return null; // caractère hors Latin-1 -> pas ce motif de mojibake
            latin1Bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return null;
        }

        String reDecoded;
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            reDecoded = dec.decode(ByteBuffer.wrap(latin1Bytes)).toString();
        } catch (Exception e) {
            return null; // pas une séquence UTF-8 valide -> texte déjà correct, ne pas y toucher
        }

        if (reDecoded.equals(s)) return null;
        // Fidélité du round-trip : le résultat, ré-encodé en UTF-8, doit redonner exactement les
        // octets Latin-1 de départ — filtre les faux positifs plutôt qu'un fixage à l'aveugle.
        if (!Arrays.equals(reDecoded.getBytes(StandardCharsets.UTF_8), latin1Bytes)) return null;

        return reDecoded;
    }
}
