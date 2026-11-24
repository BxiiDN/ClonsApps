package com.clonsapps.engine;

import com.clonsapps.engine.res.Arsc;
import com.clonsapps.engine.res.Axml;
import com.clonsapps.engine.sign.ApkSigning;
import com.clonsapps.engine.sign.SigningIdentity;
import com.clonsapps.engine.zip.AlignedZipWriter;
import com.clonsapps.engine.zip.ZipArchive;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline cloning di perangkat:
 * baca → periksa → ubah manifest → ubah resource → kemas + alignment → tanda tangan → verifikasi.
 * Hasil hanya dikembalikan bila semua APK (base + split) lolos verifikasi; jika gagal atau dibatalkan,
 * semua file yang dibuat pada proses ini dihapus.
 */
public final class CloneEngine {

    public interface Listener {
        void onStage(Stage stage, String detail);

        void onProgress(Stage stage, long done, long total);

        void onLog(String line);
    }

    public interface FreeSpace {
        long usableBytes(File dir);
    }

    public interface OutputFactory {
        OutputStream open(File file) throws IOException;
    }

    public static final class Options {
        public FreeSpace freeSpace = new FreeSpace() {
            @Override
            public long usableBytes(File dir) {
                return dir.getUsableSpace();
            }
        };
        public OutputFactory outputFactory = new OutputFactory() {
            @Override
            public OutputStream open(File file) throws IOException {
                return new FileOutputStream(file);
            }
        };
        /** Ruang minimal tambahan di luar perkiraan ukuran hasil. */
        public long reserveBytes = 64L * 1024 * 1024;
    }

    public static final String ICON_PATH = "res/clonapps_icon.png";

    private final Listener listener;
    private final CancelToken cancel;
    private final Options options;

    public CloneEngine(Listener listener, CancelToken cancel, Options options) {
        this.listener = listener != null ? listener : new Listener() {
            @Override
            public void onStage(Stage stage, String detail) {
            }

            @Override
            public void onProgress(Stage stage, long done, long total) {
            }

            @Override
            public void onLog(String line) {
            }
        };
        this.cancel = cancel != null ? cancel : CancelToken.NONE;
        this.options = options != null ? options : new Options();
    }

    static boolean isSignatureFile(String name) {
        if (name.equals("stamp-cert-sha256")) {
            return true;
        }
        if (!name.startsWith("META-INF/") || name.indexOf('/', 9) >= 0) {
            return false;
        }
        String n = name.substring(9).toUpperCase(java.util.Locale.ROOT);
        return n.equals("MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA")
                || n.endsWith(".EC") || n.startsWith("SIG-");
    }

    public static String outputFileName(ApkSet.Apk apk) {
        return apk.isBase() ? "base.apk" : "split_" + apk.info.split + ".apk";
    }

