package com.clonsapps.engine.res;

import com.clonsapps.engine.CloneException;
import com.clonsapps.engine.ErrorCode;
import com.clonsapps.engine.util.ByteSink;
import com.clonsapps.engine.util.Le;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** ResStringPool: dekode dan enkode (UTF-8 maupun UTF-16) sesuai format resource Android. */
public final class StringPool {
    public static final int TYPE = 0x0001;
    public static final int FLAG_SORTED = 1;
    public static final int FLAG_UTF8 = 1 << 8;
    public static final int HEADER_SIZE = 28;

    public final List<String> strings;
    public final boolean utf8;
    public final int styleCount;

    private StringPool(List<String> strings, boolean utf8, int styleCount) {
        this.strings = strings;
        this.utf8 = utf8;
        this.styleCount = styleCount;
    }

    /** Header pool (untuk modifikasi di tempat). */
    public static final class Header {
        public final int offset;
        public final int headerSize;
        public final int size;
        public final int stringCount;
        public final int styleCount;
        public final int flags;
        public final int stringsStart;
        public final int stylesStart;

        Header(byte[] b, int off, ErrorCode err) throws CloneException {
            if (!Le.inRange(b, off, HEADER_SIZE) || Le.u16(b, off) != TYPE) {
                throw new CloneException(err, "string pool tidak ditemukan pada offset " + off);
            }
            offset = off;
            headerSize = Le.u16(b, off + 2);
            size = Le.i32(b, off + 4);
            stringCount = Le.i32(b, off + 8);
            styleCount = Le.i32(b, off + 12);
            flags = Le.i32(b, off + 16);
            stringsStart = Le.i32(b, off + 20);
            stylesStart = Le.i32(b, off + 24);
            if (size < headerSize || !Le.inRange(b, off, size) || stringCount < 0 || styleCount < 0
                    || (long) headerSize + 4L * stringCount + 4L * styleCount > size
                    || (stringCount > 0 && (stringsStart <= 0 || stringsStart > size))) {
                throw new CloneException(err, "header string pool tidak valid");
            }
        }

        public boolean utf8() {
            return (flags & FLAG_UTF8) != 0;
        }
    }

    public static Header header(byte[] b, int off, ErrorCode err) throws CloneException {
        return new Header(b, off, err);
    }

    public static StringPool parse(byte[] b, int off, ErrorCode err) throws CloneException {
        Header h = new Header(b, off, err);
        List<String> list = new ArrayList<>(h.stringCount);
        int limit = off + h.size;
        for (int i = 0; i < h.stringCount; i++) {
            list.add(stringAt(b, h, i, limit, err));
        }
        return new StringPool(list, h.utf8(), h.styleCount);
    }

    public static String stringAt(byte[] b, Header h, int index, int limit, ErrorCode err) throws CloneException {
        if (index < 0 || index >= h.stringCount) {
            throw new CloneException(err, "indeks string " + index + " di luar batas");
        }
        int so = Le.i32(b, h.offset + h.headerSize + 4 * index);
        int p = h.offset + h.stringsStart + so;
        if (so < 0 || p >= limit) {
            throw new CloneException(err, "offset string " + index + " di luar batas");
        }
        try {
            if (h.utf8()) {
                int[] pp = {p};
                readLen8(b, pp); // panjang dalam karakter UTF-16
                int byteLen = readLen8(b, pp);
                if (pp[0] + byteLen > limit) {
                    throw new CloneException(err, "string " + index + " melewati batas pool");
                }
                return new String(b, pp[0], byteLen, StandardCharsets.UTF_8);
            } else {
                int len = Le.u16(b, p);
                p += 2;
                if ((len & 0x8000) != 0) {
                    len = ((len & 0x7FFF) << 16) | Le.u16(b, p);
                    p += 2;
                }
                if (p + 2L * len > limit) {
                    throw new CloneException(err, "string " + index + " melewati batas pool");
                }
                char[] c = new char[len];
                for (int i = 0; i < len; i++) {
                    c[i] = (char) Le.u16(b, p + 2 * i);
                }
                return new String(c);
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            throw new CloneException(err, "string " + index + " rusak");
        }
    }

    private static int readLen8(byte[] b, int[] p) {
        int v = b[p[0]++] & 0xFF;
        if ((v & 0x80) != 0) {
            v = ((v & 0x7F) << 8) | (b[p[0]++] & 0xFF);
        }
        return v;
    }

    /** Mengenkode satu string (tanpa offset) ke format pool. */
    public static byte[] encodeString(String s, boolean utf8) throws CloneException {
        ByteSink o = new ByteSink(s.length() * 2 + 8);
        if (utf8) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            if (s.length() > 0x7FFF || bytes.length > 0x7FFF) {
                throw new CloneException(ErrorCode.UNSUPPORTED_XML, "string terlalu panjang untuk pool UTF-8");
            }
            writeLen8(o, s.length());
            writeLen8(o, bytes.length);
            o.bytes(bytes);
            o.u8(0);
        } else {
            int len = s.length();
            if (len > 0x7FFF) {
                o.u16(((len >>> 16) & 0x7FFF) | 0x8000);
                o.u16(len & 0xFFFF);
            } else {
                o.u16(len);
            }
            for (int i = 0; i < len; i++) {
                o.u16(s.charAt(i));
            }
            o.u16(0);
        }
        return o.toByteArray();
    }

    private static void writeLen8(ByteSink o, int v) {
        if (v > 0x7F) {
            o.u8(((v >>> 8) & 0x7F) | 0x80);
            o.u8(v & 0xFF);
        } else {
            o.u8(v);
        }
    }

    /** Pool baru tanpa style. */
    public static byte[] encode(List<String> strings, boolean utf8) throws CloneException {
        ByteSink data = new ByteSink(strings.size() * 16);
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = data.size();
            data.bytes(encodeString(strings.get(i), utf8));
        }
        data.padTo4();
        int stringsStart = HEADER_SIZE + 4 * strings.size();
        ByteSink o = new ByteSink(stringsStart + data.size());
        o.u16(TYPE);
        o.u16(HEADER_SIZE);
        o.i32(stringsStart + data.size());
        o.i32(strings.size());
        o.i32(0);
        o.i32(utf8 ? FLAG_UTF8 : 0);
        o.i32(strings.isEmpty() ? 0 : stringsStart);
        o.i32(0);
        for (int off : offsets) {
            o.i32(off);
        }
        o.bytes(data.toByteArray());
        return o.toByteArray();
    }
}
