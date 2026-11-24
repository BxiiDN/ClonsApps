package com.clonsapps.engine.res;

import com.clonsapps.engine.CloneException;
import com.clonsapps.engine.ErrorCode;
import com.clonsapps.engine.util.ByteSink;
import com.clonsapps.engine.util.Le;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dokumen XML biner Android (AndroidManifest.xml terkompilasi dan res/xml/*.xml).
 * Diurai menjadi node yang memegang nilai string (bukan indeks), lalu dikodekan ulang dengan
 * string pool baru: nama atribut ber-ID resource ditempatkan di depan sesuai resource map.
 * Atribut dicocokkan berdasarkan ID resource, sama seperti cara Android membacanya.
 */
public final class Axml {
    public static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    static final int RES_XML_TYPE = 0x0003;
    static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;
    static final int START_NAMESPACE = 0x0100;
    static final int END_NAMESPACE = 0x0101;
    static final int START_ELEMENT = 0x0102;
    static final int END_ELEMENT = 0x0103;
    static final int CDATA = 0x0104;

    public static final int TYPE_NULL = 0x00;
    public static final int TYPE_REFERENCE = 0x01;
    public static final int TYPE_STRING = 0x03;
    public static final int TYPE_INT_DEC = 0x10;
    public static final int TYPE_INT_HEX = 0x11;
    public static final int TYPE_BOOLEAN = 0x12;

    public abstract static class Node {
        int line;
        String comment;
    }

    public static final class Namespace extends Node {
        final boolean start;
        public String prefix;
        public String uri;

        Namespace(boolean start) {
            this.start = start;
        }
    }

    public static final class Element extends Node {
        public String ns;
        public String name;
        public final List<Attr> attrs = new ArrayList<>();
        public Element parent;
        public final List<Element> children = new ArrayList<>();
        Attr idAttr;
        Attr classAttr;
        Attr styleAttr;

        /** Atribut android dengan ID resource tertentu (fallback ke nama bila atribut tidak ber-ID). */
        public Attr android(int resId, String localName) {
            for (Attr a : attrs) {
                if (a.resId != 0 && a.resId == resId) {
                    return a;
                }
            }
            for (Attr a : attrs) {
                if (localName.equals(a.name) && ANDROID_NS.equals(a.ns)) {
                    return a;
                }
            }
            return null;
        }

        /** Atribut tanpa namespace (misalnya {@code package} pada &lt;manifest&gt;). */
        public Attr plain(String localName) {
            for (Attr a : attrs) {
                if (a.ns == null && a.resId == 0 && localName.equals(a.name)) {
                    return a;
                }
            }
            return null;
        }

        public String path() {
            StringBuilder sb = new StringBuilder(name);
            for (Element p = parent; p != null; p = p.parent) {
                sb.insert(0, p.name + "/");
            }
            return sb.toString();
        }

        /** Menambah atribut android baru, urut berdasarkan ID resource seperti keluaran aapt2. */
        public Attr addAndroid(String localName, int resId) {
            Attr a = new Attr();
            a.ns = ANDROID_NS;
            a.name = localName;
            a.resId = resId;
            int at = attrs.size();
            for (int i = 0; i < attrs.size(); i++) {
                Attr o = attrs.get(i);
                if (o.resId == 0 || (o.resId & 0xFFFFFFFFL) > (resId & 0xFFFFFFFFL)) {
                    at = i;
                    break;
                }
            }
            attrs.add(at, a);
            return a;
        }
    }

    public static final class End extends Node {
        String ns;
        String name;
    }

    public static final class CData extends Node {
        String text;
        int valueType;
        int valueData;
        String valueString;
    }

    public static final class Raw extends Node {
        byte[] bytes;
    }

    public static final class Attr {
        public String ns;
        public String name;
        public int resId;
        public String raw;
        public int type;
        public int data;
        public String str;

        public String stringValue() {
            if (type == TYPE_STRING) {
                return str;
            }
            return raw;
        }

        public boolean isReference() {
            return type == TYPE_REFERENCE;
        }

        public boolean isInt() {
            return type >= TYPE_INT_DEC && type <= 0x1f;
        }

        public void setString(String v) {
            type = TYPE_STRING;
            str = v;
            raw = v;
            data = 0;
        }
    }

    private final boolean utf8;
    private final List<Node> nodes;

    private Axml(boolean utf8, List<Node> nodes) {
        this.utf8 = utf8;
        this.nodes = nodes;
    }

    public List<Element> elements() {
        List<Element> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n instanceof Element) {
                out.add((Element) n);
            }
        }
        return out;
    }

    public Element root() {
        for (Node n : nodes) {
            if (n instanceof Element) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String str(List<String> pool, int idx, ErrorCode err) throws CloneException {
        if (idx == -1) {
            return null;
        }
        if (idx < 0 || idx >= pool.size()) {
            throw new CloneException(err, "referensi string " + idx + " di luar pool (" + pool.size() + ")");
        }
        return pool.get(idx);
    }

    public static Axml parse(byte[] b, ErrorCode err) throws CloneException {
        try {
            if (b.length < 8 || Le.u16(b, 0) != RES_XML_TYPE) {
                throw new CloneException(err, "bukan XML biner Android");
            }
            int headerSize = Le.u16(b, 2);
            int total = Math.min(Le.i32(b, 4), b.length);
            if (headerSize < 8 || headerSize > total) {
                throw new CloneException(err, "header XML tidak valid");
            }
            List<String> pool = null;
            boolean utf8 = false;
            int[] resMap = new int[0];
            List<Node> nodes = new ArrayList<>();
            List<Element> stack = new ArrayList<>();
            int pos = headerSize;
            while (pos + 8 <= total) {
                int type = Le.u16(b, pos);
                int hsize = Le.u16(b, pos + 2);
                int csize = Le.i32(b, pos + 4);
                if (csize < 8 || pos + (long) csize > total || hsize > csize) {
                    throw new CloneException(err, "chunk XML rusak pada offset " + pos);
                }
                if (type == StringPool.TYPE) {
                    if (pool == null) {
                        StringPool sp = StringPool.parse(b, pos, err);
                        if (sp.styleCount != 0) {
                            throw new CloneException(ErrorCode.UNSUPPORTED_XML, "string pool XML memakai style");
                        }
                        pool = sp.strings;
                        utf8 = sp.utf8;
                    }
                } else if (type == RES_XML_RESOURCE_MAP_TYPE) {
                    int n = (csize - hsize) / 4;
                    resMap = new int[n];
                    for (int i = 0; i < n; i++) {
                        resMap[i] = Le.i32(b, pos + hsize + 4 * i);
                    }
                } else if (type >= START_NAMESPACE && type <= CDATA) {
                    if (pool == null) {
                        throw new CloneException(err, "node XML sebelum string pool");
                    }
                    int line = Le.i32(b, pos + 8);
                    String comment = str(pool, Le.i32(b, pos + 12), err);
                    int ext = pos + hsize;
                    if (type == START_NAMESPACE || type == END_NAMESPACE) {
                        Namespace ns = new Namespace(type == START_NAMESPACE);
                        ns.prefix = str(pool, Le.i32(b, ext), err);
                        ns.uri = str(pool, Le.i32(b, ext + 4), err);
                        ns.line = line;
                        ns.comment = comment;
                        nodes.add(ns);
                    } else if (type == START_ELEMENT) {
                        Element e = new Element();
                        e.line = line;
                        e.comment = comment;
                        e.ns = str(pool, Le.i32(b, ext), err);
                        e.name = str(pool, Le.i32(b, ext + 4), err);
                        int attrStart = Le.u16(b, ext + 8);
                        int attrSize = Le.u16(b, ext + 10);
                        int attrCount = Le.u16(b, ext + 12);
                        int idIndex = Le.u16(b, ext + 14);
                        int classIndex = Le.u16(b, ext + 16);
                        int styleIndex = Le.u16(b, ext + 18);
                        if (attrSize < 20 || ext + attrStart + (long) attrSize * attrCount > pos + (long) csize) {
                            throw new CloneException(err, "atribut elemen <" + e.name + "> rusak");
                        }
                        for (int i = 0; i < attrCount; i++) {
                            int ap = ext + attrStart + attrSize * i;
                            Attr a = new Attr();
                            a.ns = str(pool, Le.i32(b, ap), err);
                            int nameIdx = Le.i32(b, ap + 4);
                            a.name = str(pool, nameIdx, err);
                            a.resId = nameIdx >= 0 && nameIdx < resMap.length ? resMap[nameIdx] : 0;
                            a.raw = str(pool, Le.i32(b, ap + 8), err);
                            a.type = Le.u8(b, ap + 15);
                            a.data = Le.i32(b, ap + 16);
                            if (a.type == TYPE_STRING) {
                                a.str = str(pool, a.data, err);
                            }
                            e.attrs.add(a);
                        }
                        e.idAttr = idIndex > 0 && idIndex <= e.attrs.size() ? e.attrs.get(idIndex - 1) : null;
                        e.classAttr = classIndex > 0 && classIndex <= e.attrs.size() ? e.attrs.get(classIndex - 1) : null;
                        e.styleAttr = styleIndex > 0 && styleIndex <= e.attrs.size() ? e.attrs.get(styleIndex - 1) : null;
                        if (!stack.isEmpty()) {
                            e.parent = stack.get(stack.size() - 1);
                            e.parent.children.add(e);
                        }
                        stack.add(e);
                        nodes.add(e);
                    } else if (type == END_ELEMENT) {
                        End en = new End();
                        en.line = line;
                        en.comment = comment;
                        en.ns = str(pool, Le.i32(b, ext), err);
                        en.name = str(pool, Le.i32(b, ext + 4), err);
                        if (!stack.isEmpty()) {
                            stack.remove(stack.size() - 1);
                        }
                        nodes.add(en);
                    } else {
                        CData cd = new CData();
                        cd.line = line;
                        cd.comment = comment;
                        cd.text = str(pool, Le.i32(b, ext), err);
                        cd.valueType = Le.u8(b, ext + 7);
                        cd.valueData = Le.i32(b, ext + 8);
                        if (cd.valueType == TYPE_STRING) {
                            cd.valueString = str(pool, cd.valueData, err);
                        }
                        nodes.add(cd);
                    }
                } else {
                    Raw r = new Raw();
                    r.bytes = new byte[csize];
                    System.arraycopy(b, pos, r.bytes, 0, csize);
                    nodes.add(r);
                }
                pos += csize;
            }
            if (pool == null) {
                throw new CloneException(err, "string pool XML tidak ada");
            }
            boolean hasElement = false;
            for (Node n : nodes) {
                if (n instanceof Element) {
                    hasElement = true;
                    break;
                }
            }
            if (!hasElement) {
                throw new CloneException(err, "XML tidak memiliki elemen");
            }
            return new Axml(utf8, nodes);
        } catch (ArrayIndexOutOfBoundsException ex) {
            throw new CloneException(err, "XML biner terpotong", ex);
        }
    }

    // ---------------------------------------------------------------- serialisasi

    private static final class PoolBuilder {
        final List<String> strings = new ArrayList<>();
        final List<Integer> resIds = new ArrayList<>();
        final Map<String, Integer> attrIndex = new HashMap<>();
        final Map<String, Integer> plainIndex = new HashMap<>();

        void addAttrName(Attr a) {
            String key = a.name + '\u0000' + a.resId;
            if (!attrIndex.containsKey(key)) {
                attrIndex.put(key, strings.size());
                strings.add(a.name == null ? "" : a.name);
                resIds.add(a.resId);
            }
        }

        int attrName(Attr a) {
            if (a.resId != 0) {
                return attrIndex.get(a.name + '\u0000' + a.resId);
            }
            return idx(a.name);
        }

        int idx(String s) {
            if (s == null) {
                return -1;
            }
            Integer i = plainIndex.get(s);
            if (i == null) {
                i = strings.size();
                plainIndex.put(s, i);
                strings.add(s);
            }
            return i;
        }
    }

    public byte[] serialize() throws CloneException {
        PoolBuilder pb = new PoolBuilder();
        List<Attr> withIds = new ArrayList<>();
        for (Node n : nodes) {
            if (n instanceof Element) {
                for (Attr a : ((Element) n).attrs) {
                    if (a.resId != 0) {
                        withIds.add(a);
                    }
                }
            }
        }
        Collections.sort(withIds, new Comparator<Attr>() {
            @Override
            public int compare(Attr x, Attr y) {
                return Long.compare(x.resId & 0xFFFFFFFFL, y.resId & 0xFFFFFFFFL);
            }
        });
        for (Attr a : withIds) {
            pb.addAttrName(a);
        }
        ByteSink body = new ByteSink(4096);
        for (Node n : nodes) {
            if (n instanceof Namespace) {
                Namespace ns = (Namespace) n;
                body.u16(ns.start ? START_NAMESPACE : END_NAMESPACE);
                body.u16(16);
                body.i32(24);
                body.i32(ns.line);
                body.i32(pb.idx(ns.comment));
                body.i32(pb.idx(ns.prefix));
                body.i32(pb.idx(ns.uri));
            } else if (n instanceof Element) {
                Element e = (Element) n;
                body.u16(START_ELEMENT);
                body.u16(16);
                body.i32(16 + 20 + 20 * e.attrs.size());
                body.i32(e.line);
                body.i32(pb.idx(e.comment));
                body.i32(pb.idx(e.ns));
                body.i32(pb.idx(e.name));
                body.u16(20);
                body.u16(20);
                body.u16(e.attrs.size());
                body.u16(indexOf(e, e.idAttr));
                body.u16(indexOf(e, e.classAttr));
                body.u16(indexOf(e, e.styleAttr));
                for (Attr a : e.attrs) {
                    body.i32(pb.idx(a.ns));
                    body.i32(pb.attrName(a));
                    body.i32(pb.idx(a.raw));
                    body.u16(8);
                    body.u8(0);
                    body.u8(a.type);
                    body.i32(a.type == TYPE_STRING ? pb.idx(a.str == null ? "" : a.str) : a.data);
                }
            } else if (n instanceof End) {
                End en = (End) n;
                body.u16(END_ELEMENT);
                body.u16(16);
                body.i32(24);
                body.i32(en.line);
                body.i32(pb.idx(en.comment));
                body.i32(pb.idx(en.ns));
                body.i32(pb.idx(en.name));
            } else if (n instanceof CData) {
                CData cd = (CData) n;
                body.u16(CDATA);
                body.u16(16);
                body.i32(28);
                body.i32(cd.line);
                body.i32(pb.idx(cd.comment));
                body.i32(pb.idx(cd.text));
                body.u16(8);
                body.u8(0);
                body.u8(cd.valueType);
                body.i32(cd.valueType == TYPE_STRING ? pb.idx(cd.valueString) : cd.valueData);
            } else if (n instanceof Raw) {
                body.bytes(((Raw) n).bytes);
            }
        }
        byte[] pool = StringPool.encode(pb.strings, utf8);
        int mapSize = 8 + 4 * pb.resIds.size();
        byte[] bodyBytes = body.toByteArray();
        int total = 8 + pool.length + mapSize + bodyBytes.length;
        ByteSink out = new ByteSink(total);
        out.u16(RES_XML_TYPE);
        out.u16(8);
        out.i32(total);
        out.bytes(pool);
        out.u16(RES_XML_RESOURCE_MAP_TYPE);
        out.u16(8);
        out.i32(mapSize);
        for (int id : pb.resIds) {
            out.i32(id);
        }
        out.bytes(bodyBytes);
        return out.toByteArray();
    }

    private static int indexOf(Element e, Attr a) {
        if (a == null) {
            return 0;
        }
        int i = e.attrs.indexOf(a);
        return i < 0 ? 0 : i + 1;
    }

    /** Ringkasan teks (untuk log/debug dan pengujian). */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        Map<Element, Integer> depth = new LinkedHashMap<>();
        for (Element e : elements()) {
            int d = e.parent == null ? 0 : depth.get(e.parent) + 1;
            depth.put(e, d);
            for (int i = 0; i < d; i++) {
                sb.append("  ");
            }
            sb.append('<').append(e.name);
            for (Attr a : e.attrs) {
                sb.append(' ').append(a.name).append('=');
                if (a.type == TYPE_STRING) {
                    sb.append('"').append(a.str).append('"');
                } else if (a.type == TYPE_REFERENCE) {
                    sb.append(String.format("@0x%08x", a.data));
                } else {
                    sb.append(a.data);
                }
            }
            sb.append(">\n");
        }
        return sb.toString();
    }
}
