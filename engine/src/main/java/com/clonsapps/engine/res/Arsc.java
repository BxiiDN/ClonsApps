package com.clonsapps.engine.res;

import com.clonsapps.engine.CloneException;
import com.clonsapps.engine.ErrorCode;
import com.clonsapps.engine.util.ByteSink;
import com.clonsapps.engine.util.Le;

import java.util.ArrayList;
import java.util.List;

/**
 * resources.arsc: hanya operasi yang dibutuhkan cloning, dilakukan secara terarah:
 * mengganti nama paket pada chunk package dan library, mencari entri resource per konfigurasi,
 * menambah string ke global pool, dan mengarahkan entri ke string (path file) lain.
 * Struktur lain tidak disentuh.
 */
public final class Arsc {
    static final int TABLE_TYPE = 0x0002;
    static final int PACKAGE_TYPE = 0x0200;
    static final int TYPE_TYPE = 0x0201;
    static final int LIBRARY_TYPE = 0x0203;

    static final int TYPE_FLAG_SPARSE = 0x01;
    static final int TYPE_FLAG_OFFSET16 = 0x02;
    static final int ENTRY_FLAG_COMPLEX = 0x0001;
    static final int ENTRY_FLAG_COMPACT = 0x0008;

    private static final ErrorCode ERR = ErrorCode.BAD_RESOURCES;

    public static final class Pkg {
        public final int offset;
        public final int id;
        public final String name;
        final List<Integer> typeChunks = new ArrayList<>();
        final List<Integer> libraryChunks = new ArrayList<>();

        Pkg(int offset, int id, String name) {
            this.offset = offset;
            this.id = id;
            this.name = name;
        }
    }

    /** Satu nilai entri resource pada satu konfigurasi. */
    public static final class EntryRef {
        public final int entryOffset;
        public final boolean compact;
        public final boolean defaultConfig;
        public final int valueType;
        public final int valueData;
        final int valueTypeOffset;
        final int valueDataOffset;

        EntryRef(int entryOffset, boolean compact, boolean defaultConfig, int valueType, int valueData,
                 int valueTypeOffset, int valueDataOffset) {
            this.entryOffset = entryOffset;
            this.compact = compact;
            this.defaultConfig = defaultConfig;
            this.valueType = valueType;
            this.valueData = valueData;
            this.valueTypeOffset = valueTypeOffset;
            this.valueDataOffset = valueDataOffset;
        }
    }

    private byte[] b;
    private int poolOffset;
    private StringPool.Header pool;
    private final List<Pkg> packages = new ArrayList<>();

    public Arsc(byte[] data) throws CloneException {
        this.b = data;
        parse();
    }

