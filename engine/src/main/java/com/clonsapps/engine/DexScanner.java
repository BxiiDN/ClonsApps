package com.clonsapps.engine;

import com.clonsapps.engine.util.Le;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Membaca tabel string DEX (tanpa mengubah kode) untuk mendeteksi risiko setelah paket diganti:
 * authority/permission lama yang ditulis langsung di kode, serta pustaka pemeriksa lisensi/integritas.
 */
public final class DexScanner {
    private DexScanner() {
    }

    public static final class Result {
        public final Set<String> exactMatches = new HashSet<>();
        public final Set<String> prefixMatches = new HashSet<>();
        public int stringCount;
    }

    public static boolean isDex(byte[] b) {
        return b.length > 0x70 && b[0] == 'd' && b[1] == 'e' && b[2] == 'x' && b[3] == '\n';
    }

    public static void scan(byte[] dex, Collection<String> exact, Collection<String> prefixes, Result out) {
        if (!isDex(dex)) {
            return;
        }
        int count = Le.i32(dex, 0x38);
        int idsOff = Le.i32(dex, 0x3C);
        if (count < 0 || idsOff < 0 || (long) idsOff + 4L * count > dex.length) {
            return;
        }
        Set<String> exactSet = exact instanceof Set ? (Set<String>) exact : new HashSet<>(exact);
        out.stringCount += count;
        for (int i = 0; i < count; i++) {
            int off = Le.i32(dex, idsOff + 4 * i);
            if (off <= 0 || off >= dex.length) {
                continue;
            }
            int p = off;
            // uleb128 panjang UTF-16
            while (p < dex.length && (dex[p] & 0x80) != 0) {
                p++;
            }
            p++;
            if (p >= dex.length) {
                continue;
            }
            String s = decodeMutf8(dex, p);
            if (s == null) {
                continue;
            }
            if (exactSet.contains(s)) {
                out.exactMatches.add(s);
            }
            for (String pre : prefixes) {
                if (s.startsWith(pre)) {
                    out.prefixMatches.add(pre);
                }
            }
        }
    }

    static String decodeMutf8(byte[] b, int p) {
        StringBuilder sb = new StringBuilder();
        while (p < b.length) {
            int c = b[p] & 0xFF;
            if (c == 0) {
                return sb.toString();
            }
            if (c < 0x80) {
                sb.append((char) c);
                p++;
            } else if ((c & 0xE0) == 0xC0 && p + 1 < b.length) {
                sb.append((char) (((c & 0x1F) << 6) | (b[p + 1] & 0x3F)));
                p += 2;
            } else if ((c & 0xF0) == 0xE0 && p + 2 < b.length) {
                sb.append((char) (((c & 0x0F) << 12) | ((b[p + 1] & 0x3F) << 6) | (b[p + 2] & 0x3F)));
                p += 3;
            } else {
                return null;
            }
            if (sb.length() > 4096) {
                return null;
            }
        }
        return null;
    }
}
