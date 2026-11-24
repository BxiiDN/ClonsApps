package com.clonsapps.engine;

/**
 * Kode kegagalan mesin cloning. Setiap kode membawa penyebab dan tindakan perbaikan yang spesifik
 * (bahasa Indonesia), sehingga UI tidak pernah berhenti pada pesan "unsupported" yang kosong.
 */
public enum ErrorCode {
    CORRUPT_ZIP("APK rusak atau bukan arsip ZIP yang valid.",
            "Unduh ulang APK dari sumber resminya, atau pilih aplikasi yang sudah terpasang."),
    ZIP64_UNSUPPORTED("APK memakai format ZIP64 (lebih dari 65.535 entri atau lebih dari 4 GB).",
            "Format ini belum didukung mesin ClonApps. Laporkan aplikasi ini; tidak ada solusi aman yang bisa dilakukan di perangkat."),
    ENCRYPTED_ENTRY("APK berisi entri ZIP terenkripsi.",
            "Android sendiri tidak dapat memasang APK seperti ini. Gunakan APK asli dari sumber resmi."),
    UNSUPPORTED_COMPRESSION("APK memakai metode kompresi ZIP yang tidak dikenal Android.",
            "Gunakan APK asli dari sumber resmi atau pilih aplikasi yang sudah terpasang."),
    CRC_MISMATCH("Isi entri APK tidak cocok dengan checksum-nya (data rusak).",
            "File kemungkinan terpotong saat diunduh atau disalin. Unduh ulang lalu coba lagi."),
    DUPLICATE_ENTRY("APK berisi nama entri ganda.",
            "Android menolak APK seperti ini. Gunakan APK asli dari sumber resmi."),
    MISSING_MANIFEST("AndroidManifest.xml tidak ditemukan di dalam APK.",
            "Pastikan file yang dipilih adalah APK, bukan arsip lain."),
    BAD_MANIFEST("AndroidManifest.xml tidak dapat dibaca (format XML biner tidak valid).",
            "APK mungkin rusak atau sengaja dikaburkan. Coba ambil dari aplikasi terpasang."),
    UNSUPPORTED_XML("Struktur XML biner ini memakai fitur yang belum didukung mesin ClonApps.",
            "Laporkan aplikasi ini beserta laporan pemeriksaan agar dukungannya bisa ditambahkan."),
    BAD_RESOURCES("resources.arsc tidak dapat dibaca atau diubah.",
            "APK mungkin rusak atau memakai format resource tidak standar. Laporkan aplikasi ini."),
    NO_BASE_APK("Tidak ada base APK di antara file yang dipilih (semuanya split).",
            "Pilih juga base.apk, atau pilih aplikasi dari daftar aplikasi terpasang."),
    MULTIPLE_BASE_APK("Terdapat lebih dari satu base APK.",
            "Pilih hanya satu base APK beserta split miliknya."),
    SPLIT_PACKAGE_MISMATCH("Split APK berasal dari paket yang berbeda dengan base APK.",
            "Pilih base dan split dari aplikasi yang sama."),
    SPLIT_VERSION_MISMATCH("Split APK berasal dari versi berbeda dengan base APK.",
            "Ambil ulang seluruh file dari versi yang sama, atau pilih aplikasi terpasang."),
    DUPLICATE_SPLIT("Ada split APK dengan nama yang sama lebih dari sekali.",
            "Hapus file split yang ganda dari pilihan."),
    MISSING_SPLIT("Split APK yang diwajibkan aplikasi tidak lengkap.",
            "Pilih file .apks/.xapk lengkap atau semua file split, atau pilih aplikasi dari daftar aplikasi terpasang."),
    ABI_INCOMPATIBLE("Library native aplikasi tidak cocok dengan arsitektur CPU perangkat ini.",
            "Gunakan APK untuk arsitektur perangkat (misalnya arm64-v8a)."),
    MIN_SDK_TOO_HIGH("Aplikasi membutuhkan versi Android yang lebih baru dari perangkat ini.",
            "Gunakan versi aplikasi yang mendukung Android di perangkat ini."),
    TARGET_SDK_TOO_LOW("Android 14+ menolak memasang aplikasi dengan targetSdk di bawah 23.",
            "Gunakan versi aplikasi yang lebih baru. ClonApps tidak mengubah targetSdk karena mengubah perilaku aplikasi."),
    INVALID_PACKAGE_NAME("Format package name tidak valid.",
            "Gunakan minimal dua segmen dipisah titik, tiap segmen diawali huruf dan hanya berisi huruf, angka, atau garis bawah (contoh: com.contoh.catatan.clone2)."),
    PACKAGE_SAME_AS_ORIGINAL("Package name clone sama dengan aplikasi asli.",
            "Clone harus memakai package name berbeda agar tidak menimpa aplikasi asli."),
    PACKAGE_IN_USE("Package name sudah dipakai aplikasi lain di perangkat ini.",
            "Pilih package name lain. ClonApps tidak menimpa aplikasi yang bukan clone buatannya."),
    PACKAGE_TOO_LONG("Package name terlalu panjang (maksimal 127 karakter).",
            "Perpendek package name."),
    INVALID_LABEL("Nama aplikasi kosong atau terlalu panjang.",
            "Isi nama aplikasi 1–100 karakter."),
    INVALID_FILE_NAME("Nama file hasil tidak valid.",
            "Gunakan huruf, angka, spasi, titik, tanda hubung, atau garis bawah."),
    ICON_INVALID("Gambar ikon tidak dapat dipakai.",
            "Pilih gambar PNG/JPEG/WebP yang valid."),
    STORAGE_FULL("Ruang penyimpanan perangkat tidak cukup.",
            "Kosongkan ruang penyimpanan (hapus file hasil lama di Riwayat) lalu coba lagi."),
    SIGNING_FAILED("Penandatanganan APK gagal.",
            "Coba lagi. Jika berulang, ekspor laporan dan laporkan aplikasi ini."),
    VERIFY_FAILED("Verifikasi hasil gagal; APK tidak dianggap berhasil dibuat.",
            "Lihat detail verifikasi. Hasil yang gagal verifikasi sudah dihapus."),
    CANCELLED("Proses dibatalkan.", "Tambahkan lagi ke antrean bila ingin melanjutkan."),
    IO_ERROR("Gagal membaca atau menulis file.",
            "Periksa izin akses file dan ruang penyimpanan, lalu coba lagi."),
    INTERNAL("Kesalahan internal mesin cloning.",
            "Ekspor laporan dan laporkan aplikasi ini.");

    public final String message;
    public final String action;

    ErrorCode(String message, String action) {
        this.message = message;
        this.action = action;
    }
}