    private void parse() throws CloneException {
        packages.clear();
        poolOffset = -1;
        pool = null;
        try {
            if (b.length < 12 || Le.u16(b, 0) != TABLE_TYPE) {
                throw new CloneException(ERR, "bukan resource table");
            }
            int hs = Le.u16(b, 2);
            int size = Le.i32(b, 4);
            if (size > b.length || size < hs) {
                throw new CloneException(ERR, "ukuran resource table tidak valid");
            }
            int pos = hs;
            while (pos + 8 <= size) {
                int type = Le.u16(b, pos);
                int csize = Le.i32(b, pos + 4);
                if (csize < 8 || pos + (long) csize > size) {
                    throw new CloneException(ERR, "chunk tabel rusak pada offset " + pos);
                }
                if (type == StringPool.TYPE && poolOffset < 0) {
                    poolOffset = pos;
                    pool = StringPool.header(b, pos, ERR);
                } else if (type == PACKAGE_TYPE) {
                    parsePackage(pos, csize);
                }
                pos += csize;
            }
            if (pool == null) {
                throw new CloneException(ERR, "global string pool tidak ada");
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            throw new CloneException(ERR, "resource table terpotong", ex);
        }
    }

    private void parsePackage(int off, int csize) throws CloneException {
        int hs = Le.u16(b, off + 2);
        int id = Le.i32(b, off + 8);
        Pkg p = new Pkg(off, id, readChar16(off + 12, 128));
        int pos = off + hs;
        int end = off + csize;
        while (pos + 8 <= end) {
            int type = Le.u16(b, pos);
            int c = Le.i32(b, pos + 4);
            if (c < 8 || pos + (long) c > end) {
                throw new CloneException(ERR, "chunk paket rusak pada offset " + pos);
            }
            if (type == TYPE_TYPE) {
                p.typeChunks.add(pos);
            } else if (type == LIBRARY_TYPE) {
                p.libraryChunks.add(pos);
            }
            pos += c;
        }
        packages.add(p);
    }

    private String readChar16(int off, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxChars; i++) {
            int c = Le.u16(b, off + 2 * i);
            if (c == 0) {
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    private void writeChar16(int off, int maxChars, String s) throws CloneException {
        if (s.length() >= maxChars) {
            throw new CloneException(ErrorCode.PACKAGE_TOO_LONG, s);
        }
        for (int i = 0; i < maxChars; i++) {
            Le.put16(b, off + 2 * i, i < s.length() ? s.charAt(i) : 0);
        }
    }

    public List<Pkg> packages() {
        return packages;
    }

    public String globalString(int idx) throws CloneException {
        return StringPool.stringAt(b, pool, idx, poolOffset + pool.size, ERR);
    }

    public int globalStringCount() {
        return pool.stringCount;
    }

    /** Mengganti nama paket pada chunk package dan chunk library. Mengembalikan jumlah perubahan. */
    public int renamePackage(String oldName, String newName) throws CloneException {
        int changed = 0;
        for (Pkg p : packages) {
            if (oldName.equals(p.name)) {
                writeChar16(p.offset + 12, 128, newName);
                changed++;
            }
            for (int lib : p.libraryChunks) {
                int hs = Le.u16(b, lib + 2);
                int count = Le.i32(b, lib + 8);
                for (int i = 0; i < count; i++) {
                    int e = lib + hs + i * (4 + 256);
                    if (e + 260 > b.length) {
                        throw new CloneException(ERR, "chunk library terpotong");
                    }
                    if (oldName.equals(readChar16(e + 4, 128))) {
                        writeChar16(e + 4, 128, newName);
                        changed++;
                    }
                }
            }
        }
        if (changed > 0) {
            parse();
        }
        return changed;
    }

    /** Semua nilai entri (per konfigurasi) untuk satu ID resource; kosong bila tidak ada di tabel ini. */
    public List<EntryRef> entries(int resId) throws CloneException {
        List<EntryRef> out = new ArrayList<>();
        int pkgId = (resId >>> 24) & 0xFF;
        int typeId = (resId >>> 16) & 0xFF;
        int entryIdx = resId & 0xFFFF;
        try {
            for (Pkg p : packages) {
                if (p.id != pkgId) {
                    continue;
                }
                for (int t : p.typeChunks) {
                    if (Le.u8(b, t + 8) != typeId) {
                        continue;
                    }
                    int hs = Le.u16(b, t + 2);
                    int flags = Le.u8(b, t + 9);
                    int count = Le.i32(b, t + 12);
                    int entriesStart = Le.i32(b, t + 16);
                    int offset = -1;
                    if ((flags & TYPE_FLAG_SPARSE) != 0) {
                        for (int i = 0; i < count; i++) {
                            int idx = Le.u16(b, t + hs + 4 * i);
                            if (idx == entryIdx) {
                                offset = Le.u16(b, t + hs + 4 * i + 2) * 4;
                                break;
                            }
                        }
                    } else if (entryIdx < count) {
                        if ((flags & TYPE_FLAG_OFFSET16) != 0) {
                            int v = Le.u16(b, t + hs + 2 * entryIdx);
                            offset = v == 0xFFFF ? -1 : v * 4;
                        } else {
                            int v = Le.i32(b, t + hs + 4 * entryIdx);
                            offset = v == -1 ? -1 : v;
                        }
                    }
                    if (offset < 0) {
                        continue;
                    }
                    int e = t + entriesStart + offset;
                    int eflags = Le.u16(b, e + 2);
                    boolean def = isDefaultConfig(t + 20, hs - 20);
                    if ((eflags & ENTRY_FLAG_COMPACT) != 0) {
                        out.add(new EntryRef(e, true, def, (eflags >>> 8) & 0xFF, Le.i32(b, e + 4), -1, e + 4));
                    } else if ((eflags & ENTRY_FLAG_COMPLEX) == 0) {
                        int esize = Le.u16(b, e);
                        int v = e + esize;
                        out.add(new EntryRef(e, false, def, Le.u8(b, v + 3), Le.i32(b, v + 4), v + 3, v + 4));
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            throw new CloneException(ERR, String.format("entri 0x%08x rusak", resId), ex);
        }
        return out;
    }

    private boolean isDefaultConfig(int cfg, int available) {
        int size = Le.i32(b, cfg);
        int n = Math.min(size, available);
        for (int i = 4; i < n; i++) {
            if (b[cfg + i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Nilai string untuk ID resource (konfigurasi default diutamakan), mengikuti referensi. */
    public String resolveString(int resId) throws CloneException {
        for (int depth = 0; depth < 8; depth++) {
            List<EntryRef> list = entries(resId);
            if (list.isEmpty()) {
                return null;
            }
            EntryRef pick = list.get(0);
            for (EntryRef r : list) {
                if (r.defaultConfig) {
                    pick = r;
                    break;
                }
            }
            if (pick.valueType == Axml.TYPE_STRING) {
                return globalString(pick.valueData);
            }
            if (pick.valueType == Axml.TYPE_REFERENCE && pick.valueData != 0) {
                resId = pick.valueData;
                continue;
            }
            return null;
        }
        return null;
    }

    /** Semua path file (nilai string) untuk ID resource, di semua konfigurasi. */
    public List<String> filePaths(int resId) throws CloneException {
        List<String> out = new ArrayList<>();
        for (EntryRef r : entries(resId)) {
            if (r.valueType == Axml.TYPE_STRING) {
                String s = globalString(r.valueData);
                if (s.startsWith("res/") && !out.contains(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * Menambah string di akhir global pool tanpa menggeser indeks string lama
     * (style tetap merujuk indeks yang sama). Mengembalikan indeks baru.
     */
    public int appendGlobalString(String s) throws CloneException {
        StringPool.Header h = pool;
        int oldStart = h.offset;
        int stringsDataEnd = h.styleCount > 0 ? h.stylesStart : h.size;
        int stringsDataLen = h.stringCount == 0 ? 0 : stringsDataEnd - h.stringsStart;
        if (stringsDataLen < 0) {
            throw new CloneException(ERR, "tata letak string pool tidak didukung");
        }
        byte[] enc = StringPool.encodeString(s, h.utf8());
        int newCount = h.stringCount + 1;
        int newStringsStart = h.headerSize + 4 * newCount + 4 * h.styleCount;
        ByteSink data = new ByteSink(stringsDataLen + enc.length + 4);
        if (stringsDataLen > 0) {
            data.bytes(b, oldStart + h.stringsStart, stringsDataLen);
        }
        data.padTo4();
        int newOffset = data.size();
        data.bytes(enc);
        data.padTo4();
        int stylesLen = h.styleCount > 0 ? h.size - h.stylesStart : 0;
        int newSize = newStringsStart + data.size() + stylesLen;
        ByteSink chunk = new ByteSink(newSize);
        chunk.bytes(b, oldStart, h.headerSize);
        chunk.set32(4, newSize);
        chunk.set32(8, newCount);
        chunk.set32(16, h.flags & ~StringPool.FLAG_SORTED);
        chunk.set32(20, newStringsStart);
        chunk.set32(24, h.styleCount > 0 ? newStringsStart + data.size() : 0);
        chunk.bytes(b, oldStart + h.headerSize, 4 * h.stringCount);
        chunk.i32(newOffset);
        chunk.bytes(b, oldStart + h.headerSize + 4 * h.stringCount, 4 * h.styleCount);
        chunk.bytes(data.toByteArray());
        if (stylesLen > 0) {
            chunk.bytes(b, oldStart + h.stylesStart, stylesLen);
        }
        byte[] newChunk = chunk.toByteArray();
        int tableSize = Le.i32(b, 4);
        byte[] nb = new byte[tableSize - h.size + newChunk.length];
        System.arraycopy(b, 0, nb, 0, oldStart);
        System.arraycopy(newChunk, 0, nb, oldStart, newChunk.length);
        System.arraycopy(b, oldStart + h.size, nb, oldStart + newChunk.length, tableSize - oldStart - h.size);
        Le.put32(nb, 4, nb.length);
        b = nb;
        parse();
        return newCount - 1;
    }

    /** Mengarahkan satu entri ke string global (TYPE_STRING). */
    public void setString(EntryRef ref, int stringIndex) {
        if (ref.compact) {
            int flags = Le.u16(b, ref.entryOffset + 2);
            Le.put16(b, ref.entryOffset + 2, (flags & 0xFF) | (Axml.TYPE_STRING << 8));
        } else {
            b[ref.valueTypeOffset] = (byte) Axml.TYPE_STRING;
        }
        Le.put32(b, ref.valueDataOffset, stringIndex);
    }

    public byte[] bytes() {
        return b;
    }
}
