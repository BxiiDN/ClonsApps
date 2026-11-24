package com.clonsapps.engine;

import com.clonsapps.engine.res.Arsc;
import com.clonsapps.engine.zip.ZipArchive;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pemeriksaan sebelum cloning. Setiap masalah disertai penyebab spesifik dan tindakan; masalah
 * yang membuat Android menolak hasil dilaporkan sebagai ERROR sebelum apa pun ditulis.
 */
public final class Inspector {
    private Inspector() {
    }

    private static final List<String> RISK_PREFIXES = Arrays.asList(
            "Lcom/pairip/",
            "Lcom/google/android/vending/licensing/",
            "Lcom/google/android/play/core/integrity/",
            "Lcom/google/android/play/integrity/",
            "Lcom/google/android/gms/safetynet/",
            "Lcom/google/firebase/",
            "Lcom/google/android/gms/auth/",
            "Lcom/facebook/");

    public static Inspection inspect(List<File> files, DeviceProfile device, CloneSpec spec, CancelToken cancel)
            throws CloneException {
        try (ApkSet set = ApkSet.open(files, cancel)) {
            return inspect(set, device, spec, cancel);
        }
    }

    public static Inspection inspect(ApkSet set, DeviceProfile device, CloneSpec spec, CancelToken cancel)
            throws CloneException {
        Inspection r = new Inspection();
        ManifestInfo b = set.base.info;
        r.base = b;
        r.totalSize = set.totalSize();
        for (ApkSet.Apk s : set.splits) {
            r.splits.add(s.info);
        }

        // Nama aplikasi dari resources.arsc.
        r.label = b.labelText;
        Arsc baseArsc = readArsc(set.base);
        if (r.label == null && b.labelRes != 0 && baseArsc != null) {
            try {
                r.label = baseArsc.resolveString(b.labelRes);
            } catch (CloneException ignored) {
                r.label = null;
            }
        }

        // --- Split wajib / konsistensi split
        Set<String> splitNames = new HashSet<>(r.splitNames());
        boolean vendingRequired = b.metaDataNames.contains("com.android.vending.splits.required");
        if ((b.splitRequired || vendingRequired) && set.splits.isEmpty()) {
            r.issues.add(Issue.error(ErrorCode.MISSING_SPLIT,
                    "base APK menyatakan split wajib (" + (b.splitRequired ? "isSplitRequired" : "com.android.vending.splits.required")
                            + "), tetapi tidak ada split yang dipilih"));
        }
        for (ApkSet.Apk s : set.splits) {
            String cfs = s.info.configForSplit;
            if (cfs != null && !cfs.isEmpty() && !splitNames.contains(cfs)) {
                r.issues.add(Issue.error(ErrorCode.MISSING_SPLIT,
                        "split " + s.info.split + " adalah konfigurasi untuk fitur \"" + cfs + "\" yang tidak ada"));
            }
        }

        // --- ABI
        Set<String> abiWithLibs = new LinkedHashSet<>();
        Set<String> abiSplits = new LinkedHashSet<>();
        for (ApkSet.Apk a : set.all()) {
            abiWithLibs.addAll(a.nativeAbis());
            String splitAbi = DeviceProfile.abiFromSplit(a.info.split);
            if (splitAbi != null) {
                abiSplits.add(splitAbi);
            }
            r.dexCount += a.dexCount();
        }
        r.nativeAbis.addAll(abiWithLibs);
        if (!abiWithLibs.isEmpty()) {
            boolean match = false;
            for (String abi : device.supportedAbis) {
                if (abiWithLibs.contains(abi)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                r.issues.add(Issue.error(ErrorCode.ABI_INCOMPATIBLE,
                        "APK: " + abiWithLibs + "; perangkat: " + device.supportedAbis));
            }
            for (String abi : abiSplits) {
                if (!device.supportedAbis.contains(abi)) {
                    r.issues.add(Issue.warn("UNUSED_ABI_SPLIT", "Split ABI tidak dipakai perangkat ini",
                            "split untuk " + abi + " ikut diproses dan dipasang, tetapi tidak dipakai CPU perangkat",
                            "Tidak perlu tindakan; ukuran clone sedikit lebih besar."));
                }
            }
            r.issues.add(Issue.info("NATIVE_LIBS", "Library native", "ABI: " + abiWithLibs
                    + (Boolean.FALSE.equals(b.extractNativeLibs) ? "; tidak diekstrak (disejajarkan 16 KiB)" : "")));
        } else if (!abiSplits.isEmpty()) {
            r.issues.add(Issue.info("NATIVE_LIBS", "Split ABI tanpa library", abiSplits.toString()));
        }

        // --- SDK
        if (b.minSdk > device.sdkInt) {
            r.issues.add(Issue.error(ErrorCode.MIN_SDK_TOO_HIGH,
                    "minSdk " + b.minSdk + ", perangkat API " + device.sdkInt));
        }
        if (device.sdkInt >= 34 && b.targetSdk > 0 && b.targetSdk < 23) {
            r.issues.add(Issue.error(ErrorCode.TARGET_SDK_TOO_LOW, "targetSdk " + b.targetSdk));
        }
        if (b.testOnly) {
            r.issues.add(Issue.warn("TEST_ONLY", "APK bertanda testOnly",
                    "Android menolak memasang APK testOnly melalui installer biasa (INSTALL_FAILED_TEST_ONLY)",
                    "Gunakan build rilis aplikasi tersebut. ClonApps tidak menghapus penanda ini."));
        }
        if (b.sharedUserId != null) {
            r.issues.add(Issue.warn("SHARED_USER_ID", "Aplikasi memakai sharedUserId",
                    b.sharedUserId + " akan dipetakan ke identitas baru; integrasi dengan aplikasi lain milik pengembang yang sama tidak berlaku pada clone",
                    "Uji fitur yang bergantung pada aplikasi pendamping."));
        }

        // --- Rencana perubahan identitas
        if (spec != null && spec.newPackage != null) {
            ErrorCode pc = PackageNames.check(spec.newPackage);
            if (pc != null) {
                r.issues.add(Issue.error(pc, "\"" + spec.newPackage + "\""));
            } else if (spec.newPackage.equals(b.packageName)) {
                r.issues.add(Issue.error(ErrorCode.PACKAGE_SAME_AS_ORIGINAL, spec.newPackage));
            } else {
                IdentityMapper m = new IdentityMapper(b.packageName, spec.newPackage);
                for (ApkSet.Apk a : set.all()) {
                    for (String auth : a.info.authorities) {
                        r.authorityMap.put(auth, m.mapOwned(auth));
                    }
                    for (String p : a.info.declaredPermissions) {
                        r.permissionMap.put(p, m.mapOwned(p));
                    }
                }
            }
            if (spec.newLabel != null && !PackageNames.validLabel(spec.newLabel)) {
                r.issues.add(Issue.error(ErrorCode.INVALID_LABEL, null));
            }
            if (spec.outputName != null && !PackageNames.validFileName(spec.outputName)) {
                r.issues.add(Issue.error(ErrorCode.INVALID_FILE_NAME, "\"" + spec.outputName + "\""));
            }
        }

        // --- Ikon
        Set<Integer> icons = new LinkedHashSet<>();
        if (b.iconRes != 0) {
            icons.add(b.iconRes);
        }
        if (b.roundIconRes != 0) {
            icons.add(b.roundIconRes);
        }
        icons.addAll(b.launcherIconRes);
        for (int id : icons) {
            if ((id >>> 24) == 0x7f) {
                r.iconResIds.add(id);
            }
        }
        if (spec != null && spec.iconPng != null && r.iconResIds.isEmpty()) {
            r.issues.add(Issue.warn("ICON_NOT_APPLIED", "Ikon kustom tidak dapat diterapkan",
                    icons.isEmpty() ? "aplikasi tidak mendeklarasikan ikon sendiri" : "aplikasi memakai ikon bawaan sistem",
                    "Clone tetap dibuat dengan ikon asli."));
        }

        // --- Pemindaian DEX (baca saja)
        Set<String> exact = new HashSet<>();
        for (ApkSet.Apk a : set.all()) {
            exact.addAll(a.info.authorities);
            exact.addAll(a.info.declaredPermissions);
        }
        DexScanner.Result dex = new DexScanner.Result();
        for (ApkSet.Apk a : set.all()) {
            for (ZipArchive.Entry e : a.zip.entries()) {
                if (!e.name.matches("classes\\d*\\.dex")) {
                    continue;
                }
                CancelToken.check(cancel);
                DexScanner.scan(a.zip.read(e), exact, RISK_PREFIXES, dex);
            }
        }
        List<String> hardAuth = new ArrayList<>();
        List<String> hardPerm = new ArrayList<>();
        for (String s : dex.exactMatches) {
            boolean isAuthority = false;
            for (ApkSet.Apk a : set.all()) {
                if (a.info.authorities.contains(s)) {
                    isAuthority = true;
                    break;
                }
            }
            (isAuthority ? hardAuth : hardPerm).add(s);
        }
        if (!hardAuth.isEmpty()) {
            r.issues.add(Issue.warn("HARDCODED_AUTHORITY", "Authority lama tertulis langsung di kode",
                    hardAuth.toString() + " — kode yang memakai string ini tidak ikut berubah (hanya manifest dan resource yang diubah)",
                    "Uji fitur berbagi file, sinkronisasi, atau widget pada clone. Fitur tersebut bisa gagal."));
        }
        if (!hardPerm.isEmpty()) {
            r.issues.add(Issue.warn("HARDCODED_PERMISSION", "Nama permission milik aplikasi tertulis di kode",
                    hardPerm.toString(), "Uji fitur yang memakai permission tersebut pada clone."));
        }
        if (dex.prefixMatches.contains("Lcom/pairip/") || dex.prefixMatches.contains("Lcom/google/android/vending/licensing/")) {
            r.issues.add(Issue.warn("LICENSE_CHECK", "Aplikasi memeriksa lisensi/sumber instalasi",
                    "terdeteksi " + (dex.prefixMatches.contains("Lcom/pairip/") ? "PairIP (Play Integrity otomatis)" : "Google Play Licensing"),
                    "Clone kemungkinan besar ditolak oleh aplikasi itu sendiri. ClonApps tidak menonaktifkan proteksi ini."));
        }
        if (dex.prefixMatches.contains("Lcom/google/android/play/core/integrity/")
                || dex.prefixMatches.contains("Lcom/google/android/play/integrity/")
                || dex.prefixMatches.contains("Lcom/google/android/gms/safetynet/")) {
            r.issues.add(Issue.warn("INTEGRITY_API", "Aplikasi memakai Play Integrity/SafetyNet",
                    "verifikasi integritas akan melihat paket dan sertifikat yang berbeda",
                    "Fitur yang memerlukan verifikasi integritas kemungkinan gagal. ClonApps tidak memalsukan integritas perangkat."));
        }
        boolean gms = b.metaDataNames.contains("com.google.android.gms.version")
                || dex.prefixMatches.contains("Lcom/google/firebase/")
                || dex.prefixMatches.contains("Lcom/google/android/gms/auth/");
        if (gms) {
            r.issues.add(Issue.warn("GOOGLE_SERVICES", "Memakai layanan Google/Firebase",
                    "kunci API, Login Google, dan notifikasi FCM biasanya terikat pada package name + sertifikat asli",
                    "Login Google atau notifikasi push di clone mungkin tidak berfungsi."));
        }
        if (dex.prefixMatches.contains("Lcom/facebook/") || b.metaDataNames.contains("com.facebook.sdk.ApplicationId")) {
            r.issues.add(Issue.warn("SIGNATURE_BOUND_SDK", "SDK pihak ketiga terikat tanda tangan",
                    "SDK Facebook mencocokkan key hash sertifikat",
                    "Login lewat SDK tersebut di clone mungkin gagal."));
        }

        r.issues.add(Issue.info("SIGNATURE", "Tanda tangan baru",
                "Clone ditandatangani kunci ClonApps milik perangkat ini, bukan kunci pengembang asli."));
        r.issues.add(Issue.info("DATA", "Data mulai kosong",
                "Akun, login, dan data privat aplikasi asli tidak disalin ke clone."));
        return r;
    }

    static Arsc readArsc(ApkSet.Apk a) throws CloneException {
        ZipArchive.Entry e = a.zip.get("resources.arsc");
        if (e == null) {
            return null;
        }
        return new Arsc(a.zip.read(e));
    }
}
