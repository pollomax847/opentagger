param([string]$Mode, [int]$Track = 0, [string]$Out = '', [string]$Drive = '')
# Lecture native d'un CD audio sous Windows (DeviceIoControl : IOCTL_CDROM_READ_TOC / IOCTL_CDROM_RAW_READ).
# Sortie, une ligne par evenement, champs separes par des tabulations :
#   T n sectors audio(1/0)   piste de la table des pistes     P done total   progression
#   RIPPED errors            piste extraite (secteurs illisibles remplaces par du silence)
#   ERR CODE message         echec (CODE : NODRIVE, NODISC, OPEN, TOC, TRACK, READ)     DONE   fin normale
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

public class CdFail : Exception { public string Code; public CdFail(string code, string msg) : base(msg) { Code = code; } }

public static class Cd {
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern SafeFileHandle CreateFile(string name, uint access, uint share, IntPtr sec, uint disp, uint flags, IntPtr tmpl);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool DeviceIoControl(SafeFileHandle h, uint code, byte[] inb, int inSize, byte[] outb, int outSize, out int ret, IntPtr ov);

    const int RAW = 2352;

    public static SafeFileHandle Open(string letter) {
        SafeFileHandle h = CreateFile("\\\\.\\" + letter + ":", 0x80000000u, 3u, IntPtr.Zero, 3u, 0u, IntPtr.Zero);
        if (h.IsInvalid) throw new CdFail("OPEN", "code " + Marshal.GetLastWin32Error());
        return h;
    }

    // Entrees {numero, lba, control} y compris la piste 0xAA (fin du disque).
    public static List<int[]> Toc(SafeFileHandle h) {
        byte[] buf = new byte[804];
        int ret;
        if (!DeviceIoControl(h, 0x00024000u, null, 0, buf, buf.Length, out ret, IntPtr.Zero))
            throw new CdFail("TOC", "code " + Marshal.GetLastWin32Error());
        int n = (ret - 4) / 8;
        List<int[]> list = new List<int[]>();
        for (int i = 0; i < n; i++) {
            int o = 4 + i * 8;
            int control = buf[o + 1] & 0x0F;
            int number = buf[o + 2];
            int lba = (buf[o + 5] * 60 + buf[o + 6]) * 75 + buf[o + 7] - 150;
            list.Add(new int[] { number, lba, control });
        }
        return list;
    }

    static bool ReadSectors(SafeFileHandle h, int lba, int count, byte[] dst, int dstOff) {
        byte[] inb = new byte[16];
        BitConverter.GetBytes((long)lba * 2048L).CopyTo(inb, 0);
        BitConverter.GetBytes((uint)count).CopyTo(inb, 8);
        BitConverter.GetBytes(2).CopyTo(inb, 12);                  // TrackMode = CDDA
        byte[] outb = new byte[count * RAW];
        int ret;
        if (!DeviceIoControl(h, 0x0002403Eu, inb, inb.Length, outb, outb.Length, out ret, IntPtr.Zero)) return false;
        Buffer.BlockCopy(outb, 0, dst, dstOff, count * RAW);
        return true;
    }

