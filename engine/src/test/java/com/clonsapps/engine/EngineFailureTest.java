package com.clonsapps.engine;

import com.clonsapps.engine.sign.SigningIdentity;
import com.clonsapps.engine.zip.ZipArchive;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Jalur kegagalan: semuanya terjadi sebelum tanda tangan dan wajib membersihkan file parsial. */
public class EngineFailureTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static SigningIdentity id;
    private static final DeviceProfile ARM64 = DeviceProfile.of(34, "arm64-v8a", "armeabi-v7a");

    @BeforeClass
    public static void key() throws Exception {
        id = SigningIdentity.generate("test");
    }

    private CloneSpec spec() {
        return new CloneSpec("com.example.notes.clone2", "Catatan Kedua", null, "Catatan Kedua");
    }

    private static void assertNoOutputs(File outDir) {
        File[] files = outDir.listFiles();
        if (files != null) {
            for (File f : files) {
                assertFalse("file sisa: " + f, f.getName().endsWith(".apk") || f.getName().equals(".tmp"));
            }
        }
    }

    private CloneException expectFailure(List<File> in, CloneSpec spec, DeviceProfile dev, CloneEngine engine, File out) {
        try {
            engine.run(in, spec, id, dev, out);
            fail("seharusnya gagal");
            return null;
        } catch (CloneException e) {
            return e;
        }
    }

    @Test
    public void cancelDuringPackagingRemovesPartialFiles() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        File out = tmp.newFolder("out");
        final AtomicBoolean cancel = new AtomicBoolean(false);
        CloneEngine engine = new CloneEngine(new CloneEngine.Listener() {
            @Override
            public void onStage(Stage stage, String detail) {
            }

            @Override
            public void onProgress(Stage stage, long done, long total) {
                if (stage == Stage.PACKAGE_ALIGN && done > 0) {
                    cancel.set(true);
                }
            }

            @Override
            public void onLog(String line) {
            }
        }, cancel::get, null);
        CloneException e = expectFailure(Collections.singletonList(base), spec(), ARM64, engine, out);
        assertEquals(ErrorCode.CANCELLED, e.code());
        assertNoOutputs(out);
    }

    @Test
    public void cancelBeforeStartStopsImmediately() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        File out = tmp.newFolder("out");
        CloneEngine engine = new CloneEngine(null, () -> true, null);
        assertEquals(ErrorCode.CANCELLED, expectFailure(Collections.singletonList(base), spec(), ARM64, engine, out).code());
        assertNoOutputs(out);
    }

    @Test
    public void notEnoughSpaceIsDetectedBeforeWriting() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        File out = tmp.newFolder("out");
        CloneEngine.Options o = new CloneEngine.Options();
        o.freeSpace = dir -> 1024 * 1024;
        CloneException e = expectFailure(Collections.singletonList(base), spec(), ARM64, new CloneEngine(null, null, o), out);
        assertEquals(ErrorCode.STORAGE_FULL, e.code());
        assertTrue(e.detail(), e.detail().contains("tersedia 1 MB"));
        assertNoOutputs(out);
    }

    @Test
    public void diskFullWhileWritingIsMappedAndCleanedUp() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        File out = tmp.newFolder("out");
        CloneEngine.Options o = new CloneEngine.Options();
        o.outputFactory = f -> new FilterOutputStream(new FileOutputStream(f)) {
            long written;

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                written += len;
                if (written > 2000) {
                    throw new IOException("write failed: ENOSPC (No space left on device)");
                }
                out.write(b, off, len);
            }

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }
        };
        CloneException e = expectFailure(Collections.singletonList(base), spec(), ARM64, new CloneEngine(null, null, o), out);
        assertEquals(ErrorCode.STORAGE_FULL, e.code());
        assertNoOutputs(out);
    }

    @Test
    public void corruptEntryIsDetectedWhileCopying() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        try (ZipArchive z = ZipArchive.open(base); RandomAccessFile raf = new RandomAccessFile(base, "rw")) {
            raf.seek(z.get("lib/x86_64/libnotes.so").dataOffset() + 5);
            raf.write(0x42);
        }
        File out = tmp.newFolder("out");
        CloneException e = expectFailure(Collections.singletonList(base), spec(), ARM64, new CloneEngine(null, null, null), out);
        assertEquals(ErrorCode.CRC_MISMATCH, e.code());
        assertTrue(e.detail(), e.detail().contains("lib/x86_64/libnotes.so"));
        assertNoOutputs(out);
    }

    @Test
    public void truncatedApkIsCorrupt() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        byte[] all = java.nio.file.Files.readAllBytes(base.toPath());
        java.nio.file.Files.write(base.toPath(), Arrays.copyOf(all, all.length - 30));
        File out = tmp.newFolder("out");
        assertEquals(ErrorCode.CORRUPT_ZIP,
                expectFailure(Collections.singletonList(base), spec(), ARM64, new CloneEngine(null, null, null), out).code());
    }

    @Test
    public void requiredSplitMissing() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk", true);
        File out = tmp.newFolder("out");
        CloneException e = expectFailure(Collections.singletonList(base), spec(), ARM64, new CloneEngine(null, null, null), out);
        assertEquals(ErrorCode.MISSING_SPLIT, e.code());
        assertNoOutputs(out);
    }

    @Test
    public void splitSetConsistencyChecks() throws Exception {
        File root = tmp.getRoot();
        File base = Fixtures.notesApk(root, "base.apk");
        File split = Fixtures.splitApk(root, "s1.apk", "config.arm64_v8a", true);
        File dup = Fixtures.splitApk(root, "s2.apk", "config.arm64_v8a", true);
        CloneEngine engine = new CloneEngine(null, null, null);
        File out = tmp.newFolder("out");
        assertEquals(ErrorCode.NO_BASE_APK, expectFailure(Collections.singletonList(split), spec(), ARM64, engine, out).code());
        assertEquals(ErrorCode.DUPLICATE_SPLIT, expectFailure(Arrays.asList(base, split, dup), spec(), ARM64, engine, out).code());
        assertEquals(ErrorCode.MULTIPLE_BASE_APK, expectFailure(Arrays.asList(base, Fixtures.notesApk(root, "b2.apk")), spec(), ARM64, engine, out).code());
        // split dari paket lain
        Map<String, byte[]> d = new LinkedHashMap<>();
        d.put("AndroidManifest.xml", Fixtures.notesManifest("com.other.app", "config.xxhdpi"));
        File foreign = Fixtures.writeZip(new File(root, "foreign.apk"), new LinkedHashMap<String, byte[]>(), d);
        assertEquals(ErrorCode.SPLIT_PACKAGE_MISMATCH, expectFailure(Arrays.asList(base, foreign), spec(), ARM64, engine, out).code());
        assertNoOutputs(out);
    }

    @Test
    public void abiAndSdkCompatibility() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        File out = tmp.newFolder("out");
        CloneEngine engine = new CloneEngine(null, null, null);
        CloneException abi = expectFailure(Collections.singletonList(base), spec(), DeviceProfile.of(34, "riscv64"), engine, out);
        assertEquals(ErrorCode.ABI_INCOMPATIBLE, abi.code());
        assertTrue(abi.detail(), abi.detail().contains("arm64-v8a") && abi.detail().contains("riscv64"));
        assertEquals(ErrorCode.MIN_SDK_TOO_HIGH,
                expectFailure(Collections.singletonList(base), spec(), DeviceProfile.of(24, "arm64-v8a"), engine, out).code());
    }

    @Test
    public void identityValidation() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        File out = tmp.newFolder("out");
        CloneEngine engine = new CloneEngine(null, null, null);
        assertEquals(ErrorCode.PACKAGE_SAME_AS_ORIGINAL, expectFailure(Collections.singletonList(base),
                new CloneSpec("com.example.notes", null, null, "x"), ARM64, engine, out).code());
        assertEquals(ErrorCode.INVALID_PACKAGE_NAME, expectFailure(Collections.singletonList(base),
                new CloneSpec("notes", null, null, "x"), ARM64, engine, out).code());
        assertEquals(ErrorCode.INVALID_LABEL, expectFailure(Collections.singletonList(base),
                new CloneSpec("com.example.notes.c2", "  ", null, "x"), ARM64, engine, out).code());
    }

    @Test
    public void inspectionReportsRisksAndPlan() throws Exception {
        File base = Fixtures.notesApk(tmp.getRoot(), "base.apk");
        Inspection insp = Inspector.inspect(Collections.singletonList(base), ARM64,
                new CloneSpec("com.example.notes.clone2", "Catatan Kedua", Fixtures.png(), "x"), CancelToken.NONE);
        assertFalse(insp.issues.toString(), insp.hasErrors());
        assertEquals("Catatan", insp.label);
        assertEquals(2, insp.dexCount);
        assertEquals("com.example.notes.clone2.notes", insp.authorityMap.get("com.example.notes.notes"));
        assertEquals("com.example.notes.clone2.legacy.notes", insp.authorityMap.get("legacy.notes"));
        assertEquals("com.example.notes.clone2.permission.SYNC", insp.permissionMap.get("com.example.notes.permission.SYNC"));
        String all = insp.issues.toString();
        assertTrue(all, all.contains("HARDCODED_AUTHORITY"));
        assertTrue(all, all.contains("LICENSE_CHECK"));
        assertTrue(all, all.contains("NATIVE_LIBS"));
        assertEquals(Collections.singletonList(Fixtures.ICON_ID), insp.iconResIds);
    }

    @Test
    public void bundletoolVariantsAreSelectedBySdk() throws Exception {
        File root = tmp.getRoot();
        Map<String, byte[]> stored = new LinkedHashMap<>();
        stored.put("splits/base-master.apk", java.nio.file.Files.readAllBytes(Fixtures.notesApk(root, "v1.apk", false, 26).toPath()));
        stored.put("splits/base-master_2.apk", java.nio.file.Files.readAllBytes(Fixtures.notesApk(root, "v2.apk", false, 31).toPath()));
        stored.put("splits/base-master_3.apk", java.nio.file.Files.readAllBytes(Fixtures.notesApk(root, "v3.apk", false, 35).toPath()));
        byte[] abi = java.nio.file.Files.readAllBytes(Fixtures.splitApk(root, "a.apk", "config.x86_64", false).toPath());
        stored.put("splits/base-x86_64.apk", abi);
        stored.put("splits/base-x86_64_2.apk", abi);
        stored.put("splits/base-x86_64_3.apk", abi);
        File bundle = Fixtures.writeZip(new File(root, "variants.apks"), stored, new LinkedHashMap<String, byte[]>());
        DeviceProfile api34 = DeviceProfile.of(34, "x86_64");
        BundleExtractor.Extracted ex = BundleExtractor.extract(bundle, tmp.newFolder("vx"), api34, CancelToken.NONE);
        assertEquals("varian 2 (minSdk 31) untuk API 34: " + ex.apks, 2, ex.apks.size());
        assertTrue(ex.notes.toString(), ex.notes.toString().contains("varian 2"));
        try (ApkSet set = ApkSet.open(ex.apks, CancelToken.NONE)) {
            assertEquals(31, set.base.info.minSdk);
            assertEquals("config.x86_64", set.splits.get(0).splitName());
        }
        ManifestInfo cfg = new ManifestInfo();
        cfg.split = "config.x86_64";
        assertEquals(1, BundleExtractor.bundletoolVariant("splits/base-x86_64.apk", cfg));
        assertEquals(2, BundleExtractor.bundletoolVariant("splits/base-x86_64_2.apk", cfg));
        ManifestInfo master = new ManifestInfo();
        assertEquals(3, BundleExtractor.bundletoolVariant("splits/base-master_3.apk", master));
        try {
            BundleExtractor.extract(bundle, tmp.newFolder("vy"), DeviceProfile.of(24, "x86_64"), CancelToken.NONE);
            fail();
        } catch (CloneException e) {
            assertEquals(ErrorCode.MIN_SDK_TOO_HIGH, e.code());
        }
    }

    @Test
    public void bundleExtractionKeepsSplitsSeparateAndSkipsForeignAbi() throws Exception {
        File root = tmp.getRoot();
        Map<String, byte[]> stored = new LinkedHashMap<>();
        stored.put("splits/base-master.apk", java.nio.file.Files.readAllBytes(Fixtures.notesApk(root, "b.apk").toPath()));
        stored.put("splits/base-arm64_v8a.apk", java.nio.file.Files.readAllBytes(Fixtures.splitApk(root, "a.apk", "config.arm64_v8a", true).toPath()));
        stored.put("splits/base-x86.apk", java.nio.file.Files.readAllBytes(Fixtures.splitApk(root, "x.apk", "config.x86", false).toPath()));
        stored.put("standalones/standalone-arm64_v8a.apk", java.nio.file.Files.readAllBytes(Fixtures.notesApk(root, "s.apk").toPath()));
        stored.put("Android/obb/com.example.notes/main.7.com.example.notes.obb", new byte[10]);
        File bundle = Fixtures.writeZip(new File(root, "notes.apks"), stored, new LinkedHashMap<String, byte[]>());
        BundleExtractor.Extracted ex = BundleExtractor.extract(bundle, tmp.newFolder("ex"), ARM64, CancelToken.NONE);
        assertEquals(2, ex.apks.size());
        String notes = ex.notes.toString();
        assertTrue(notes, notes.contains("ABI_SPLIT_SKIPPED") && notes.contains("OBB_IGNORED"));
        try (ApkSet set = ApkSet.open(ex.apks, CancelToken.NONE)) {
            assertEquals(1, set.splits.size());
            assertEquals("config.arm64_v8a", set.splits.get(0).splitName());
        }
        // bukan APK maupun bundel
        File junk = Fixtures.writeZip(new File(root, "junk.zip"), new LinkedHashMap<String, byte[]>(),
                Collections.singletonMap("readme.txt", "x".getBytes("UTF-8")));
        try {
            BundleExtractor.extract(junk, tmp.newFolder("ex2"), ARM64, CancelToken.NONE);
            fail();
        } catch (CloneException e) {
            assertEquals(ErrorCode.MISSING_MANIFEST, e.code());
        }
    }
}
