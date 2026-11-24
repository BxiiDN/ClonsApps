package com.clonsapps.engine.zip;

import com.clonsapps.engine.CloneException;
import com.clonsapps.engine.ErrorCode;
import com.clonsapps.engine.util.Le;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Pembaca ZIP/APK minimal yang ketat: membaca central directory, memvalidasi local header,
 * mendeteksi ZIP64, enkripsi, metode kompresi tak dikenal, dan entri ganda.
 * Data mentah (terkompresi) bisa disalin apa adanya tanpa kompresi ulang.
 */
public final class ZipArchive implements Closeable {
    public static final int METHOD_STORED = 0;
    public static final int METHOD_DEFLATED = 8;
    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_SIG = 0x02014b50;
    private static final int LFH_SIG = 0x04034b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    /** Batas aman untuk entri yang dibaca utuh ke memori (manifest, arsc, xml, dex). */
    public static final long MAX_IN_MEMORY = 512L * 1024 * 1024;

    public static final class Entry {
        public final String name;
        final byte[] rawName;
        public final int method;
        final int flags;
        public final long crc;
        public final long compressedSize;
        public final long size;
        final int dosTime;
        final int dosDate;
        final int versionMadeBy;
        final int externalAttrs;
        final long localHeaderOffset;
        long dataOffset;

        Entry(String name, byte[] rawName, int method, int flags, long crc, long compressedSize, long size,
              int dosTime, int dosDate, int versionMadeBy, int externalAttrs, long localHeaderOffset) {
            this.name = name;
            this.rawName = rawName;
            this.method = method;
            this.flags = flags;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.size = size;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.versionMadeBy = versionMadeBy;
            this.externalAttrs = externalAttrs;
            this.localHeaderOffset = localHeaderOffset;
        }

        public long dataOffset() {
            return dataOffset;
        }

        public int dosTime() {
            return dosTime;
        }

        public int dosDate() {
            return dosDate;
        }
    }

    private final File file;
    private final RandomAccessFile raf;
    private final long length;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private ZipArchive(File file, RandomAccessFile raf, long length, List<Entry> entries, Map<String, Entry> byName) {
        this.file = file;
        this.raf = raf;
        this.length = length;
        this.entries = entries;
        this.byName = byName;
    }