    public static int Rip(SafeFileHandle h, int start, int total, string path) {
        int errors = 0;
        using (FileStream fs = new FileStream(path, FileMode.Create, FileAccess.Write)) {
            long dataLen = (long)total * RAW;
            BinaryWriter w = new BinaryWriter(fs);
            w.Write(new char[] { 'R', 'I', 'F', 'F' }); w.Write((int)(36 + dataLen));
            w.Write(new char[] { 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ' }); w.Write(16);
            w.Write((short)1); w.Write((short)2); w.Write(44100); w.Write(44100 * 4); w.Write((short)4); w.Write((short)16);
            w.Write(new char[] { 'd', 'a', 't', 'a' }); w.Write((int)dataLen);
            const int CHUNK = 16;
            byte[] block = new byte[CHUNK * RAW];
            int done = 0, lastPct = -1;
            while (done < total) {
                int n = Math.Min(CHUNK, total - done);
                bool ok = false;
                for (int attempt = 0; attempt < 3 && !ok; attempt++) ok = ReadSectors(h, start + done, n, block, 0);
                if (!ok) {
                    for (int s = 0; s < n; s++) {                 // secteur par secteur : isole l'erreur
                        bool sok = false;
                        for (int attempt = 0; attempt < 3 && !sok; attempt++) sok = ReadSectors(h, start + done + s, 1, block, s * RAW);
                        if (!sok) { Array.Clear(block, s * RAW, RAW); errors++; }
                    }
                }
                w.Write(block, 0, n * RAW);
                done += n;
                int pct = (int)(100L * done / total);
                if (pct != lastPct) { lastPct = pct; Console.Out.WriteLine("P\t" + done + "\t" + total); Console.Out.Flush(); }
            }
            w.Flush();
        }
        return errors;
    }
}
'@

function Fail($code, $msg) { "ERR`t$code`t$msg"; exit 0 }

$letter = $Drive.TrimEnd(':', '\')
if ($Mode -eq 'eject') {
    # Éjection du disque (sans ouvrir le lecteur en lecture brute) : verbe « Éjecter » de l'Explorateur sur le lecteur concerné.
    if (-not $letter) {
        $cd = @([IO.DriveInfo]::GetDrives() | Where-Object { $_.DriveType -eq 'CDRom' })
        if ($cd.Count -eq 0) { Fail 'NODRIVE' 'aucun lecteur de CD' }
        $letter = $cd[0].Name.Substring(0, 1)
    }
    try {
        (New-Object -ComObject Shell.Application).NameSpace(17).ParseName("$($letter):").InvokeVerb('Eject')
        'DONE'
    } catch { Fail 'EJECT' $_.Exception.Message }
    exit 0
}
if (-not $letter) {
    $cd = @([IO.DriveInfo]::GetDrives() | Where-Object { $_.DriveType -eq 'CDRom' })
    if ($cd.Count -eq 0) { Fail 'NODRIVE' 'aucun lecteur de CD' }
    $ready = @($cd | Where-Object { $_.IsReady })
    if ($ready.Count -eq 0) { Fail 'NODISC' 'aucun disque dans le lecteur' }
    $letter = $ready[0].Name.Substring(0, 1)
}
try {
    $h = [Cd]::Open($letter)
} catch [CdFail] { Fail $_.Exception.Code $_.Exception.Message } catch { Fail 'OPEN' $_.Exception.Message }
try {
    try { $toc = [Cd]::Toc($h) } catch [CdFail] { Fail 'NODISC' $_.Exception.Message }
    $tracks = @($toc | Where-Object { $_[0] -ge 1 -and $_[0] -le 99 })
    $leadout = @($toc | Where-Object { $_[0] -eq 170 })
    if ($tracks.Count -eq 0 -or $leadout.Count -eq 0) { Fail 'NODISC' 'table des pistes vide' }
    $end = $leadout[0][1]
    $info = @()
    for ($i = 0; $i -lt $tracks.Count; $i++) {
        $next = if ($i + 1 -lt $tracks.Count) { $tracks[$i + 1][1] } else { $end }
        $isAudio = if (($tracks[$i][2] -band 4) -eq 0) { 1 } else { 0 }
        $info += ,@($tracks[$i][0], $tracks[$i][1], ($next - $tracks[$i][1]), $isAudio)
    }
    if ($Mode -eq 'toc') {
        foreach ($t in $info) { "T`t$($t[0])`t$($t[2])`t$($t[3])" }
        'DONE'
    } elseif ($Mode -eq 'rip') {
        $t = $info | Where-Object { $_[0] -eq $Track } | Select-Object -First 1
        if (-not $t) { Fail 'TRACK' "piste $Track introuvable" }
        if ($t[3] -ne 1) { Fail 'TRACK' "la piste $Track est une piste de donnees" }
        $errs = [Cd]::Rip($h, $t[1], $t[2], $Out)
        "RIPPED`t$errs"
        'DONE'
    } else {
        Fail 'READ' "mode inconnu : $Mode"
    }
} catch [CdFail] { Fail $_.Exception.Code $_.Exception.Message } catch { Fail 'READ' $_.Exception.Message }
finally { if ($h) { $h.Dispose() } }
