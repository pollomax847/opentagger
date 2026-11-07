package com.opentagger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Découpe un fichier audio en « étiquettes » et « son », pour pouvoir remettre les étiquettes d'origine sans copier tout
 * le fichier (voir {@link SaveJournal}, l'annulation d'un enregistrement — demande de l'utilisateur, 2026-11-07, comme
 * SongKong). Écrire des tags ne touche jamais au son : MP3, FLAC et M4A se lisent comme
 * {@code [étiquettes du début][son][étiquettes de la fin]}, et remettre les deux bouts d'origine autour du son actuel
 * redonne le fichier d'origine octet pour octet (la taille et une empreinte du son sont vérifiées).
 *
 * <ul>
 *   <li>MP3 : balises ID3v2 (et l'éventuel remplissage jusqu'à la première trame) au début ; APEv2, Lyrics3 et ID3v1
 *       à la fin ;</li>
 *   <li>FLAC : « fLaC » et tous les blocs de métadonnées (commentaires, images…) ;</li>
 *   <li>M4A/MP4 : toutes les boîtes de premier niveau sauf le contenu de « mdat » (le son). Les positions du son notées
 *       dans « moov » restent justes puisque les boîtes d'origine reprennent leur taille d'origine.</li>
 * </ul>
 * Les autres formats (Ogg, Opus, WMA, WAV…) ne sont pas découpés : {@link SaveJournal} en garde une copie entière.
 */
public final class TagSnapshot {

    private TagSnapshot() {}

    /** {@code [0, audioStart)} étiquettes du début, {@code [audioStart, audioEnd)} son, {@code [audioEnd, size)} fin. */
    public record Layout(long size, long audioStart, long audioEnd) {
        public long audioLength() { return audioEnd - audioStart; }
        public long tagBytes()    { return size - audioLength(); }
    }

    /** Taille lue aux deux bouts du son pour son empreinte (le son entier serait trop long à relire sur un disque lent). */
    static final int HASH_WINDOW = 1 << 20;

    /** Découpage du fichier, ou {@code null} si le format n'est pas découpable ou si sa structure n'est pas comprise. */
    public static Layout layout(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size < 16) return null;
            if (n.endsWith(".mp3")) return mp3(ch, size);
            if (n.endsWith(".flac")) return flac(ch, size);
            if (n.endsWith(".m4a") || n.endsWith(".m4b") || n.endsWith(".m4p") || n.endsWith(".mp4") || n.endsWith(".alac"))
                return isoBmff(ch, size);
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    // ── MP3 ─────────────────────────────────────────────────────────────────────────────────────────

    private static Layout mp3(FileChannel ch, long size) throws IOException {
        long pos = skipId3v2(ch, size, 0);
        long start = firstMpegFrame(ch, size, pos);
        if (start < 0) start = pos;
        long end = size;
        // Plusieurs balises de fin peuvent se suivre (APEv2 puis ID3v1, Lyrics3 puis ID3v1) : on les retire tant qu'il y en a.
        for (int guard = 0; guard < 4 && end > start; guard++) {
            long before = end;
            if (end - start >= 128 && "TAG".equals(ascii(ch, end - 128, 3))) end -= 128;
            if (end - start >= 15 && "LYRICS200".equals(ascii(ch, end - 9, 9))) {
                String digits = ascii(ch, end - 15, 6);
                if (digits.matches("\\d{6}")) {
                    long sz = Long.parseLong(digits);
                    if (end - 15 - sz >= start) end -= 15 + sz;
                }
            }
            if (end - start >= 32 && "APETAGEX".equals(ascii(ch, end - 32, 8))) {
                ByteBuffer f = read(ch, end - 32, 32);
                long tagSize = Integer.toUnsignedLong(Integer.reverseBytes(f.getInt(12)));
                int flags = Integer.reverseBytes(f.getInt(20));
                long total = tagSize + ((flags & 0x80000000) != 0 ? 32 : 0);
                if (total >= 32 && end - total >= start) end -= total;
            }
            if (end == before) break;
        }
        return start <= end ? new Layout(size, start, end) : null;
    }