    public CloneResult run(List<File> inputs, CloneSpec spec, SigningIdentity identity, DeviceProfile device, File outDir)
            throws CloneException {
        List<File> created = new ArrayList<>();
        File tmpDir = new File(outDir, ".tmp");
        try {
            CloneResult r = runInternal(inputs, spec, identity, device, outDir, tmpDir, created);
            deleteTree(tmpDir);
            return r;
        } catch (CloneException e) {
            cleanup(created, tmpDir);
            throw e;
        } catch (RuntimeException e) {
            cleanup(created, tmpDir);
            throw new CloneException(ErrorCode.INTERNAL, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (OutOfMemoryError e) {
            cleanup(created, tmpDir);
            throw new CloneException(ErrorCode.INTERNAL, "memori tidak cukup untuk memproses APK ini", null);
        }
    }

    private void cleanup(List<File> created, File tmpDir) {
        for (File f : created) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        deleteTree(tmpDir);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private CloneResult runInternal(List<File> inputs, CloneSpec spec, SigningIdentity identity, DeviceProfile device,
                                    File outDir, File tmpDir, List<File> created) throws CloneException {
        PackageNames.require(spec.newPackage);
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new CloneException(ErrorCode.IO_ERROR, "tidak bisa membuat folder " + outDir);
        }
        long inputBytes = 0;
        for (File f : inputs) {
            inputBytes += f.length();
        }
        long need = inputBytes * 2 + options.reserveBytes;
        long free = options.freeSpace.usableBytes(outDir);
        if (free < need) {
            throw new CloneException(ErrorCode.STORAGE_FULL,
                    "butuh ±" + mb(need) + " MB, tersedia " + mb(free) + " MB");
        }

        // 1. Baca
        listener.onStage(Stage.READ, inputs.size() + " file");
        try (ApkSet set = ApkSet.open(inputs, cancel)) {
            String oldPkg = set.base.info.packageName;
            listener.onLog("Paket asli " + oldPkg + " versi " + set.base.info.versionName + " (" + set.base.info.versionCode
                    + "), split: " + set.splits.size());

            // 2. Periksa
            listener.onStage(Stage.CHECK, null);
            Inspection insp = Inspector.inspect(set, device, spec, cancel);
            Issue err = insp.firstError();
            if (err != null) {
                ErrorCode code;
                try {
                    code = ErrorCode.valueOf(err.code);
                } catch (IllegalArgumentException ex) {
                    code = ErrorCode.INTERNAL;
                }
                throw new CloneException(code, err.detail);
            }
            if (spec.newPackage.equals(oldPkg)) {
                throw new CloneException(ErrorCode.PACKAGE_SAME_AS_ORIGINAL, oldPkg);
            }
            CloneResult result = new CloneResult();
            result.oldPackage = oldPkg;
            result.newPackage = spec.newPackage;
            result.versionCode = set.base.info.versionCode;
            result.versionName = set.base.info.versionName;
            result.label = spec.newLabel != null ? spec.newLabel : insp.label;
            for (Issue i : insp.issues) {
                if (i.severity == Issue.Severity.WARNING) {
                    result.warnings.add(i);
                }
            }
            IdentityMapper map = new IdentityMapper(oldPkg, spec.newPackage);
            Set<String> declaredPerms = new HashSet<>();
            Set<String> oldAuthorities = new HashSet<>();
            for (ApkSet.Apk a : set.all()) {
                declaredPerms.addAll(a.info.declaredPermissions);
                oldAuthorities.addAll(a.info.authorities);
            }

            // 3. Manifest
            listener.onStage(Stage.PATCH_MANIFEST, null);
            Map<ApkSet.Apk, Map<String, byte[]>> replacements = new LinkedHashMap<>();
            Map<ApkSet.Apk, Map<String, byte[]>> additions = new LinkedHashMap<>();
            for (ApkSet.Apk a : set.all()) {
                CancelToken.check(cancel);
                replacements.put(a, new HashMap<String, byte[]>());
                additions.put(a, new LinkedHashMap<String, byte[]>());
                Axml doc = a.parseManifest();
                ManifestPatcher.patch(doc, map, declaredPerms, spec.newLabel, result.changes);
                byte[] bytes = doc.serialize();
                ManifestInfo check = ManifestInfo.read(Axml.parse(bytes, ErrorCode.BAD_MANIFEST));
                if (!spec.newPackage.equals(check.packageName)) {
                    throw new CloneException(ErrorCode.INTERNAL, "manifest hasil tidak berisi paket baru");
                }
                replacements.get(a).put("AndroidManifest.xml", bytes);
            }

            // 4. Resource
            listener.onStage(Stage.PATCH_RESOURCES, null);
            Map<ApkSet.Apk, Arsc> tables = new LinkedHashMap<>();
            for (ApkSet.Apk a : set.all()) {
                CancelToken.check(cancel);
                Arsc t = Inspector.readArsc(a);
                if (t == null) {
                    continue;
                }
                int n = t.renamePackage(oldPkg, spec.newPackage);
                if (n > 0) {
                    result.changes.add("[" + label(a) + "] resources.arsc: nama paket resource → " + spec.newPackage + " (" + n + ")");
                }
                tables.put(a, t);
            }
            if (spec.iconPng != null && !insp.iconResIds.isEmpty()) {
                int patched = 0;
                for (Map.Entry<ApkSet.Apk, Arsc> en : tables.entrySet()) {
                    Arsc t = en.getValue();
                    boolean any = false;
                    for (int id : insp.iconResIds) {
                        if (!t.entries(id).isEmpty()) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        continue;
                    }
                    String path = ICON_PATH;
                    for (int k = 2; en.getKey().zip.has(path); k++) {
                        path = "res/clonapps_icon_" + k + ".png";
                    }
                    int idx = t.appendGlobalString(path);
                    for (int id : insp.iconResIds) {
                        for (Arsc.EntryRef ref : t.entries(id)) {
                            t.setString(ref, idx);
                            patched++;
                        }
                    }
                    additions.get(en.getKey()).put(path, spec.iconPng);
                }
                if (patched > 0) {
                    result.changes.add("ikon: " + patched + " entri resource ikon diarahkan ke " + ICON_PATH);
                } else {
                    result.warnings.add(Issue.warn("ICON_NOT_APPLIED", "Ikon kustom tidak dapat diterapkan",
                            "entri ikon tidak ditemukan di resources.arsc", "Clone memakai ikon asli."));
                }
            }
            for (Map.Entry<ApkSet.Apk, Arsc> en : tables.entrySet()) {
                replacements.get(en.getKey()).put("resources.arsc", en.getValue().bytes());
            }
            // XML resource yang dirujuk meta-data (shortcut statis, sync adapter, searchable).
            Set<Integer> xmlIds = new LinkedHashSet<>();
            for (ApkSet.Apk a : set.all()) {
                xmlIds.addAll(a.info.metaDataXmlRes);
            }
            Set<String> xmlPaths = new LinkedHashSet<>();
            for (int id : xmlIds) {
                for (Arsc t : tables.values()) {
                    xmlPaths.addAll(t.filePaths(id));
                }
            }
            for (String p : xmlPaths) {
                if (!p.endsWith(".xml")) {
                    continue;
                }
                for (ApkSet.Apk a : set.all()) {
                    ZipArchive.Entry e = a.zip.get(p);
                    if (e == null) {
                        continue;
                    }
                    Axml x;
                    try {
                        x = Axml.parse(a.zip.read(e), ErrorCode.BAD_RESOURCES);
                    } catch (CloneException ex) {
                        if (ex.code() == ErrorCode.CRC_MISMATCH) {
                            throw ex;
                        }
                        result.warnings.add(Issue.warn("XML_SKIPPED", "XML resource dilewati", p + ": " + ex.detail(),
                                "Fitur yang memakai file ini (misalnya shortcut) mungkin merujuk paket lama."));
                        continue;
                    }
                    int changed = ManifestPatcher.patchXmlResource(x, map, oldAuthorities);
                    if (changed > 0) {
                        replacements.get(a).put(p, x.serialize());
                        result.changes.add("[" + label(a) + "] " + p + ": " + changed + " referensi identitas diperbarui");
                    }
                }
            }

            // 5. Kemas + alignment (sebelum tanda tangan)
            listener.onStage(Stage.PACKAGE_ALIGN, null);
            if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
                throw new CloneException(ErrorCode.IO_ERROR, "tidak bisa membuat folder sementara");
            }
            long totalRaw = 0;
            for (ApkSet.Apk a : set.all()) {
                for (ZipArchive.Entry e : a.zip.entries()) {
                    totalRaw += e.compressedSize;
                }
            }
            final long totalBytes = totalRaw;
            final long[] done = {0};
            AlignedZipWriter.Progress prog = new AlignedZipWriter.Progress() {
                @Override
                public void advance(long bytes) {
                    done[0] += bytes;
                    listener.onProgress(Stage.PACKAGE_ALIGN, done[0], totalBytes);
                }
            };
            Map<ApkSet.Apk, File> unsigned = new LinkedHashMap<>();
            for (ApkSet.Apk a : set.all()) {
                CancelToken.check(cancel);
                File u = new File(tmpDir, outputFileName(a) + ".unsigned");
                created.add(u);
                writeUnsigned(a, u, replacements.get(a), additions.get(a), prog);
                unsigned.put(a, u);
            }

            // 6. Tanda tangan
            listener.onStage(Stage.SIGN, null);
            int i = 0;
            for (Map.Entry<ApkSet.Apk, File> en : unsigned.entrySet()) {
                CancelToken.check(cancel);
                File out = new File(outDir, outputFileName(en.getKey()));
                created.add(out);
                ApkSigning.sign(en.getValue(), out, identity, set.base.info.minSdk);
                //noinspection ResultOfMethodCallIgnored
                en.getValue().delete();
                listener.onProgress(Stage.SIGN, ++i, unsigned.size());
                result.outputs.add(new CloneResult.Output(out, en.getKey().info.split, null, out.length()));
            }

            // 7. Verifikasi
            listener.onStage(Stage.VERIFY, null);
            String certHash = identity.certificateSha256();
            result.certSha256 = certHash;
            List<CloneResult.Output> verified = new ArrayList<>();
            String schemes = null;
            for (CloneResult.Output o : result.outputs) {
                CancelToken.check(cancel);
                ApkSigning.Verification v = ApkSigning.verify(o.file, set.base.info.minSdk);
                if (!v.verified) {
                    throw new CloneException(ErrorCode.VERIFY_FAILED, o.file.getName() + ": " + v.errors);
                }
                for (X509Certificate c : v.signers) {
                    try {
                        if (!certHash.equals(SigningIdentity.sha256Hex(c.getEncoded()))) {
                            throw new CloneException(ErrorCode.VERIFY_FAILED, o.file.getName() + ": sertifikat penanda tangan berbeda");
                        }
                    } catch (java.security.cert.CertificateEncodingException ex) {
                        throw new CloneException(ErrorCode.VERIFY_FAILED, ex.getMessage());
                    }
                }
                String s = (v.v1 ? "v1 " : "") + (v.v2 ? "v2 " : "") + (v.v3 ? "v3" : "");
                schemes = s.trim();
                verifyStructure(o.file, spec, oldAuthorities, o.splitName == null);
                verified.add(new CloneResult.Output(o.file, o.splitName, sha256(o.file), o.file.length()));
            }
            result.outputs.clear();
            result.outputs.addAll(verified);
            result.signatureSchemes = schemes;
            listener.onLog("Selesai: " + result.outputs.size() + " APK terverifikasi, sertifikat SHA-256 " + certHash);
            return result;
        }
    }

    private static String label(ApkSet.Apk a) {
        return a.isBase() ? "base" : "split " + a.info.split;
    }

    private void writeUnsigned(ApkSet.Apk a, File out, Map<String, byte[]> repl, Map<String, byte[]> add,
                               AlignedZipWriter.Progress prog) throws CloneException {
        OutputStream os;
        try {
            os = new BufferedOutputStream(options.outputFactory.open(out), 256 * 1024);
        } catch (IOException e) {
            throw new CloneException(ErrorCode.IO_ERROR, out.getName() + ": " + e.getMessage(), e);
        }
        AlignedZipWriter w = new AlignedZipWriter(os);
        try {
            for (ZipArchive.Entry e : a.zip.entries()) {
                CancelToken.check(cancel);
                if (isSignatureFile(e.name)) {
                    continue;
                }
                byte[] r = repl.get(e.name);
                if (r != null) {
                    boolean compress = e.method == ZipArchive.METHOD_DEFLATED && !"resources.arsc".equals(e.name);
                    w.addBytes(e.name, r, compress, e.dosTime(), e.dosDate());
                    prog.advance(e.compressedSize);
                } else {
                    w.copyRaw(a.zip, e, cancel, prog);
                }
            }
            for (Map.Entry<String, byte[]> en : add.entrySet()) {
                w.addBytes(en.getKey(), en.getValue(), false, 0x0000, 0x0021);
            }
            w.finish();
        } finally {
            try {
                w.close();
            } catch (IOException e) {
                // Kegagalan flush saat penutupan (misalnya ruang habis) ditangani di bawah.
                String m = String.valueOf(e.getMessage());
                if (m.contains("ENOSPC") || m.toLowerCase().contains("no space left")) {
                    //noinspection ThrowFromFinallyBlock
                    throw new CloneException(ErrorCode.STORAGE_FULL, out.getName(), e);
                }
            }
        }
    }

    /** Pemeriksaan struktur hasil: alignment, paket baru, komponen absolut, authority lama tidak tersisa. */
    static void verifyStructure(File apk, CloneSpec spec, Set<String> oldAuthorities, boolean isBase) throws CloneException {
        try (ZipArchive z = ZipArchive.open(apk)) {
            for (ZipArchive.Entry e : z.entries()) {
                if (e.method == ZipArchive.METHOD_STORED) {
                    int align = AlignedZipWriter.alignmentFor(e.name, e.method);
                    if (e.dataOffset() % align != 0) {
                        throw new CloneException(ErrorCode.VERIFY_FAILED,
                                apk.getName() + ": " + e.name + " tidak sejajar " + align + " byte");
                    }
                }
            }
            ZipArchive.Entry arsc = z.get("resources.arsc");
            if (arsc != null && arsc.method != ZipArchive.METHOD_STORED) {
                throw new CloneException(ErrorCode.VERIFY_FAILED, apk.getName() + ": resources.arsc terkompresi");
            }
            ZipArchive.Entry m = z.get("AndroidManifest.xml");
            if (m == null) {
                throw new CloneException(ErrorCode.VERIFY_FAILED, apk.getName() + ": manifest hilang");
            }
            Axml doc = Axml.parse(z.read(m), ErrorCode.VERIFY_FAILED);
            ManifestInfo info = ManifestInfo.read(doc);
            if (!spec.newPackage.equals(info.packageName)) {
                throw new CloneException(ErrorCode.VERIFY_FAILED, apk.getName() + ": package " + info.packageName);
            }
            if (!info.relativeComponents.isEmpty()) {
                throw new CloneException(ErrorCode.VERIFY_FAILED, apk.getName() + ": nama komponen relatif tersisa " + info.relativeComponents);
            }
            for (String auth : info.authorities) {
                if (oldAuthorities.contains(auth)) {
                    throw new CloneException(ErrorCode.VERIFY_FAILED, apk.getName() + ": authority lama tersisa " + auth);
                }
            }
            if (isBase && spec.newLabel != null && info.labelText != null && !spec.newLabel.equals(info.labelText)) {
                throw new CloneException(ErrorCode.VERIFY_FAILED, apk.getName() + ": label tidak sesuai");
            }
        }
    }

    public static String sha256(File f) throws CloneException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new CloneException(ErrorCode.IO_ERROR, f.getName() + ": " + e.getMessage(), e);
        }
    }

    private static long mb(long b) {
        return (b + (1 << 20) - 1) >> 20;
    }
}
