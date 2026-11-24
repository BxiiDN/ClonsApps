# Arsitektur

```
app (Kotlin, Compose)                      engine (Java murni, berjalan di Android & JVM)
├─ ui/        Pilih Sumber → Atur → Periksa  ├─ zip/   ZipArchive (pembaca ketat), AlignedZipWriter
├─ work/      CloneService (antrean, FGS)    ├─ res/   Axml (XML biner), Arsc (resources.arsc), StringPool
│             CloneProcessor → engine        ├─ sign/  SigningIdentity, CertificateBuilder, ApkSigning (apksig)
│             Installer (PackageInstaller)   ├─ ManifestPatcher, IdentityMapper, DexScanner
│             RunVerifier, Exporter (SAF)    ├─ Inspector (Periksa), BundleExtractor (.apks/.xapk)
├─ platform/  SigningKeyStore, Conflicts     └─ CloneEngine (pipeline), InstallErrors
└─ data/      JobRepository (JSON atomik)
```

## Pipeline `CloneEngine.run`

| Tahap | Yang dilakukan |
|---|---|
| Membaca APK | Membuka base + semua split. ZIP divalidasi (EOCD, central directory, local header, ZIP64, enkripsi, metode, nama ganda). Manifest tiap APK dibaca. Harus ada tepat satu base; paket dan versionCode split harus sama dengan base; nama split unik. |
| Memeriksa | `Inspector`: split wajib (`isSplitRequired`, `com.android.vending.splits.required`, `configForSplit`), ABI library native vs `Build.SUPPORTED_ABIS`, minSdk/targetSdk (Android 14+ menolak targetSdk < 23), testOnly, sharedUserId, rencana pemetaan authority/permission, pemindaian DEX untuk authority/permission lama yang tertulis di kode serta pustaka lisensi/integritas/Google. Masalah ERROR menghentikan proses sebelum ada file yang ditulis. |
| Mengubah manifest | Lihat "Perubahan identitas" di bawah. XML biner dikodekan ulang dengan string pool baru (nama atribut ber-ID resource di depan, sesuai resource map), lalu diurai ulang sebagai pemeriksaan. |
| Mengubah resource | `resources.arsc`: nama paket pada chunk package/library → paket baru (seperti `--rename-resources-package` aapt2, agar `getIdentifier(..., getPackageName())` tetap bekerja). Ikon kustom: string baru ditambahkan di akhir global pool (indeks lama tidak bergeser) lalu semua entri resource ikon (semua konfigurasi, di base maupun split density) diarahkan ke `res/clonapps_icon.png`. XML yang dirujuk meta-data (shortcut statis, sync adapter, searchable): hanya `targetPackage` paket lama dan authority yang dipetakan. |
| Mengemas + alignment | Arsip baru ditulis entri demi entri; data yang tidak diubah disalin mentah (tanpa kompresi ulang) sambil diverifikasi CRC-32. Tanda tangan lama (`META-INF/*.SF/.RSA/.DSA/.EC/MANIFEST.MF`, `stamp-cert-sha256`) dibuang; file META-INF lain (services, versi) dipertahankan. Entri STORED disejajarkan 4 byte, `.so` 16 KiB (extra field 0xD935). `resources.arsc` selalu STORED. **Alignment dilakukan sebelum tanda tangan.** |
| Menandatangani | apksig `ApkSigner` dengan `setAlignmentPreserved(true)`: skema v2 + v3 (v1 juga bila minSdk < 24). Base dan semua split memakai kunci yang sama. |
| Memverifikasi | apksig `ApkVerifier` per APK; sertifikat penanda tangan harus sama dengan kunci clone; alignment diperiksa ulang pada file bertanda tangan; manifest hasil diurai ulang: paket baru, tidak ada nama komponen relatif, tidak ada authority lama. SHA-256 tiap file dihitung. Bila satu APK gagal, semua hasil dihapus. |

## Perubahan identitas (terarah, bukan penggantian string massal)

| Item | Aturan |
|---|---|
| `<manifest package>` | → paket baru (base dan semua split) |
| Nama kelas relatif (`.Main`, `Main`) pada application/activity/alias/service/receiver/provider/instrumentation, `targetActivity`, `backupAgent`, `manageSpaceActivity`, `parentActivityName`, `appComponentFactory` | dijadikan absolut terhadap **paket lama**, karena kelasnya tidak dipindahkan |
| `android:authorities` provider di dalam `<application>` | `paket.lama.x` → `paket.baru.x`; selain itu diberi awalan `paket.baru.` (wajib unik di perangkat). Provider di `<queries>` milik aplikasi lain tidak disentuh |
| `<permission>`, `<permission-group>`, `<permission-tree>` yang dideklarasikan aplikasi | dipetakan seperti authority; `uses-permission` dan atribut `permission/readPermission/writePermission` hanya diubah bila merujuk permission yang dideklarasikan aplikasi itu sendiri. Permission sistem/pihak lain tidak disentuh |
| `sharedUserId` | dipetakan (peringatan ditampilkan) |
| `taskAffinity` | hanya bila sama dengan/berawalan paket lama |
| `instrumentation targetPackage` | paket lama → baru |
| Label | atribut `label` pada `<application>` dan aktivitas launcher diganti string; ditambahkan bila tidak ada |
| DEX | **tidak diubah**. String identitas di kode hanya dilaporkan |

## Aplikasi

- **Sumber terpasang**: `ApplicationInfo.sourceDir` + `splitSourceDirs` (semua split), diambil ulang saat job diproses.
- **Sumber file**: disalin dari SAF ke penyimpanan privat job. `.apks` (bundletool/SAI) dan `.xapk` dibuka sebagai beberapa APK
  terpisah; split ABI yang tidak didukung perangkat dilewati; file OBB dilaporkan tidak ikut.
- **Antrean**: `CloneService` (foreground service `dataSync`) memproses job satu per satu. Pembatalan lewat `CancelToken` yang
  diperiksa di setiap tahap dan di perulangan penyalinan data.
- **Pemulihan proses**: `jobs.json` ditulis atomik. Saat aplikasi dimulai ulang, job yang tadinya *berjalan* ditandai
  *Terputus* (file parsial dihapus), job *menunggu* tetap di antrean dan dilanjutkan.
- **Pemasangan**: `PackageInstaller` session (base + semua split, mode full install) dengan konfirmasi Android. Status
  *Dipasang* baru ditetapkan setelah `PackageManager` menunjukkan paket ada dan sertifikatnya sama dengan kunci clone.
  Kegagalan dipetakan ke penyebab spesifik (`InstallErrors`).
- **Terbukti berjalan**: setelah clone dibuka, `UsageStatsManager` (bila pengguna memberi izin Akses penggunaan) harus
  mencatat `ACTIVITY_RESUMED` untuk paket clone; tanpa izin itu pengguna dapat mengonfirmasi sendiri. Statusnya dibedakan.
- **Kunci**: RSA-2048 + sertifikat X.509 self-signed per package clone, disimpan sebagai blob AES-256-GCM dengan kunci induk
  di Android Keystore. Tidak pernah ada kunci privat di repository. Cadangan: PKCS#12 dengan kata sandi pengguna.
