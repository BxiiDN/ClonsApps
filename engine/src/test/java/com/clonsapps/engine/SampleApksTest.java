package com.clonsapps.engine;

import com.clonsapps.engine.res.Arsc;
import com.clonsapps.engine.res.Axml;
import com.clonsapps.engine.sign.ApkSigning;
import com.clonsapps.engine.sign.SigningIdentity;
import com.clonsapps.engine.zip.ZipArchive;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cloning APK contoh milik proyek yang dibangun Android Gradle Plugin sungguhan (samples/*).
 * Dijalankan oleh `./gradlew :engine:test` yang lebih dulu membangun sampel.
 */
public class SampleApksTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static SigningIdentity key;
    private static final DeviceProfile X86_64 = DeviceProfile.of(34, "x86_64", "arm64-v8a");

    @BeforeClass
    public static void setUp() throws Exception {
        key = SigningIdentity.generate("samples");
    }

    private static File sample(String name) {
        String dir = System.getProperty("samples.dir");
        Assume.assumeTrue("samples.dir tidak diset", dir != null);
        File f = new File(dir, name + "/build/outputs/apk/debug/" + name + "-debug.apk");
        assertTrue("APK sampel tidak ada: " + f, f.isFile());
        return f;
    }

    private CloneResult cloneAndCheck(List<File> in, String newPkg, String label) throws Exception {
        Inspection insp = Inspector.inspect(in, X86_64, new CloneSpec(newPkg, label, null, "x"), CancelToken.NONE);
        assertFalse(insp.issues.toString(), insp.hasErrors());
        CloneResult r = new CloneEngine(null, null, null).run(in, new CloneSpec(newPkg, label, Fixtures.png(), "x"), key, X86_64,
                tmp.newFolder());
        assertEquals(in.size(), r.outputs.size());
        for (CloneResult.Output o : r.outputs) {
            ApkSigning.Verification v = ApkSigning.verify(o.file, 28);
            assertTrue(o.file + ": " + v.errors, v.verified);
            assertEquals(1, v.signers.size());
        }
        // Isi arsip tetap lengkap: entri sumber (kecuali tanda tangan lama) ada di hasil.
        for (int i = 0; i < in.size(); i++) {
            try (ZipArchive src = ZipArchive.open(in.get(i))) {
                String srcSplit = ManifestInfo.read(Axml.parse(src.read(src.get("AndroidManifest.xml")), ErrorCode.BAD_MANIFEST)).split;
                File outFile = null;
                for (CloneResult.Output o : r.outputs) {
                    if (srcSplit == null ? o.splitName == null : srcSplit.equals(o.splitName)) {
                        outFile = o.file;
                    }
                }
                assertNotNull(outFile);
                try (ZipArchive dst = ZipArchive.open(outFile)) {
                    for (ZipArchive.Entry e : src.entries()) {
                        if (!CloneEngine.isSignatureFile(e.name)) {
                            assertNotNull(e.name, dst.get(e.name));
                        }
                    }
                    for (ZipArchive.Entry e : dst.entries()) {
                        if (e.name.endsWith(".dex") || e.name.endsWith(".so")) {
                            assertEquals("tidak diubah: " + e.name, src.get(e.name).crc, e.crc);
                        }
                        if (e.method == ZipArchive.METHOD_STORED) {
                            assertEquals(e.name, 0, e.dataOffset() % (e.name.endsWith(".so") ? 16384 : 4));
                        }
                    }
                    ManifestInfo m = ManifestInfo.read(Axml.parse(dst.read(dst.get("AndroidManifest.xml")), ErrorCode.BAD_MANIFEST));
                    assertEquals(newPkg, m.packageName);
                    if (dst.get("resources.arsc") != null) {
                        Arsc t = new Arsc(dst.read(dst.get("resources.arsc")));
                        for (Arsc.Pkg p : t.packages()) {
                            if (p.id == 0x7f) {
                                assertEquals(newPkg, p.name);
                            }
                        }
                    }
                }
            }
        }
        return r;
    }

    @Test
    public void simple() throws Exception {
        CloneResult r = cloneAndCheck(Collections.singletonList(sample("simple")), "com.clonsapps.sample.simple.clone2", "Simple Kedua");
        assertTrue(r.changes.toString(), r.changes.toString().contains("label → \"Simple Kedua\""));
        assertTrue(r.changes.toString(), r.changes.toString().contains("ikon:"));
    }

    @Test
    public void multidex() throws Exception {
        File apk = sample("multidex");
        try (ZipArchive z = ZipArchive.open(apk)) {
            assertNotNull("sampel harus benar-benar multidex", z.get("classes2.dex"));
        }
        cloneAndCheck(Collections.singletonList(apk), "com.clonsapps.sample.multidex.clone2", null);
    }

    @Test
    public void nativeLibrary() throws Exception {
        File apk = sample("nativelib");
        Set<String> abis;
        try (ApkSet set = ApkSet.open(Collections.singletonList(apk), CancelToken.NONE)) {
            abis = set.base.nativeAbis();
        }
        assertTrue(abis.toString(), abis.contains("x86_64"));
        cloneAndCheck(Collections.singletonList(apk), "com.clonsapps.sample.nativelib.clone2", null);
    }

    @Test
    public void providerAuthorityAndPermissionAreRenamed() throws Exception {
        File apk = sample("provider");
        Inspection insp = Inspector.inspect(Collections.singletonList(apk), X86_64,
                new CloneSpec("com.clonsapps.sample.provider.clone2", null, null, "x"), CancelToken.NONE);
        assertEquals("com.clonsapps.sample.provider.clone2.notes", insp.authorityMap.get("com.clonsapps.sample.provider.notes"));
        assertTrue(insp.permissionMap.toString(), insp.permissionMap.containsKey("com.clonsapps.sample.provider.permission.NOTES"));
        assertTrue("string authority lama di kode harus terdeteksi: " + insp.issues, insp.issues.toString().contains("HARDCODED_AUTHORITY"));
        CloneResult r = cloneAndCheck(Collections.singletonList(apk), "com.clonsapps.sample.provider.clone2", null);
        try (ZipArchive z = ZipArchive.open(r.outputs.get(0).file)) {
            ManifestInfo m = ManifestInfo.read(Axml.parse(z.read(z.get("AndroidManifest.xml")), ErrorCode.BAD_MANIFEST));
            assertTrue(m.authorities.toString(), m.authorities.contains("com.clonsapps.sample.provider.clone2.notes"));
            for (String a : m.authorities) {
                assertTrue(a, a.startsWith("com.clonsapps.sample.provider.clone2."));
            }
            assertTrue(m.declaredPermissions.toString(), m.declaredPermissions.contains("com.clonsapps.sample.provider.clone2.permission.NOTES"));
        }
    }

    @Test
    public void splitApksFromBundletool() throws Exception {
        String path = System.getProperty("sample.split.apks");
        Assume.assumeTrue("sample.split.apks tidak diset", path != null && !path.isEmpty());
        BundleExtractor.Extracted ex = BundleExtractor.extract(new File(path), tmp.newFolder(), X86_64, CancelToken.NONE);
        assertTrue("harus ada base + split: " + ex.apks, ex.apks.size() >= 2);
        Set<String> splits = new HashSet<>();
        try (ApkSet set = ApkSet.open(ex.apks, CancelToken.NONE)) {
            for (ApkSet.Apk a : set.splits) {
                splits.add(a.splitName());
            }
        }
        assertTrue(splits.toString(), splits.contains("config.x86_64"));
        List<File> in = new ArrayList<>(ex.apks);
        CloneResult r = cloneAndCheck(in, "com.clonsapps.sample.split.clone2", "Split Kedua");
        int iconFiles = 0;
        for (CloneResult.Output o : r.outputs) {
            try (ZipArchive z = ZipArchive.open(o.file)) {
                if (z.get(CloneEngine.ICON_PATH) != null) {
                    iconFiles++;
                }
            }
        }
        assertTrue("ikon diterapkan di base dan split density: " + iconFiles, iconFiles >= 1);
    }
}
