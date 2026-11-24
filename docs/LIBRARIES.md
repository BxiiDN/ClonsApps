# Audit pustaka

Prinsip: fungsi utama berjalan di perangkat. Tidak ada executable desktop (aapt/aapt2, apktool, zipalign, apksigner CLI)
yang dikemas — biner itu dibuat untuk Linux/macOS/Windows desktop dan tidak bisa dijalankan sebagai bagian aplikasi Android.

| Pustaka | Lisensi | Dipakai untuk | Berjalan di Android? |
|---|---|---|---|
| `com.android.tools.build:apksig` (versi = AGP) | Apache-2.0 | Tanda tangan APK v1/v2/v3 dan verifikasi | Ya. Java murni, hanya memakai JCA (`java.security`), tanpa dependensi lain; pustaka yang sama dipakai `apksigner` resmi. |
| AndroidX Core, Activity Compose, Lifecycle | Apache-2.0 | Aplikasi | Ya |
| Jetpack Compose (BOM), Material 3, Material Icons Core | Apache-2.0 | UI | Ya |
| kotlinx-coroutines | Apache-2.0 | Antrean/IO | Ya |
| JUnit 4, AndroidX Test, org.json (hanya uji JVM) | EPL-1.0 / Apache-2.0 / JSON License | Pengujian | Tidak dikemas di APK |

Ditulis sendiri di modul `engine` (Java, tanpa dependensi): pembaca/penulis ZIP dengan alignment, pengurai dan penulis
XML biner Android, operasi `resources.arsc`, pemindai tabel string DEX, pembuat sertifikat X.509 (encoder DER minimal).

Dipertimbangkan dan **tidak** dipakai:

| Alternatif | Alasan |
|---|---|
| apktool | Bergantung pada biner aapt/aapt2 desktop; decode/encode penuh resource tidak diperlukan dan berisiko merusak resource. |
| ARSCLib / pustaka manipulasi resource lain | Hanya butuh operasi terarah; kode sendiri yang kecil lebih mudah diaudit dan diuji. |
| BouncyCastle | Sertifikat self-signed cukup dibuat dengan encoder DER ±100 baris; PKCS#12 sudah tersedia di Android. |
| `zipalign` | Biner desktop; alignment dilakukan oleh `AlignedZipWriter`. |
