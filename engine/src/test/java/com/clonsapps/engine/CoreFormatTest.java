package com.clonsapps.engine;

import com.clonsapps.engine.res.AndroidAttrs;
import com.clonsapps.engine.res.Arsc;
import com.clonsapps.engine.res.Axml;
import com.clonsapps.engine.sign.SigningIdentity;
import com.clonsapps.engine.zip.AlignedZipWriter;
import com.clonsapps.engine.zip.ZipArchive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Format biner (ZIP, XML biner, ARSC, DEX, sertifikat) — tanpa penandatanganan. */
public class CoreFormatTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void manifestIsReadCorrectly() throws Exception {
        ManifestInfo m = ManifestInfo.read(Axml.parse(Fixtures.notesManifest("com.example.notes", null), ErrorCode.BAD_MANIFEST));
        assertEquals("com.example.notes", m.packageName);
        assertEquals(7, m.versionCode);
        assertEquals("1.7", m.versionName);
        assertEquals(26, m.minSdk);
        assertEquals(34, m.targetSdk);
        assertTrue(m.isBase());
        assertEquals(Arrays.asList("com.example.notes.notes", "legacy.notes"), m.authorities);
        assertEquals(Arrays.asList("com.example.notes.permission.SYNC"), m.declaredPermissions);
        assertEquals(Arrays.asList("com.example.notes.MainActivity"), m.launcherActivities);
        assertEquals(Fixtures.ICON_ID, m.iconRes);
        assertEquals(Fixtures.LABEL_ID, m.labelRes);
        assertTrue(m.relativeComponents.containsAll(Arrays.asList(".NotesApp", ".MainActivity", "Settings", ".data.NotesProvider")));
        assertEquals(Arrays.asList(Fixtures.SHORTCUTS_ID), m.metaDataXmlRes);
    }

    @Test
    public void manifestPatchIsTargeted() throws Exception {
        Axml doc = Axml.parse(Fixtures.notesManifest("com.example.notes", null), ErrorCode.BAD_MANIFEST);
        IdentityMapper map = new IdentityMapper("com.example.notes", "com.example.notes.clone2");
        List<String> changes = new ArrayList<>();
        ManifestPatcher.patch(doc, map, new HashSet<>(Arrays.asList("com.example.notes.permission.SYNC")), "Catatan Kedua", changes);
        byte[] out = doc.serialize();
        Axml re = Axml.parse(out, ErrorCode.BAD_MANIFEST);
        ManifestInfo m = ManifestInfo.read(re);
        assertEquals("com.example.notes.clone2", m.packageName);
        assertTrue("tidak boleh ada nama relatif: " + m.relativeComponents, m.relativeComponents.isEmpty());
        assertEquals(Arrays.asList("com.example.notes.clone2.notes", "com.example.notes.clone2.legacy.notes"), m.authorities);
        assertEquals(Arrays.asList("com.example.notes.clone2.permission.SYNC"), m.declaredPermissions);
        assertTrue(m.usedPermissions.contains("com.example.notes.clone2.permission.SYNC"));
        assertTrue("permission framework tidak diubah", m.usedPermissions.contains("android.permission.INTERNET"));
        assertEquals("Catatan Kedua", m.labelText);
        assertEquals("kelas tetap di paket lama", Arrays.asList("com.example.notes.MainActivity"), m.launcherActivities);
        String dump = re.dump();
        assertTrue(dump, dump.contains("name=\"com.example.notes.NotesApp\""));
        assertTrue(dump, dump.contains("name=\"com.example.notes.Settings\""));
        assertTrue(dump, dump.contains("targetActivity=\"com.example.notes.MainActivity\""));
        assertTrue(dump, dump.contains("taskAffinity=\"com.example.notes.clone2.settings\""));
        assertTrue(dump, dump.contains("name=\"com.example.lib.SyncService\""));
        assertTrue(dump, dump.contains("permission=\"com.example.notes.clone2.permission.SYNC\""));
        assertTrue("permission milik sistem tidak diubah", dump.contains("readPermission=\"android.permission.READ_CONTACTS\""));
        assertTrue("authority di <queries> milik aplikasi lain tidak diubah", dump.contains("authorities=\"com.other.provider\""));
        // ID resource atribut tetap terpetakan
        Axml.Element app = null;
        for (Axml.Element e : re.elements()) {
            if ("application".equals(e.name)) {
                app = e;
            }
        }
        assertNotNull(app);
        assertNotNull(app.android(AndroidAttrs.LABEL, "label"));
        assertEquals(Axml.TYPE_STRING, app.android(AndroidAttrs.LABEL, "label").type);
        assertEquals(Axml.TYPE_REFERENCE, app.android(AndroidAttrs.ICON, "icon").type);
        assertFalse(changes.isEmpty());
    }

    @Test
    public void labelIsAddedWhenMissing() throws Exception {
        Fixtures.XmlBuilder x = new Fixtures.XmlBuilder();
        x.start("manifest", Fixtures.plain("package", "a.b"));
        x.leaf("application", Fixtures.bool("debuggable", AndroidAttrs.DEBUGGABLE, true));
        x.end("manifest");
        Axml doc = Axml.parse(x.build(true), ErrorCode.BAD_MANIFEST);
        ManifestPatcher.patch(doc, new IdentityMapper("a.b", "a.b.c"), new HashSet<String>(), "Baru", new ArrayList<String>());
        ManifestInfo m = ManifestInfo.read(Axml.parse(doc.serialize(), ErrorCode.BAD_MANIFEST));
        assertEquals("Baru", m.labelText);
        assertTrue(m.debuggable);
    }

    @Test
    public void axmlRoundTripIsStable() throws Exception {
        for (boolean utf8 : new boolean[]{true, false}) {
            byte[] src = Fixtures.shortcutsXml("com.example.notes");
            Axml a = Axml.parse(src, ErrorCode.BAD_MANIFEST);
            byte[] once = a.serialize();
            byte[] twice = Axml.parse(once, ErrorCode.BAD_MANIFEST).serialize();
            assertTrue(Arrays.equals(once, twice));
            assertEquals(a.dump(), Axml.parse(once, ErrorCode.BAD_MANIFEST).dump());
        }
    }

    @Test
    public void shortcutXmlTargetPackageIsUpdated() throws Exception {
        Axml x = Axml.parse(Fixtures.shortcutsXml("com.example.notes"), ErrorCode.BAD_RESOURCES);
        int n = ManifestPatcher.patchXmlResource(x, new IdentityMapper("com.example.notes", "com.example.notes.clone2"), new HashSet<String>());
        assertEquals(1, n);
        String d = Axml.parse(x.serialize(), ErrorCode.BAD_RESOURCES).dump();
        assertTrue(d, d.contains("targetPackage=\"com.example.notes.clone2\""));
        assertTrue("targetClass tetap", d.contains("targetClass=\"com.example.notes.MainActivity\""));
    }

    @Test
    public void corruptXmlIsRejectedWithSpecificCode() throws Exception {
        byte[] good = Fixtures.notesManifest("com.example.notes", null);
        try {
            Axml.parse(Arrays.copyOf(good, 40), ErrorCode.BAD_MANIFEST);
            fail();
        } catch (CloneException e) {
            assertEquals(ErrorCode.BAD_MANIFEST, e.code());
        }
        byte[] bad = good.clone();
        bad[0] = 9;
        try {
            Axml.parse(bad, ErrorCode.BAD_MANIFEST);
            fail();
        } catch (CloneException e) {
            assertEquals(ErrorCode.BAD_MANIFEST, e.code());
        }
    }

    @Test
    public void arscRenameAppendAndRepoint() throws Exception {
        for (boolean utf8 : new boolean[]{true, false}) {
            Arsc t = new Arsc(Fixtures.arsc("com.example.notes", utf8));
            assertEquals("Catatan", t.resolveString(Fixtures.LABEL_ID));
            assertEquals(Arrays.asList("res/mipmap-anydpi-v26/ic_launcher.xml", "res/mipmap-xxhdpi-v4/ic_launcher.png"),
                    t.filePaths(Fixtures.ICON_ID));
            assertEquals(1, t.renamePackage("com.example.notes", "com.example.notes.clone2"));
            assertEquals("com.example.notes.clone2", t.packages().get(0).name);
            int idx = t.appendGlobalString(CloneEngine.ICON_PATH);
            assertEquals(4, idx);
            for (Arsc.EntryRef r : t.entries(Fixtures.ICON_ID)) {
                t.setString(r, idx);
            }
            Arsc re = new Arsc(t.bytes());
            assertEquals(Arrays.asList(CloneEngine.ICON_PATH), re.filePaths(Fixtures.ICON_ID));
            assertEquals("string lama tidak bergeser", "Catatan", re.resolveString(Fixtures.LABEL_ID));
            assertEquals("res/xml/shortcuts.xml", re.globalString(3));
            assertEquals("com.example.notes.clone2", re.packages().get(0).name);
        }
    }

    @Test
    public void dexScannerFindsExactAndPrefix() {
        DexScanner.Result r = new DexScanner.Result();
        DexScanner.scan(Fixtures.dex("a.b.provider", "Lcom/pairip/x/Y;", "zzz"),
                new HashSet<>(Arrays.asList("a.b.provider", "nope")), Arrays.asList("Lcom/pairip/", "Lcom/facebook/"), r);
        assertEquals(new HashSet<>(Arrays.asList("a.b.provider")), r.exactMatches);
        assertEquals(new HashSet<>(Arrays.asList("Lcom/pairip/")), r.prefixMatches);
    }

    @Test
    public void alignedWriterAlignsStoredEntriesAndSoTo16k() throws Exception {
        File src = Fixtures.notesApk(tmp.getRoot(), "in.apk");
        File out = tmp.newFile("out.apk");
        try (ZipArchive z = ZipArchive.open(src); FileOutputStream fos = new FileOutputStream(out)) {
            AlignedZipWriter w = new AlignedZipWriter(fos);
            for (ZipArchive.Entry e : z.entries()) {
                w.copyRaw(z, e, CancelToken.NONE, null);
            }
            w.addBytes("res/new.png", Fixtures.png(), false, 0, 0x21);
            w.finish();
        }
        try (ZipArchive z = ZipArchive.open(out)) {
            int stored = 0;
            for (ZipArchive.Entry e : z.entries()) {
                if (e.method == ZipArchive.METHOD_STORED) {
                    stored++;
                    int align = e.name.endsWith(".so") ? 16384 : 4;
                    assertEquals(e.name, 0, e.dataOffset() % align);
                }
                z.read(e); // CRC tervalidasi
            }
            assertEquals(5, stored);
            // juga terbaca oleh java.util.zip
            try (java.util.zip.ZipFile jz = new java.util.zip.ZipFile(out)) {
                assertNotNull(jz.getEntry("lib/arm64-v8a/libnotes.so"));
            }
        }
    }

    @Test
    public void corruptZipVariantsAreReportedSpecifically() throws Exception {
        File src = Fixtures.notesApk(tmp.getRoot(), "in.apk");
        // terpotong
        File cut = tmp.newFile("cut.apk");
        byte[] all = java.nio.file.Files.readAllBytes(src.toPath());
        java.nio.file.Files.write(cut.toPath(), Arrays.copyOf(all, all.length / 2));
        try {
            ZipArchive.open(cut).close();
            fail();
        } catch (CloneException e) {
            assertEquals(ErrorCode.CORRUPT_ZIP, e.code());
        }
        // bukan zip
        File txt = tmp.newFile("x.apk");
        java.nio.file.Files.write(txt.toPath(), "bukan apk sama sekali, hanya teks biasa".getBytes("UTF-8"));
        try {
            ZipArchive.open(txt).close();
            fail();
        } catch (CloneException e) {
            assertEquals(ErrorCode.CORRUPT_ZIP, e.code());
        }
        // data entri rusak → CRC
        File flipped = tmp.newFile("flip.apk");
        java.nio.file.Files.write(flipped.toPath(), all);
        try (ZipArchive z = ZipArchive.open(src)) {
            ZipArchive.Entry so = z.get("lib/arm64-v8a/libnotes.so");
            try (RandomAccessFile raf = new RandomAccessFile(flipped, "rw")) {
                raf.seek(so.dataOffset() + 10);
                raf.write(0x5A);
            }
        }
        try (ZipArchive z = ZipArchive.open(flipped)) {
            z.read(z.get("lib/arm64-v8a/libnotes.so"));
            fail();
        } catch (CloneException e) {
            assertEquals(ErrorCode.CRC_MISMATCH, e.code());
        }
        // tanpa manifest
        File noManifest = Fixtures.writeZip(tmp.newFile("nomf.apk"), new java.util.HashMap<String, byte[]>(),
                java.util.Collections.singletonMap("classes.dex", Fixtures.dex("a")));
        try {
            ApkSet.openApk(noManifest);
            fail();
        } catch (CloneException e) {
            assertEquals(ErrorCode.MISSING_MANIFEST, e.code());
        }
    }

    @Test
    public void certificateIsValidAndSelfSigned() throws Exception {
        SigningIdentity id = SigningIdentity.generate("ClonApps test");
        id.certificate().checkValidity();
        id.certificate().verify(id.certificate().getPublicKey());
        assertEquals(3, id.certificate().getVersion());
        assertEquals("SHA256withRSA", id.certificate().getSigAlgName());
        assertTrue(id.certificate().getNotAfter().getTime() > 3300000000000L);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(id.privateKey());
        s.update(new byte[]{1, 2, 3});
        byte[] sig = s.sign();
        s.initVerify(id.certificate());
        s.update(new byte[]{1, 2, 3});
        assertTrue(s.verify(sig));
        assertEquals(64, id.certificateSha256().length());
    }

    @Test
    public void packageNameRules() {
        assertNull(PackageNames.check("com.example.notes.clone2"));
        assertEquals(ErrorCode.INVALID_PACKAGE_NAME, PackageNames.check("notes"));
        assertEquals(ErrorCode.INVALID_PACKAGE_NAME, PackageNames.check("com..notes"));
        assertEquals(ErrorCode.INVALID_PACKAGE_NAME, PackageNames.check("com.1notes"));
        assertEquals(ErrorCode.INVALID_PACKAGE_NAME, PackageNames.check("com.not-es"));
        assertEquals(ErrorCode.INVALID_PACKAGE_NAME, PackageNames.check("com.notes."));
        StringBuilder sb = new StringBuilder("a");
        while (sb.length() < 130) {
            sb.append(".b");
        }
        assertEquals(ErrorCode.PACKAGE_TOO_LONG, PackageNames.check(sb.toString()));
        assertEquals("com.example.notes.clone2", PackageNames.suggest("com.example.notes", 2));
        assertTrue(PackageNames.validFileName("Catatan Kedua"));
        assertFalse(PackageNames.validFileName("../etc"));
        assertFalse(PackageNames.validFileName("a/b"));
        assertEquals("a_b", PackageNames.sanitizeFileName("a/b"));
    }

    @Test
    public void identityMapperRules() {
        IdentityMapper m = new IdentityMapper("com.a", "com.a.c2");
        assertEquals("com.a.c2", m.mapOwned("com.a"));
        assertEquals("com.a.c2.fileprovider", m.mapOwned("com.a.fileprovider"));
        assertEquals("com.a.c2.x.y", m.mapOwned("x.y"));
        assertEquals("com.a.c2.p;com.a.c2.q.r", m.mapAuthorities("com.a.p; q.r"));
        assertEquals("other.affinity", m.mapAffinity("other.affinity"));
        assertEquals("com.a.c2:remote", m.mapAffinity("com.a:remote"));
        assertEquals("com.a.Main", m.absolutizeClass(".Main"));
        assertEquals("com.a.Main", m.absolutizeClass("Main"));
        assertEquals("x.y.Main", m.absolutizeClass("x.y.Main"));
        assertTrue(IdentityMapper.isRelativeClass(".X"));
        assertFalse(IdentityMapper.isRelativeClass("a.X"));
    }

    @Test
    public void installErrorsAreSpecific() {
        assertEquals("SIGNATURE_MISMATCH", InstallErrors.explain(1, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package x signatures do not match").code);
        assertEquals("CONFLICTING_PROVIDER", InstallErrors.explain(1, "INSTALL_FAILED_CONFLICTING_PROVIDER: x").code);
        assertEquals("ABORTED", InstallErrors.explain(InstallErrors.STATUS_FAILURE_ABORTED, null).code);
        assertEquals("STORAGE", InstallErrors.explain(InstallErrors.STATUS_FAILURE_STORAGE, "").code);
        assertEquals("NO_MATCHING_ABIS", InstallErrors.explain(7, "INSTALL_FAILED_NO_MATCHING_ABIS").code);
    }

    @Test
    public void signatureFilesDetection() {
        assertTrue(CloneEngine.isSignatureFile("META-INF/MANIFEST.MF"));
        assertTrue(CloneEngine.isSignatureFile("META-INF/CERT.RSA"));
        assertTrue(CloneEngine.isSignatureFile("META-INF/BNDLTOOL.SF"));
        assertTrue(CloneEngine.isSignatureFile("stamp-cert-sha256"));
        assertFalse(CloneEngine.isSignatureFile("META-INF/services/com.example.Plugin"));
        assertFalse(CloneEngine.isSignatureFile("META-INF/kotlinx_coroutines_core.version"));
    }
}