    public static ZipArchive open(File file) throws CloneException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            long len = raf.length();
            if (len < 22) {
                throw new CloneException(ErrorCode.CORRUPT_ZIP, file.getName() + ": ukuran " + len + " byte, terlalu kecil");
            }
            int tailLen = (int) Math.min(len, 22 + 65535);
            byte[] tail = new byte[tailLen];
            raf.seek(len - tailLen);
            raf.readFully(tail);
            int eocd = -1;
            for (int i = tailLen - 22; i >= 0; i--) {
                if (Le.i32(tail, i) == EOCD_SIG) {
                    int commentLen = Le.u16(tail, i + 20);
                    if (i + 22 + commentLen == tailLen) {
                        eocd = i;
                        break;
                    }
                }
            }
            if (eocd < 0) {
                throw new CloneException(ErrorCode.CORRUPT_ZIP, file.getName() + ": akhir arsip (EOCD) tidak ditemukan; file kemungkinan terpotong");
            }
            if (eocd >= 20 && Le.i32(tail, eocd - 20) == ZIP64_LOCATOR_SIG) {
                throw new CloneException(ErrorCode.ZIP64_UNSUPPORTED, file.getName());
            }
            int total = Le.u16(tail, eocd + 10);
            long cdSize = Le.u32(tail, eocd + 12);
            long cdOffset = Le.u32(tail, eocd + 16);
            if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                throw new CloneException(ErrorCode.ZIP64_UNSUPPORTED, file.getName());
            }
            long eocdAbs = len - tailLen + eocd;
            if (cdOffset + cdSize > eocdAbs) {
                throw new CloneException(ErrorCode.CORRUPT_ZIP, file.getName() + ": central directory di luar batas file");
            }
            if (cdSize > Integer.MAX_VALUE) {
                throw new CloneException(ErrorCode.ZIP64_UNSUPPORTED, file.getName());
            }
            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOffset);
            raf.readFully(cd);
            List<Entry> list = new ArrayList<>(total);
            Map<String, Entry> map = new HashMap<>();
            int p = 0;
            for (int i = 0; i < total; i++) {
                if (p + 46 > cd.length || Le.i32(cd, p) != CD_SIG) {
                    throw new CloneException(ErrorCode.CORRUPT_ZIP, file.getName() + ": entri central directory #" + i + " rusak");
                }
                int madeBy = Le.u16(cd, p + 4);
                int flags = Le.u16(cd, p + 8);
                int method = Le.u16(cd, p + 10);
                int time = Le.u16(cd, p + 12);
                int date = Le.u16(cd, p + 14);
                long crc = Le.u32(cd, p + 16);
                long csize = Le.u32(cd, p + 20);
                long usize = Le.u32(cd, p + 24);
                int nameLen = Le.u16(cd, p + 28);
                int extraLen = Le.u16(cd, p + 30);
                int commentLen = Le.u16(cd, p + 32);
                int extAttr = Le.i32(cd, p + 38);
                long lho = Le.u32(cd, p + 42);
                if (p + 46 + nameLen + extraLen + commentLen > cd.length) {
                    throw new CloneException(ErrorCode.CORRUPT_ZIP, file.getName() + ": nama entri #" + i + " di luar batas");
                }
                byte[] rawName = new byte[nameLen];
                System.arraycopy(cd, p + 46, rawName, 0, nameLen);
                String name = new String(rawName, StandardCharsets.UTF_8);
                if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || lho == 0xFFFFFFFFL) {
                    throw new CloneException(ErrorCode.ZIP64_UNSUPPORTED, file.getName() + ": " + name);
                }
                if ((flags & 0x1) != 0) {
                    throw new CloneException(ErrorCode.ENCRYPTED_ENTRY, name);
                }
                if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                    throw new CloneException(ErrorCode.UNSUPPORTED_COMPRESSION, name + " (metode " + method + ")");
                }
                if (method == METHOD_STORED && csize != usize) {
                    throw new CloneException(ErrorCode.CORRUPT_ZIP, name + ": ukuran entri STORED tidak konsisten");
                }
                Entry e = new Entry(name, rawName, method, flags, crc, csize, usize, time, date, madeBy, extAttr, lho);
                if (map.put(name, e) != null) {
                    throw new CloneException(ErrorCode.DUPLICATE_ENTRY, name);
                }
                list.add(e);
                p += 46 + nameLen + extraLen + commentLen;
            }
            byte[] lfh = new byte[30];
            for (Entry e : list) {
                if (e.localHeaderOffset + 30 > cdOffset) {
                    throw new CloneException(ErrorCode.CORRUPT_ZIP, e.name + ": local header di luar batas");
                }
                raf.seek(e.localHeaderOffset);
                raf.readFully(lfh);
                if (Le.i32(lfh, 0) != LFH_SIG) {
                    throw new CloneException(ErrorCode.CORRUPT_ZIP, e.name + ": tanda tangan local header salah");
                }
                int nl = Le.u16(lfh, 26);
                int xl = Le.u16(lfh, 28);
                e.dataOffset = e.localHeaderOffset + 30 + nl + xl;
                if (e.dataOffset + e.compressedSize > cdOffset) {
                    throw new CloneException(ErrorCode.CORRUPT_ZIP, e.name + ": data entri melewati batas arsip");
                }
            }
            ZipArchive za = new ZipArchive(file, raf, len, Collections.unmodifiableList(list), map);
            raf = null;
            return za;
        } catch (IOException e) {
            throw new CloneException(ErrorCode.CORRUPT_ZIP, file.getName() + ": " + e.getMessage(), e);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public File file() {
        return file;
    }

    public long length() {
        return length;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry get(String name) {
        return byName.get(name);
    }

    public boolean has(String name) {
        return byName.containsKey(name);
    }

    /** Membaca bagian data mentah (masih terkompresi bila DEFLATED). */
    public synchronized int readRaw(Entry e, long offsetInData, byte[] buf, int off, int len) throws IOException {
        long remaining = e.compressedSize - offsetInData;
        if (remaining <= 0) {
            return -1;
        }
        int n = (int) Math.min(len, remaining);
        raf.seek(e.dataOffset + offsetInData);
        raf.readFully(buf, off, n);
        return n;
    }

    /** Membaca isi entri utuh (didekompresi) dan memvalidasi CRC-32 serta ukurannya. */
    public byte[] read(Entry e) throws CloneException {
        if (e.size > MAX_IN_MEMORY || e.compressedSize > MAX_IN_MEMORY) {
            throw new CloneException(ErrorCode.CORRUPT_ZIP, e.name + ": entri terlalu besar untuk diproses (" + e.size + " byte)");
        }
        try {
            byte[] raw = new byte[(int) e.compressedSize];
            int got = 0;
            while (got < raw.length) {
                int n = readRaw(e, got, raw, got, raw.length - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            byte[] out;
            if (e.method == METHOD_STORED) {
                out = raw;
            } else {
                Inflater inf = new Inflater(true);
                try {
                    inf.setInput(raw);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(e.size, Integer.MAX_VALUE - 8));
                    byte[] chunk = new byte[64 * 1024];
                    while (!inf.finished()) {
                        int n = inf.inflate(chunk);
                        if (n == 0) {
                            if (inf.needsInput() || inf.needsDictionary()) {
                                break;
                            }
                        }
                        bos.write(chunk, 0, n);
                        if (bos.size() > e.size) {
                            break;
                        }
                    }
                    out = bos.toByteArray();
                } finally {
                    inf.end();
                }
            }
            if (out.length != e.size) {
                throw new CloneException(ErrorCode.CRC_MISMATCH, e.name + ": ukuran " + out.length + " ≠ " + e.size);
            }
            CRC32 crc = new CRC32();
            crc.update(out, 0, out.length);
            if (crc.getValue() != e.crc) {
                throw new CloneException(ErrorCode.CRC_MISMATCH, e.name);
            }
            return out;
        } catch (DataFormatException ex) {
            throw new CloneException(ErrorCode.CRC_MISMATCH, e.name + ": data deflate rusak", ex);
        } catch (IOException ex) {
            throw new CloneException(ErrorCode.IO_ERROR, e.name + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (IOException ignored) {
        }
    }
}
