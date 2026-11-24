package com.clonsapps.engine;

import com.clonsapps.engine.res.AndroidAttrs;
import com.clonsapps.engine.res.Axml;
import com.clonsapps.engine.res.StringPool;
import com.clonsapps.engine.util.ByteSink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pembuat APK sintetis untuk pengujian JVM: XML biner, resources.arsc, dan DEX minimal ditulis
 * byte demi byte dengan tata letak seperti keluaran aapt2 (jalur kode berbeda dari mesin).
 */
final class Fixtures {
    private Fixtures() {
    }

    static final String NS = Axml.ANDROID_NS;

    // ------------------------------------------------------------------ XML biner

    static final class A {
        final String ns;
        final String name;
        final int resId;
        final int type;
        final Object value;

        A(String ns, String name, int resId, int type, Object value) {
            this.ns = ns;
            this.name = name;
            this.resId = resId;
            this.type = type;
            this.value = value;
        }
    }

    static A str(String name, int resId, String v) {
        return new A(NS, name, resId, Axml.TYPE_STRING, v);
    }

    static A plain(String name, String v) {
        return new A(null, name, 0, Axml.TYPE_STRING, v);
    }

    static A intv(String name, int resId, int v) {
        return new A(NS, name, resId, Axml.TYPE_INT_DEC, v);
    }

    static A bool(String name, int resId, boolean v) {
        return new A(NS, name, resId, Axml.TYPE_BOOLEAN, v ? -1 : 0);
    }

    static A ref(String name, int resId, int v) {
        return new A(NS, name, resId, Axml.TYPE_REFERENCE, v);
    }

    static final class XmlBuilder {
        private final List<Object[]> events = new ArrayList<>();

        XmlBuilder start(String name, A... attrs) {
            events.add(new Object[]{"S", name, attrs});
            return this;
        }

        XmlBuilder end(String name) {
            events.add(new Object[]{"E", name});
            return this;
        }

        XmlBuilder leaf(String name, A... attrs) {
            return start(name, attrs).end(name);
        }

        byte[] build(boolean utf8) throws Exception {
            // pool: nama atribut ber-ID dulu (urut ID), lalu string lain
            Map<String, Integer> idx = new LinkedHashMap<>();
            List<String> pool = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            List<A> withId = new ArrayList<>();
            for (Object[] ev : events) {
                if ("S".equals(ev[0])) {
                    for (A a : (A[]) ev[2]) {
                        if (a.resId != 0) {
                            withId.add(a);
                        }
                    }
                }
            }
            withId.sort((x, y) -> Integer.compareUnsigned(x.resId, y.resId));
            for (A a : withId) {
                String k = "#" + a.resId;
                if (!idx.containsKey(k)) {
                    idx.put(k, pool.size());
                    pool.add(a.name);
                    ids.add(a.resId);
                }
            }
            ByteSink body = new ByteSink(1024);
            // start namespace
            body.u16(0x0100);
            body.u16(16);
            body.i32(24);
            body.i32(1);
            body.i32(-1);
            body.i32(s(idx, pool, "android"));
            body.i32(s(idx, pool, NS));
            for (Object[] ev : events) {
                if ("S".equals(ev[0])) {
                    A[] attrs = (A[]) ev[2];
                    body.u16(0x0102);
                    body.u16(16);
                    body.i32(36 + 20 * attrs.length);
                    body.i32(1);
                    body.i32(-1);
                    body.i32(-1);
                    body.i32(s(idx, pool, (String) ev[1]));
                    body.u16(20);
                    body.u16(20);
                    body.u16(attrs.length);
                    body.u16(0);
                    body.u16(0);
                    body.u16(0);
                    for (A a : attrs) {
                        body.i32(a.ns == null ? -1 : s(idx, pool, a.ns));
                        body.i32(a.resId != 0 ? idx.get("#" + a.resId) : s(idx, pool, a.name));
                        int raw = a.type == Axml.TYPE_STRING ? s(idx, pool, (String) a.value) : -1;
                        body.i32(raw);
                        body.u16(8);
                        body.u8(0);
                        body.u8(a.type);
                        body.i32(a.type == Axml.TYPE_STRING ? raw : (Integer) a.value);
                    }
                } else {
                    body.u16(0x0103);
                    body.u16(16);
                    body.i32(24);
                    body.i32(1);
                    body.i32(-1);
                    body.i32(-1);
                    body.i32(s(idx, pool, (String) ev[1]));
                }
            }
            body.u16(0x0101);
            body.u16(16);
            body.i32(24);
            body.i32(1);
            body.i32(-1);
            body.i32(s(idx, pool, "android"));
            body.i32(s(idx, pool, NS));
            byte[] sp = StringPool.encode(pool, utf8);
            byte[] bb = body.toByteArray();
            ByteSink out = new ByteSink(4096);
            int map = 8 + 4 * ids.size();
            out.u16(0x0003);
            out.u16(8);
            out.i32(8 + sp.length + map + bb.length);
            out.bytes(sp);
            out.u16(0x0180);
            out.u16(8);
            out.i32(map);
            for (int id : ids) {
                out.i32(id);
            }
            out.bytes(bb);
            return out.toByteArray();
        }

