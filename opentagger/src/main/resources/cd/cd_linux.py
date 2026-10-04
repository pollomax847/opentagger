#!/usr/bin/env python3
# Lecture native d'un CD audio sous Linux (ioctl du pilote cdrom : CDROMREADTOCHDR / CDROMREADTOCENTRY /
# CDROMREADAUDIO) — sans cdparanoia. Meme protocole de sortie que cd_windows.ps1 (champs separes par des tabulations) :
#   T n sectors audio(1/0)   P done total   RIPPED errors   ERR CODE message   DONE
# Usage : cd_linux.py toc [peripherique]   |   cd_linux.py rip <piste> <fichier.wav> [peripherique]
import ctypes, ctypes.util, errno, os, struct, sys

CDROMREADTOCHDR = 0x5305
CDROMREADTOCENTRY = 0x5306
CDROMREADAUDIO = 0x530E
CDROM_LBA = 0x01
CDROM_LEADOUT = 0xAA
RAW = 2352


class TocHdr(ctypes.Structure):
    _fields_ = [("first", ctypes.c_ubyte), ("last", ctypes.c_ubyte)]


class TocEntry(ctypes.Structure):
    _fields_ = [("track", ctypes.c_ubyte), ("adrctrl", ctypes.c_ubyte), ("format", ctypes.c_ubyte),
                ("addr", ctypes.c_int), ("datamode", ctypes.c_ubyte)]


class ReadAudio(ctypes.Structure):
    _fields_ = [("addr", ctypes.c_int), ("addr_format", ctypes.c_ubyte), ("nframes", ctypes.c_int),
                ("buf", ctypes.POINTER(ctypes.c_ubyte))]


libc = ctypes.CDLL(ctypes.util.find_library("c") or None, use_errno=True)
libc.ioctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_void_p]


def out(*fields):
    sys.stdout.write("\t".join(str(f) for f in fields) + "\n")
    sys.stdout.flush()


def fail(code, msg):
    out("ERR", code, msg)
    sys.exit(0)


def open_device(path):
    candidates = [path] if path else ["/dev/cdrom", "/dev/sr0", "/dev/sr1", "/dev/sr2", "/dev/sr3"]
    seen = False
    last = None
    for p in candidates:
        if not os.path.exists(p):
            continue
        seen = True
        try:
            return os.open(p, os.O_RDONLY | os.O_NONBLOCK)
        except OSError as e:
            last = e
    if not seen:
        fail("NODRIVE", "aucun lecteur de CD")
    fail("OPEN", "acces refuse ou lecteur occupe (%s)" % (last,))


def toc(fd):
    hdr = TocHdr()
    if libc.ioctl(fd, CDROMREADTOCHDR, ctypes.byref(hdr)) != 0:
        e = ctypes.get_errno()
        fail("NODISC", "aucun disque dans le lecteur (errno %d)" % e)
    tracks = []
    for n in list(range(hdr.first, hdr.last + 1)) + [CDROM_LEADOUT]:
        ent = TocEntry()
        ent.track = n
        ent.format = CDROM_LBA
        if libc.ioctl(fd, CDROMREADTOCENTRY, ctypes.byref(ent)) != 0:
            fail("TOC", "lecture de la table des pistes impossible (errno %d)" % ctypes.get_errno())
        tracks.append((n, ent.addr, (ent.adrctrl & 0x0F)))   # controle = quartet bas
    return tracks


def track_info(fd):
    entries = toc(fd)
    leadout = [t for t in entries if t[0] == CDROM_LEADOUT]
    real = [t for t in entries if t[0] != CDROM_LEADOUT]
    if not real or not leadout:
        fail("NODISC", "table des pistes vide")
    info = []
    for i, t in enumerate(real):
        nxt = real[i + 1][1] if i + 1 < len(real) else leadout[0][1]
        info.append((t[0], t[1], nxt - t[1], 0 if (t[2] & 4) else 1))
    return info


def read_sectors(fd, lba, count, buf):
    ra = ReadAudio()
    ra.addr = lba
    ra.addr_format = CDROM_LBA
    ra.nframes = count
    ra.buf = ctypes.cast(buf, ctypes.POINTER(ctypes.c_ubyte))
    return libc.ioctl(fd, CDROMREADAUDIO, ctypes.byref(ra)) == 0


def rip(fd, start, total, path):
    errors = 0
    chunk = 8
    buf = ctypes.create_string_buffer(chunk * RAW)
    data_len = total * RAW
    with open(path, "wb") as f:
        f.write(b"RIFF" + struct.pack("<I", 36 + data_len) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 2, 44100, 44100 * 4, 4, 16))
        f.write(b"data" + struct.pack("<I", data_len))
        done = 0
        last_pct = -1
        while done < total:
            n = min(chunk, total - done)
            ok = False
            for _ in range(3):
                if read_sectors(fd, start + done, n, buf):
                    ok = True
                    break
            if ok:
                f.write(buf.raw[:n * RAW])
            else:
                for s in range(n):                       # secteur par secteur : isole l'erreur
                    one = ctypes.create_string_buffer(RAW)
                    sok = any(read_sectors(fd, start + done + s, 1, one) for _ in range(3))
                    if sok:
                        f.write(one.raw)
                    else:
                        f.write(b"\x00" * RAW)
                        errors += 1
            done += n
            pct = 100 * done // total
            if pct != last_pct:
                last_pct = pct
                out("P", done, total)
    return errors


def main():
    if len(sys.argv) < 2:
        fail("READ", "usage : toc | rip")
    mode = sys.argv[1]
    if mode == "toc":
        fd = open_device(sys.argv[2] if len(sys.argv) > 2 else "")
        for t in track_info(fd):
            out("T", t[0], t[2], t[3])
        out("DONE")
    elif mode == "rip":
        if len(sys.argv) < 4:
            fail("READ", "usage : rip <piste> <fichier>")
        track = int(sys.argv[2])
        fd = open_device(sys.argv[4] if len(sys.argv) > 4 else "")
        info = [t for t in track_info(fd) if t[0] == track]
        if not info:
            fail("TRACK", "piste %d introuvable" % track)
        if info[0][3] != 1:
            fail("TRACK", "la piste %d est une piste de donnees" % track)
        errors = rip(fd, info[0][1], info[0][2], sys.argv[3])
        out("RIPPED", errors)
        out("DONE")
    else:
        fail("READ", "mode inconnu : " + mode)


main()
