package com.clonsapps.engine;

import com.clonsapps.engine.zip.ZipArchive;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Membuka file yang dipilih dari penyimpanan: APK tunggal, atau bundel split (.apks dari bundletool/SAI,
 * .xapk) yang berisi beberapa APK. Bundel TIDAK digabung menjadi satu APK; setiap APK diekstrak utuh dan
 * diproses sebagai base/split. Split ABI yang tidak didukung perangkat tidak diikutkan.
 */
public final class BundleExtractor {
    private BundleExtractor() {
    }

    public static final class Extracted {
        public final List<File> apks = new ArrayList<>();
        public final List<Issue> notes = new ArrayList<>();
    }

    public static boolean isApk(ZipArchive z) {
        return z.has("AndroidManifest.xml");
    }

    public static Extracted extract(File container, File outDir, DeviceProfile device, CancelToken cancel) throws CloneException {
        Extracted ex = new Extracted();
        try (ZipArchive z = ZipArchive.open(container)) {
            if (isApk(z)) {
                ex.apks.add(container);
                return ex;
            }
            List<ZipArchive.Entry> nested = new ArrayList<>();
            boolean hasSplitsDir = false;
            int obb = 0;
            for (ZipArchive.Entry e : z.entries()) {
                String n = e.name;
                if (n.endsWith(".obb")) {
                    obb++;
                }
                if (!n.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    continue;
                }
                if (n.startsWith("splits/")) {
                    hasSplitsDir = true;
                }
                nested.add(e);
            }
            if (nested.isEmpty()) {
                throw new CloneException(ErrorCode.MISSING_MANIFEST,
                        container.getName() + ": bukan APK dan tidak berisi APK (bundel terenkripsi seperti .apkm tidak didukung)");
            }
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                throw new CloneException(ErrorCode.IO_ERROR, "tidak bisa membuat " + outDir);
            }
            int idx = 0;
            java.util.Map<Integer, List<File>> variants = new java.util.TreeMap<>();
            java.util.Map<Integer, Integer> variantMinSdk = new java.util.HashMap<>();
            for (ZipArchive.Entry e : nested) {
                CancelToken.check(cancel);
                // Format bundletool: pakai splits/, abaikan standalones/ dan universal.apk.
                if (hasSplitsDir && !e.name.startsWith("splits/")) {
                    continue;
                }
                File f = new File(outDir, String.format(java.util.Locale.ROOT, "in_%03d.apk", idx++));
                copyEntry(z, e, f, cancel);
                ApkSet.Apk apk;
                try {
                    apk = ApkSet.openApk(f);
                } catch (CloneException ce) {
                    throw new CloneException(ce.code(), e.name + ": " + ce.detail(), ce);
                }
                String abi = DeviceProfile.abiFromSplit(apk.info.split);
                int variant = hasSplitsDir ? bundletoolVariant(e.name, apk.info) : 1;
                if (apk.isBase()) {
                    variantMinSdk.put(variant, apk.info.minSdk);
                }
                apk.zip.close();
                if (abi != null && !device.supportedAbis.contains(abi)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    ex.notes.add(Issue.info("ABI_SPLIT_SKIPPED", "Split ABI dilewati", e.name + " (" + abi + ")"));
                    continue;
                }
                List<File> group = variants.get(variant);
                if (group == null) {
                    group = new ArrayList<>();
                    variants.put(variant, group);
                }
                group.add(f);
            }
            // bundletool dapat menyimpan beberapa varian (target SDK berbeda) dalam satu .apks.
            // Pilih varian dengan minSdk tertinggi yang masih didukung perangkat; varian lain tidak dipakai.
            int chosen = -1;
            int bestSdk = -1;
            for (java.util.Map.Entry<Integer, Integer> v : variantMinSdk.entrySet()) {
                if (v.getValue() <= device.sdkInt && v.getValue() >= bestSdk) {
                    bestSdk = v.getValue();
                    chosen = v.getKey();
                }
            }
            if (variants.size() > 1) {
                if (chosen < 0) {
                    throw new CloneException(ErrorCode.MIN_SDK_TOO_HIGH,
                            "tidak ada varian di bundel yang mendukung API " + device.sdkInt);
                }
                ex.notes.add(Issue.info("BUNDLE_VARIANT", "Varian bundel dipilih",
                        variants.size() + " varian; dipakai varian " + chosen + " (minSdk " + bestSdk + ")"));
                for (java.util.Map.Entry<Integer, List<File>> v : variants.entrySet()) {
                    if (v.getKey() != chosen) {
                        for (File f : v.getValue()) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
                ex.apks.addAll(variants.get(chosen));
            } else {
                for (List<File> g : variants.values()) {
                    ex.apks.addAll(g);
                }
            }
            if (obb > 0) {
                ex.notes.add(Issue.warn("OBB_IGNORED", "File OBB tidak ikut di-clone",
                        obb + " file OBB di dalam bundel. Lokasi OBB terikat package name, sehingga clone tidak memakainya.",
                        "Aplikasi yang membutuhkan OBB mungkin meminta unduhan ulang data pada clone."));
            }
            return ex;
        }
    }

    /**
     * Nomor varian bundletool dari nama file: &lt;modul&gt;-&lt;akhiran&gt;[_N].apk, dengan akhiran "master"
     * atau nama konfigurasi split (misalnya x86_64, xxhdpi). Nama dibandingkan dengan nama yang diharapkan dari
     * manifest agar akhiran ABI seperti "x86_64" tidak tertukar dengan nomor varian.
     */
    static int bundletoolVariant(String entryName, ManifestInfo info) {
        String file = entryName.substring(entryName.lastIndexOf('/') + 1);
        if (!file.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
            return 1;
        }
        String stem = file.substring(0, file.length() - 4);
        String split = info.split;
        String module;
        String suffix;
        if (split == null || split.isEmpty()) {
            module = "base";
            suffix = "master";
        } else if (split.startsWith("config.")) {
            module = info.configForSplit == null || info.configForSplit.isEmpty() ? "base" : info.configForSplit;
            suffix = split.substring("config.".length());
        } else {
            module = split;
            suffix = "master";
        }
        String expected = module + "-" + suffix;
        if (stem.equals(expected)) {
            return 1;
        }
        if (stem.startsWith(expected + "_")) {
            String n = stem.substring(expected.length() + 1);
            if (n.matches("\\d{1,3}")) {
                return Integer.parseInt(n);
            }
        }
        return 1;
    }

    private static void copyEntry(ZipArchive z, ZipArchive.Entry e, File out, CancelToken cancel) throws CloneException {
        if (e.method == ZipArchive.METHOD_STORED) {
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[256 * 1024];
                long done = 0;
                while (done < e.compressedSize) {
                    CancelToken.check(cancel);
                    int n = z.readRaw(e, done, buf, 0, buf.length);
                    if (n <= 0) {
                        break;
                    }
                    os.write(buf, 0, n);
                    done += n;
                }
            } catch (IOException ex) {
                throw ioError(out, ex);
            }
        } else {
            byte[] data = z.read(e);
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(data);
            } catch (IOException ex) {
                throw ioError(out, ex);
            }
        }
    }

    private static CloneException ioError(File f, IOException ex) {
        String m = String.valueOf(ex.getMessage());
        if (m.contains("ENOSPC") || m.toLowerCase().contains("no space left")) {
            return new CloneException(ErrorCode.STORAGE_FULL, f.getName(), ex);
        }
        return new CloneException(ErrorCode.IO_ERROR, f.getName() + ": " + m, ex);
    }
}