    /** Position après la (ou les) balise(s) ID3v2 qui commencent à {@code pos}. */
    private static long skipId3v2(FileChannel ch, long size, long pos) throws IOException {
        for (int guard = 0; guard < 4 && pos + 10 <= size; guard++) {
            ByteBuffer h = read(ch, pos, 10);
            if (h.get(0) != 'I' || h.get(1) != 'D' || h.get(2) != '3') break;
            int flags = h.get(5) & 0xFF;
            long sz = ((h.get(6) & 0x7F) << 21) | ((h.get(7) & 0x7F) << 14) | ((h.get(8) & 0x7F) << 7) | (h.get(9) & 0x7F);
            long next = pos + 10 + sz + ((flags & 0x10) != 0 ? 10 : 0);
            if (next > size) break;
            pos = next;
        }
        return pos;
    }

    private static final int[][] BITRATES = {
            // MPEG-1 couche I, II, III
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
            // MPEG-2/2.5 couche I, puis II et III
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};
    private static final int[][] RATES = {{11025, 12000, 8000}, {0, 0, 0}, {22050, 24000, 16000}, {44100, 48000, 32000}};

    /** Longueur d'une trame MPEG d'après les octets 2 à 4 de son en-tête, ou -1 si ce n'en est pas un. */
    static int frameLength(int b1, int b2, int b3) {
        int version = (b1 >> 3) & 3, layer = (b1 >> 1) & 3, br = (b2 >> 4) & 0xF, sr = (b2 >> 2) & 3, pad = (b2 >> 1) & 1;
        if (version == 1 || layer == 0 || br == 0 || br == 15 || sr == 3) return -1;
        boolean v1 = version == 3;
        int layerIdx = 3 - layer;                    // 0 = couche I, 1 = II, 2 = III
        int kbps = v1 ? BITRATES[layerIdx][br] : BITRATES[layerIdx == 0 ? 3 : 4][br];
        int rate = RATES[version][sr];
        if (kbps == 0 || rate == 0) return -1;
        if (layerIdx == 0) return (12 * kbps * 1000 / rate + pad) * 4;
        int coef = layerIdx == 2 && !v1 ? 72 : 144;
        return coef * kbps * 1000 / rate + pad;
    }

    /** Première trame MPEG suivie d'une autre trame valide, cherchée dans les 256 Ko après {@code from} ; -1 sinon. */
    private static long firstMpegFrame(FileChannel ch, long size, long from) throws IOException {
        int window = (int) Math.min(256 * 1024 + 4, size - from);
        if (window < 4) return -1;
        ByteBuffer b = read(ch, from, window);
        for (int i = 0; i + 4 <= window; i++) {
            if ((b.get(i) & 0xFF) != 0xFF || (b.get(i + 1) & 0xE0) != 0xE0) continue;
            int len = frameLength(b.get(i + 1) & 0xFF, b.get(i + 2) & 0xFF, b.get(i + 3) & 0xFF);
            if (len < 24) continue;
            long next = from + i + len;
            if (next + 2 > size) return from + i;        // dernière trame du fichier
            ByteBuffer nb = read(ch, next, 2);
            if ((nb.get(0) & 0xFF) == 0xFF && (nb.get(1) & 0xE0) == 0xE0) return from + i;
        }
        return -1;
    }

    // ── FLAC ────────────────────────────────────────────────────────────────────────────────────────

    private static Layout flac(FileChannel ch, long size) throws IOException {
        long pos = skipId3v2(ch, size, 0);
        if (!"fLaC".equals(ascii(ch, pos, 4))) return null;
        pos += 4;
        for (int guard = 0; guard < 10_000; guard++) {
            if (pos + 4 > size) return null;
            ByteBuffer h = read(ch, pos, 4);
            boolean last = (h.get(0) & 0x80) != 0;
            long len = ((h.get(1) & 0xFFL) << 16) | ((h.get(2) & 0xFFL) << 8) | (h.get(3) & 0xFFL);
            pos += 4 + len;
            if (pos > size) return null;
            if (last) break;
        }
        long end = size;
        if (end - pos >= 128 && "TAG".equals(ascii(ch, end - 128, 3))) end -= 128;
        return new Layout(size, pos, end);
    }

    // ── M4A / MP4 ───────────────────────────────────────────────────────────────────────────────────

