package com.clonsapps.engine;

import com.clonsapps.engine.res.Axml;
import com.clonsapps.engine.zip.ZipArchive;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Base APK + seluruh split-nya, dibuka dan divalidasi konsistensinya. */
public final class ApkSet implements Closeable {
    public static final class Apk {
        public final File file;
        public final ZipArchive zip;
        public final ManifestInfo info;
        final byte[] manifestBytes;

        Apk(File file, ZipArchive zip, ManifestInfo info, byte[] manifestBytes) {
            this.file = file;
            this.zip = zip;
            this.info = info;
            this.manifestBytes = manifestBytes;
        }

        public boolean isBase() {
            return info.isBase();
        }

        public String splitName() {
            return info.split;
        }

        /** Nama ABI dari folder lib/ di APK ini. */
        public Set<String> nativeAbis() {
            Set<String> abis = new HashSet<>();
            for (ZipArchive.Entry e : zip.entries()) {
                if (e.name.startsWith("lib/") && e.name.endsWith(".so")) {
                    int slash = e.name.indexOf('/', 4);
                    if (slash > 4) {
                        abis.add(e.name.substring(4, slash));
                    }
                }
            }
            return abis;
        }

        public int dexCount() {
            int n = 0;
            for (ZipArchive.Entry e : zip.entries()) {
                if (e.name.matches("classes\\d*\\.dex")) {
                    n++;
                }
            }
            return n;
        }

        Axml parseManifest() throws CloneException {
            return Axml.parse(manifestBytes, ErrorCode.BAD_MANIFEST);
        }
    }

    public final Apk base;
    public final List<Apk> splits;

    private ApkSet(Apk base, List<Apk> splits) {
        this.base = base;
        this.splits = splits;
    }

    public List<Apk> all() {
        List<Apk> l = new ArrayList<>();
        l.add(base);
        l.addAll(splits);
        return l;
    }

    public long totalSize() {
        long t = 0;
        for (Apk a : all()) {
            t += a.file.length();
        }
        return t;
    }

    public static Apk openApk(File f) throws CloneException {
        ZipArchive zip = ZipArchive.open(f);
        try {
            ZipArchive.Entry m = zip.get("AndroidManifest.xml");
            if (m == null) {
                throw new CloneException(ErrorCode.MISSING_MANIFEST, f.getName());
            }
            byte[] bytes = zip.read(m);
            ManifestInfo info = ManifestInfo.read(Axml.parse(bytes, ErrorCode.BAD_MANIFEST));
            return new Apk(f, zip, info, bytes);
        } catch (CloneException e) {
            zip.close();
            throw e;
        } catch (RuntimeException e) {
            zip.close();
            throw new CloneException(ErrorCode.BAD_MANIFEST, f.getName() + ": " + e, e);
        }
    }

    /** Membuka semua file dan memvalidasi: tepat satu base, paket & versionCode sama, nama split unik. */
    public static ApkSet open(List<File> files, CancelToken cancel) throws CloneException {
        List<Apk> opened = new ArrayList<>();
        try {
            for (File f : files) {
                CancelToken.check(cancel);
                opened.add(openApk(f));
            }
            Apk base = null;
            List<Apk> splits = new ArrayList<>();
            for (Apk a : opened) {
                if (a.isBase()) {
                    if (base != null) {
                        throw new CloneException(ErrorCode.MULTIPLE_BASE_APK, base.file.getName() + ", " + a.file.getName());
                    }
                    base = a;
                } else {
                    splits.add(a);
                }
            }
            if (base == null) {
                throw new CloneException(ErrorCode.NO_BASE_APK, opened.size() + " file split");
            }
            Set<String> names = new HashSet<>();
            for (Apk s : splits) {
                if (!base.info.packageName.equals(s.info.packageName)) {
                    throw new CloneException(ErrorCode.SPLIT_PACKAGE_MISMATCH,
                            s.file.getName() + ": " + s.info.packageName + " ≠ " + base.info.packageName);
                }
                if (s.info.versionCode != base.info.versionCode) {
                    throw new CloneException(ErrorCode.SPLIT_VERSION_MISMATCH,
                            s.file.getName() + ": versionCode " + s.info.versionCode + " ≠ " + base.info.versionCode);
                }
                if (!names.add(s.info.split)) {
                    throw new CloneException(ErrorCode.DUPLICATE_SPLIT, s.info.split);
                }
            }
            Collections.sort(splits, (x, y) -> x.info.split.compareTo(y.info.split));
            return new ApkSet(base, splits);
        } catch (CloneException | RuntimeException e) {
            for (Apk a : opened) {
                a.zip.close();
            }
            throw e;
        }
    }

    @Override
    public void close() {
        for (Apk a : all()) {
            a.zip.close();
        }
    }
}