        private static int s(Map<String, Integer> idx, List<String> pool, String v) {
            Integer i = idx.get("$" + v);
            if (i == null) {
                i = pool.size();
                idx.put("$" + v, i);
                pool.add(v);
            }
            return i;
        }
    }

    static final int ICON_ID = 0x7f010000;
    static final int LABEL_ID = 0x7f020000;
    static final int SHORTCUTS_ID = 0x7f030000;

    /** Manifest "Catatan" dengan nama komponen relatif, provider, permission kustom, dan shortcut. */
    static byte[] notesManifest(String pkg, String split) throws Exception {
        return notesManifest(pkg, split, false);
    }

    static byte[] notesManifest(String pkg, String split, boolean splitRequired) throws Exception {
        return notesManifest(pkg, split, splitRequired, 26);
    }

    static byte[] notesManifest(String pkg, String split, boolean splitRequired, int minSdk) throws Exception {
        XmlBuilder x = new XmlBuilder();
        List<A> root = new ArrayList<>();
        root.add(intv("versionCode", AndroidAttrs.VERSION_CODE, 7));
        root.add(str("versionName", AndroidAttrs.VERSION_NAME, "1.7"));
        root.add(plain("package", pkg));
        if (split != null) {
            root.add(plain("split", split));
        }
        if (splitRequired) {
            root.add(bool("isSplitRequired", AndroidAttrs.IS_SPLIT_REQUIRED, true));
        }
        x.start("manifest", root.toArray(new A[0]));
if (split == null) {
            x.leaf("uses-sdk", intv("minSdkVersion", AndroidAttrs.MIN_SDK_VERSION, minSdk), intv("targetSdkVersion", AndroidAttrs.TARGET_SDK_VERSION, 34));
        }
        if (split == null) {
            x.leaf("permission", str("name", AndroidAttrs.NAME, pkg + ".permission.SYNC"));
            x.leaf("uses-permission", str("name", AndroidAttrs.NAME, pkg + ".permission.SYNC"));
            x.leaf("uses-permission", str("name", AndroidAttrs.NAME, "android.permission.INTERNET"));
            x.start("queries");
            x.leaf("provider", str("authorities", AndroidAttrs.AUTHORITIES, "com.other.provider"));
            x.end("queries");
            x.start("application", ref("label", AndroidAttrs.LABEL, LABEL_ID), ref("icon", AndroidAttrs.ICON, ICON_ID),
                    str("name", AndroidAttrs.NAME, ".NotesApp"));
            x.start("activity", str("name", AndroidAttrs.NAME, ".MainActivity"), bool("exported", 0x01010010, true));
            x.start("intent-filter");
            x.leaf("action", str("name", AndroidAttrs.NAME, "android.intent.action.MAIN"));
            x.leaf("category", str("name", AndroidAttrs.NAME, "android.intent.category.LAUNCHER"));
            x.end("intent-filter");
            x.leaf("meta-data", str("name", AndroidAttrs.NAME, "android.app.shortcuts"), ref("resource", AndroidAttrs.RESOURCE, SHORTCUTS_ID));
            x.end("activity");
            x.leaf("activity", str("name", AndroidAttrs.NAME, "Settings"), str("taskAffinity", AndroidAttrs.TASK_AFFINITY, pkg + ".settings"));
            x.leaf("activity-alias", str("name", AndroidAttrs.NAME, ".Alias"), str("targetActivity", AndroidAttrs.TARGET_ACTIVITY, ".MainActivity"));
            x.leaf("service", str("name", AndroidAttrs.NAME, "com.example.lib.SyncService"), str("permission", AndroidAttrs.PERMISSION, pkg + ".permission.SYNC"));
            x.leaf("provider", str("name", AndroidAttrs.NAME, ".data.NotesProvider"),
                    str("authorities", AndroidAttrs.AUTHORITIES, pkg + ".notes;legacy.notes"),
                    str("readPermission", AndroidAttrs.READ_PERMISSION, "android.permission.READ_CONTACTS"));
            x.end("application");
        }
        x.end("manifest");
        return x.build(false);
    }

