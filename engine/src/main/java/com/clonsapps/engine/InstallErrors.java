package com.clonsapps.engine;

/**
 * Memetakan pesan status PackageInstaller Android ke penyebab dan tindakan spesifik.
 * Logika murni (tanpa API Android) agar bisa diuji di JVM.
 */
public final class InstallErrors {
    private InstallErrors() {
    }

    public static final class Explained {
        public final String code;
        public final String cause;
        public final String action;

        Explained(String code, String cause, String action) {
            this.code = code;
            this.cause = cause;
            this.action = action;
        }
    }

    // Konstanta PackageInstaller.STATUS_*
    public static final int STATUS_PENDING_USER_ACTION = -1;
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_FAILURE = 1;
    public static final int STATUS_FAILURE_BLOCKED = 2;
    public static final int STATUS_FAILURE_ABORTED = 3;
    public static final int STATUS_FAILURE_INVALID = 4;
    public static final int STATUS_FAILURE_CONFLICT = 5;
    public static final int STATUS_FAILURE_STORAGE = 6;
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;
    public static final int STATUS_FAILURE_TIMEOUT = 8;

    public static Explained explain(int status, String message) {
        String m = message == null ? "" : message;
        if (m.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || m.contains("signatures do not match")) {
            return new Explained("SIGNATURE_MISMATCH",
                    "Paket ini sudah terpasang dengan tanda tangan lain, jadi Android menolak pembaruan.",
                    "Clone lama dibuat dengan kunci berbeda (misalnya sebelum ClonApps dipasang ulang). Pulihkan cadangan kunci di Pengaturan, atau copot clone lama (datanya ikut terhapus) lalu pasang lagi.");
        }
        if (m.contains("INSTALL_FAILED_CONFLICTING_PROVIDER")) {
            return new Explained("CONFLICTING_PROVIDER",
                    "Authority content provider bentrok dengan aplikasi lain yang terpasang.",
                    "Gunakan package name clone lain; authority dibentuk dari package name.");
        }
        if (m.contains("INSTALL_FAILED_DUPLICATE_PERMISSION")) {
            return new Explained("DUPLICATE_PERMISSION",
                    "Permission yang dideklarasikan bentrok dengan aplikasi lain.",
                    "Gunakan package name clone lain. Laporkan aplikasi ini bila tetap terjadi.");
        }
        if (m.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE")) {
            return new Explained("SHARED_USER", "sharedUserId bentrok dengan aplikasi lain.",
                    "Gunakan package name clone lain.");
        }
        if (m.contains("INSTALL_FAILED_NO_MATCHING_ABIS") || m.contains("INSTALL_FAILED_CPU_ABI_INCOMPATIBLE")) {
            return new Explained("NO_MATCHING_ABIS", "Library native tidak cocok dengan CPU perangkat.",
                    "Gunakan APK/split untuk ABI perangkat.");
        }
        if (m.contains("INSTALL_FAILED_MISSING_SPLIT")) {
            return new Explained("MISSING_SPLIT", "Android menyatakan ada split yang hilang.",
                    "Clone dari aplikasi terpasang atau dari bundel lengkap (.apks/.xapk).");
        }
        if (m.contains("INSTALL_FAILED_DEPRECATED_SDK_VERSION")) {
            return new Explained("DEPRECATED_SDK", "targetSdk aplikasi terlalu rendah untuk Android ini.",
                    "Gunakan versi aplikasi yang lebih baru.");
        }
        if (m.contains("INSTALL_FAILED_TEST_ONLY")) {
            return new Explained("TEST_ONLY", "APK bertanda testOnly.", "Gunakan build rilis aplikasi tersebut.");
        }
        if (m.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return new Explained("VERSION_DOWNGRADE", "Clone yang terpasang lebih baru daripada yang akan dipasang.",
                    "Pasang clone dari versi aplikasi yang sama atau lebih baru.");
        }
        if (m.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") || status == STATUS_FAILURE_STORAGE) {
            return new Explained("STORAGE", "Ruang penyimpanan tidak cukup untuk memasang.",
                    "Kosongkan ruang penyimpanan lalu coba lagi.");
        }
        if (m.contains("INSTALL_PARSE_FAILED_NO_CERTIFICATES") || m.contains("INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES")) {
            return new Explained("CERTIFICATES", "Android tidak menerima tanda tangan APK hasil.",
                    "Buat ulang clone. Laporkan bila berulang.");
        }
        if (m.contains("INSTALL_PARSE_FAILED") || m.contains("INSTALL_FAILED_INVALID_APK") || status == STATUS_FAILURE_INVALID) {
            return new Explained("INVALID_APK", "Android menolak struktur APK hasil: " + m,
                    "Laporkan aplikasi ini beserta pesan di atas.");
        }
        if (m.contains("INSTALL_FAILED_USER_RESTRICTED") || status == STATUS_FAILURE_BLOCKED) {
            return new Explained("BLOCKED", "Pemasangan diblokir oleh kebijakan perangkat atau pengguna.",
                    "Izinkan ClonApps memasang aplikasi (Pengaturan → Instal aplikasi tak dikenal) dan periksa pembatasan perangkat.");
        }
        if (status == STATUS_FAILURE_ABORTED) {
            return new Explained("ABORTED", "Pemasangan dibatalkan di layar konfirmasi Android.",
                    "Ketuk Pasang lagi bila ingin melanjutkan.");
        }
        if (status == STATUS_FAILURE_CONFLICT) {
            return new Explained("CONFLICT", "Bentrok dengan paket yang sudah ada: " + m,
                    "Gunakan package name clone lain.");
        }
        if (status == STATUS_FAILURE_INCOMPATIBLE) {
            return new Explained("INCOMPATIBLE", "Aplikasi tidak kompatibel dengan perangkat: " + m,
                    "Periksa ABI dan versi Android pada tahap Periksa.");
        }
        if (status == STATUS_FAILURE_TIMEOUT) {
            return new Explained("TIMEOUT", "Pemasangan melewati batas waktu.", "Coba lagi.");
        }
        return new Explained("FAILURE", "Pemasangan gagal: " + (m.isEmpty() ? "status " + status : m),
                "Coba lagi; bila berulang, ekspor laporan dari Riwayat.");
    }
}
