package com.clonsapps.engine;

/** Tahap proses yang benar-benar dijalankan mesin (ditampilkan apa adanya di UI). */
public enum Stage {
    READ("Membaca APK"),
    CHECK("Memeriksa split, ABI, dan kompatibilitas"),
    PATCH_MANIFEST("Mengubah manifest"),
    PATCH_RESOURCES("Mengubah resource"),
    PACKAGE_ALIGN("Mengemas ulang dan alignment"),
    SIGN("Menandatangani"),
    VERIFY("Memverifikasi hasil");

    public final String label;

    Stage(String label) {
        this.label = label;
    }
}