    static byte[] shortcutsXml(String pkg) throws Exception {
        XmlBuilder x = new XmlBuilder();
        x.start("shortcuts");
        x.start("shortcut", str("shortcutId", 0x01010528, "compose"));
        x.leaf("intent", str("action", 0x0101002d, "android.intent.action.VIEW"),
                str("targetPackage", AndroidAttrs.TARGET_PACKAGE, pkg),
                str("targetClass", 0x0101002f, pkg + ".MainActivity"));
        x.end("shortcut");
        x.end("shortcuts");
        return x.build(true);
    }

    // ------------------------------------------------------------------ resources.arsc

    static byte[] arsc(String pkg, boolean utf8) throws Exception {
        List<String> global = new ArrayList<>();
        global.add("res/mipmap-anydpi-v26/ic_launcher.xml"); // 0
        global.add("res/mipmap-xxhdpi-v4/ic_launcher.png");  // 1
        global.add("Catatan");                                // 2
        global.add("res/xml/shortcuts.xml");                  // 3
        byte[] gp = StringPool.encode(global, utf8);
        List<String> typeNames = new ArrayList<>();
        typeNames.add("mipmap");
        typeNames.add("string");
        typeNames.add("xml");
        List<String> keys = new ArrayList<>();
        keys.add("ic_launcher");
        keys.add("app_name");
        keys.add("shortcuts");
        byte[] tp = StringPool.encode(typeNames, utf8);
        byte[] kp = StringPool.encode(keys, utf8);

        ByteSink pkgBody = new ByteSink(2048);
        pkgBody.bytes(tp);
        pkgBody.bytes(kp);
        // mipmap: 2 konfigurasi (anydpi-v26, xxhdpi)
        pkgBody.bytes(typeSpec(1, 1));
        pkgBody.bytes(typeChunk(1, 0, 0, 0, false));
        pkgBody.bytes(typeChunk(1, 480, 1, 0, true));
        pkgBody.bytes(typeSpec(2, 1));
        pkgBody.bytes(typeChunk(2, 0, 2, 1, false));
        pkgBody.bytes(typeSpec(3, 1));
        pkgBody.bytes(typeChunk(3, 0, 3, 2, false));
        byte[] body = pkgBody.toByteArray();

        int pkgHeader = 288;
        ByteSink p = new ByteSink(pkgHeader + body.length);
        p.u16(0x0200);
        p.u16(pkgHeader);
        p.i32(pkgHeader + body.length);
        p.i32(0x7f);
        for (int i = 0; i < 128; i++) {
            p.u16(i < pkg.length() ? pkg.charAt(i) : 0);
        }
        p.i32(pkgHeader); // typeStrings
        p.i32(0);
        p.i32(pkgHeader + tp.length); // keyStrings
        p.i32(0);
        p.i32(0);
        p.bytes(body);
        byte[] pb = p.toByteArray();

        ByteSink t = new ByteSink(12 + gp.length + pb.length);
        t.u16(0x0002);
        t.u16(12);
        t.i32(12 + gp.length + pb.length);
        t.i32(1);
        t.bytes(gp);
        t.bytes(pb);
        return t.toByteArray();
    }

    private static byte[] typeSpec(int id, int count) {
        ByteSink s = new ByteSink(16 + 4 * count);
        s.u16(0x0202);
        s.u16(16);
        s.i32(16 + 4 * count);
        s.u8(id);
        s.u8(0);
        s.u16(0);
        s.i32(count);
        for (int i = 0; i < count; i++) {
            s.i32(0);
        }
        return s.toByteArray();
    }

    /** Satu entri bernilai string global {@code stringIdx}; {@code density} &gt; 0 membuat konfigurasi non-default. */
    private static byte[] typeChunk(int id, int density, int stringIdx, int keyIdx, boolean compact) {
        int cfgSize = 64;
        int hs = 20 + cfgSize;
        int entriesStart = hs + 4;
        int entrySize = compact ? 8 : 16;
        ByteSink s = new ByteSink(entriesStart + entrySize);
        s.u16(0x0201);
        s.u16(hs);
        s.i32(entriesStart + entrySize);
        s.u8(id);
        s.u8(0);
        s.u16(0);
        s.i32(1);
        s.i32(entriesStart);
        s.i32(cfgSize);
        byte[] cfg = new byte[cfgSize - 4];
        cfg[10] = (byte) density;
        cfg[11] = (byte) (density >> 8);
        s.bytes(cfg);
        s.i32(0); // offset entri 0
        if (compact) {
            s.u16(keyIdx);
            s.u16(0x0008 | (Axml.TYPE_STRING << 8));
            s.i32(stringIdx);
        } else {
            s.u16(8);
            s.u16(0);
            s.i32(keyIdx);
            s.u16(8);
            s.u8(0);
            s.u8(Axml.TYPE_STRING);
            s.i32(stringIdx);
        }
        return s.toByteArray();
    }

