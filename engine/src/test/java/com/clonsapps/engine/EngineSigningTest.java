package com.clonsapps.engine;

import com.clonsapps.engine.res.Arsc;
import com.clonsapps.engine.res.Axml;
import com.clonsapps.engine.sign.ApkSigning;
import com.clonsapps.engine.sign.SigningIdentity;
import com.clonsapps.engine.zip.ZipArchive;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pipeline lengkap pada APK sintetis base + split, termasuk tanda tangan apksig dan verifikasi. */
public class EngineSigningTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static SigningIdentity keyA;
    private static SigningIdentity keyB;
    private static final DeviceProfile ARM64 = DeviceProfile.of(34, "arm64-v8a", "armeabi-v7a");

    @BeforeClass
    public static void keys() throws Exception {
        keyA = SigningIdentity.generate("A");
        keyB = SigningIdentity.generate("B");
    }

    @Test
    public void baseAndSplitsAreClonedSignedAndVerified() throws Exception {
        File root = tmp.getRoot();
        List<File> in = Arrays.asList(
                Fixtures.notesApk(root, "base.apk"),
                Fixtures.splitApk(root, "abi.apk", "config.arm64_v8a", true),
                Fixtures.splitApk(root, "dpi.apk", "config.xxhdpi", false));
        File out = tmp.newFolder("out");
        final List<Stage> stages = new ArrayList<>();
        CloneEngine engine = new CloneEngine(new CloneEngine.Listener() {
            @Override
            public void onStage(Stage stage, String detail) {
                stages.add(stage);
            }

            @Override
            public void onProgress(Stage stage, long done, long total) {
            }

            @Override
            public void onLog(String line) {
            }
        }, null, null);
        CloneSpec spec = new CloneSpec("com.example.notes.clone2", "Catatan Kedua", Fixtures.png(), "Catatan Kedua");
        CloneResult r = engine.run(in, spec, keyA, ARM64, out);

        assertEquals(Arrays.asList(Stage.values()), stages);
        assertEquals(3, r.outputs.size());
        assertEquals(keyA.certificateSha256(), r.certSha256);
        assertFalse(new File(out, ".tmp").exists());
        for (CloneResult.Output o : r.outputs) {
            ApkSigning.Verification v = ApkSigning.verify(o.file, 26);
            assertTrue(o.file + " " + v.errors, v.verified);
            assertTrue(v.v2);
            assertEquals(64, o.sha256.length());
            try (ZipArchive z = ZipArchive.open(o.file)) {
                ManifestInfo m = ManifestInfo.read(Axml.parse(z.read(z.get("AndroidManifest.xml")), ErrorCode.BAD_MANIFEST));
                assertEquals("com.example.notes.clone2", m.packageName);
                assertNull("tanda tangan v1 lama dibuang", z.get("META-INF/CERT.RSA"));
                for (ZipArchive.Entry e : z.entries()) {
                    if (e.method == ZipArchive.METHOD_STORED) {
                        assertEquals(e.name, 0, e.dataOffset() % (e.name.endsWith(".so") ? 16384 : 4));
                    }
                }
            }
        }
        File base = new File(out, "base.apk");
        try (ZipArchive z = ZipArchive.open(base); ZipArchive src = ZipArchive.open(in.get(0))) {
            assertNotNull("file layanan META-INF dipertahankan", z.get("META-INF/services/com.example.Plugin"));
            assertNotNull(z.get(CloneEngine.ICON_PATH));
            assertArrayEquals("DEX tidak diubah", src.read(src.get("classes.dex")), z.read(z.get("classes.dex")));
            Arsc t = new Arsc(z.read(z.get("resources.arsc")));
            assertEquals("com.example.notes.clone2", t.packages().get(0).name);
            assertEquals(Arrays.asList(CloneEngine.ICON_PATH), t.filePaths(Fixtures.ICON_ID));
            String shortcuts = Axml.parse(z.read(z.get("res/xml/shortcuts.xml")), ErrorCode.BAD_RESOURCES).dump();
            assertTrue(shortcuts, shortcuts.contains("targetPackage=\"com.example.notes.clone2\""));
        }
        String w = r.warnings.toString();
        assertTrue(w, w.contains("HARDCODED_AUTHORITY") && w.contains("LICENSE_CHECK"));
        assertTrue(r.changes.toString(), r.changes.toString().contains("authority: com.example.notes.notes;legacy.notes"));
    }

    @Test
    public void sameKeyIsReusedForUpdatesDifferentKeyDiffers() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        CloneSpec spec = new CloneSpec("com.example.notes.clone3", null, null, "x");
        CloneResult r1 = new CloneEngine(null, null, null).run(Arrays.asList(base), spec, keyA, ARM64, tmp.newFolder("o1"));
        CloneResult r2 = new CloneEngine(null, null, null).run(Arrays.asList(base), spec, keyA, ARM64, tmp.newFolder("o2"));
        CloneResult r3 = new CloneEngine(null, null, null).run(Arrays.asList(base), spec, keyB, ARM64, tmp.newFolder("o3"));
        assertEquals(r1.certSha256, r2.certSha256);
        assertNotEquals(r1.certSha256, r3.certSha256);
        assertEquals("tanpa label baru, label asli dipertahankan", "Catatan", r1.label);
    }
}