    private static Layout isoBmff(FileChannel ch, long size) throws IOException {
        long pos = 0, mdatStart = -1, mdatEnd = -1;
        int mdats = 0;
        while (pos < size) {
            if (pos + 8 > size) return null;
            ByteBuffer h = read(ch, pos, 8);
            long box = Integer.toUnsignedLong(h.getInt(0));
            String type = new String(new byte[]{h.get(4), h.get(5), h.get(6), h.get(7)}, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (box == 1) {
                if (pos + 16 > size) return null;
                box = read(ch, pos + 8, 8).getLong(0);
                header = 16;
            } else if (box == 0) {
                box = size - pos;
            }
            if (box < header || pos + box > size) return null;
            if (!type.chars().allMatch(c -> c >= 0x20 && c < 0x7F || c == 0xA9)) return null;
            if (type.equals("mdat")) {
                mdats++;
                mdatStart = pos + header;
                mdatEnd = pos + box;
            }
            pos += box;
        }
        if (mdats != 1) return null;
        return new Layout(size, mdatStart, mdatEnd);
    }

    // ── Empreintes, lecture, reconstruction ────────────────────────────────────────────────────────

    /** Empreinte du son : sa longueur, son premier et son dernier Mo. */
    public static String audioHash(Path p, Layout l) throws IOException {
        MessageDigest md = sha1();
        md.update(ByteBuffer.allocate(8).putLong(0, l.audioLength()));
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            long len = l.audioLength();
            int head = (int) Math.min(HASH_WINDOW, len);
            md.update(read(ch, l.audioStart(), head));
            if (len > HASH_WINDOW) {
                int tail = (int) Math.min(HASH_WINDOW, len - HASH_WINDOW);
                md.update(read(ch, l.audioEnd() - tail, tail));
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Empreinte des étiquettes (début + fin) : change dès qu'un tag change. */
    public static String tagHash(Path p, Layout l) throws IOException {
        MessageDigest md = sha1();
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            digestRange(ch, md, 0, l.audioStart());
            digestRange(ch, md, l.audioEnd(), l.size() - l.audioEnd());
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Empreinte d'un fichier non découpé : sa taille, son premier et son dernier Mo (les tags sont au début ou à la fin). */
    public static String fileHash(Path p) throws IOException {
        long size = Files.size(p);
        return audioHash(p, new Layout(size, 0, size));
    }

    /** Copie les étiquettes (début puis fin) dans {@code out}. */
    public static void copyTags(Path p, Layout l, Path out) throws IOException {
        try (FileChannel in = FileChannel.open(p, StandardOpenOption.READ);
             FileChannel o = FileChannel.open(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            transfer(in, 0, l.audioStart(), o);
            transfer(in, l.audioEnd(), l.size() - l.audioEnd(), o);
            o.force(true);
        }
    }

    /**
     * Écrit dans {@code out} : les {@code prefixLength} premiers octets de {@code tags}, le son actuel de {@code current},
     * puis le reste de {@code tags}.
     */
    public static void rebuild(Path current, Layout cur, Path tags, long prefixLength, Path out) throws IOException {
        try (FileChannel t = FileChannel.open(tags, StandardOpenOption.READ);
             FileChannel in = FileChannel.open(current, StandardOpenOption.READ);
             FileChannel o = FileChannel.open(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (prefixLength < 0 || prefixLength > t.size()) throw new IOException("étiquettes enregistrées incomplètes");
            transfer(t, 0, prefixLength, o);
            transfer(in, cur.audioStart(), cur.audioLength(), o);
            transfer(t, prefixLength, t.size() - prefixLength, o);
            o.force(true);
        }
    }

    private static void transfer(FileChannel in, long pos, long len, FileChannel out) throws IOException {
        long done = 0;
        while (done < len) {
            long n = in.transferTo(pos + done, len - done, out);
            if (n <= 0) throw new IOException("lecture interrompue");
            done += n;
        }
    }

    private static void digestRange(FileChannel ch, MessageDigest md, long pos, long len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
        long done = 0;
        while (done < len) {
            buf.clear();
            buf.limit((int) Math.min(buf.capacity(), len - done));
            int n = ch.read(buf, pos + done);
            if (n <= 0) throw new IOException("lecture interrompue");
            buf.flip();
            md.update(buf);
            done += n;
        }
    }

    private static ByteBuffer read(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) {
            int n = ch.read(b, pos + b.position());
            if (n <= 0) break;
        }
        b.flip();
        if (b.remaining() < len) throw new IOException("fin de fichier inattendue");
        return b;
    }

    private static String ascii(FileChannel ch, long pos, int len) throws IOException {
        if (pos < 0 || pos + len > ch.size()) return "";
        ByteBuffer b = read(ch, pos, len);
        byte[] a = new byte[len];
        b.get(a);
        return new String(a, StandardCharsets.ISO_8859_1);
    }

    private static MessageDigest sha1() {
        try { return MessageDigest.getInstance("SHA-1"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