    // ------------------------------------------------------------------ DEX minimal

    static byte[] dex(String... strings) {
        int headerSize = 0x70;
        ByteSink data = new ByteSink(256);
        int[] offs = new int[strings.length];
        int idsOff = headerSize;
        int dataStart = idsOff + 4 * strings.length;
        for (int i = 0; i < strings.length; i++) {
            offs[i] = dataStart + data.size();
            byte[] b = strings[i].getBytes(StandardCharsets.UTF_8);
            data.u8(strings[i].length());
            data.bytes(b);
            data.u8(0);
        }
        ByteSink d = new ByteSink(dataStart + data.size());
        d.bytes("dex\n035\0".getBytes(StandardCharsets.US_ASCII));
        d.zeros(0x38 - 8);
        d.i32(strings.length);
        d.i32(idsOff);
        d.zeros(headerSize - 0x40);
        for (int o : offs) {
            d.i32(o);
        }
        d.bytes(data.toByteArray());
        return d.toByteArray();
    }

    // ------------------------------------------------------------------ APK

    static byte[] png() {
        // PNG 1x1 valid (merah)
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0,
                (byte) 0x90, 0x77, 0x53, (byte) 0xDE, 0, 0, 0, 12, 'I', 'D', 'A', 'T', 8, (byte) 0xD7, 99, (byte) 0xF8, (byte) 0xCF, (byte) 0xC0,
                0, 0, 3, 1, 1, 0, 0x18, (byte) 0xDD, (byte) 0x8D, (byte) 0xB0, 0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 'B', 0x60, (byte) 0x82};
    }

    /** APK base sintetis "com.example.notes". */
    static File notesApk(File dir, String fileName) throws Exception {
        return notesApk(dir, fileName, false);
    }

    static File notesApk(File dir, String fileName, boolean splitRequired) throws Exception {
        return notesApk(dir, fileName, splitRequired, 26);
    }

    static File notesApk(File dir, String fileName, boolean splitRequired, int minSdk) throws Exception {
        String pkg = "com.example.notes";
        Map<String, byte[]> stored = new LinkedHashMap<>();
        Map<String, byte[]> deflated = new LinkedHashMap<>();
        deflated.put("AndroidManifest.xml", notesManifest(pkg, null, splitRequired, minSdk));
        stored.put("resources.arsc", arsc(pkg, true));
        deflated.put("classes.dex", dex("Lcom/example/notes/MainActivity;", pkg + ".notes", "hello"));
        deflated.put("classes2.dex", dex("Lcom/pairip/licensecheck/LicenseClient;"));
        deflated.put("res/mipmap-anydpi-v26/ic_launcher.xml", shortcutsXml("dummy"));
        stored.put("res/mipmap-xxhdpi-v4/ic_launcher.png", png());
        deflated.put("res/xml/shortcuts.xml", shortcutsXml(pkg));
        stored.put("lib/arm64-v8a/libnotes.so", new byte[5000]);
        stored.put("lib/x86_64/libnotes.so", new byte[3000]);
        deflated.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        deflated.put("META-INF/CERT.SF", "x".getBytes(StandardCharsets.UTF_8));
        deflated.put("META-INF/CERT.RSA", "x".getBytes(StandardCharsets.UTF_8));
        deflated.put("META-INF/services/com.example.Plugin", "com.example.Impl\n".getBytes(StandardCharsets.UTF_8));
        return writeZip(new File(dir, fileName), stored, deflated);
    }

    static File splitApk(File dir, String fileName, String split, boolean withLib) throws Exception {
        Map<String, byte[]> stored = new LinkedHashMap<>();
        Map<String, byte[]> deflated = new LinkedHashMap<>();
        deflated.put("AndroidManifest.xml", notesManifest("com.example.notes", split));
        if (withLib) {
            stored.put("lib/arm64-v8a/libextra.so", new byte[777]);
        }
        return writeZip(new File(dir, fileName), stored, deflated);
    }

    static File writeZip(File f, Map<String, byte[]> stored, Map<String, byte[]> deflated) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (Map.Entry<String, byte[]> e : deflated.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setMethod(ZipEntry.DEFLATED);
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : stored.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setMethod(ZipEntry.STORED);
                ze.setSize(e.getValue().length);
                ze.setCompressedSize(e.getValue().length);
                CRC32 c = new CRC32();
                c.update(e.getValue());
                ze.setCrc(c.getValue());
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return f;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }
}
