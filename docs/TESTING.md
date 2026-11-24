# Pengujian

Hasil **build**, **uji JVM**, **uji emulator**, dan **uji perangkat fisik** dibedakan. Build yang berhasil tidak membuktikan
bahwa aplikasi target berjalan sebagai clone.

Hasil terakhir: commit `faa2462`, run [45348331357](https://github.com/BxiiDN/ClonsApps/actions/runs/45348331357),
rilis [v1.0.0-build.5](https://github.com/BxiiDN/ClonsApps/releases/tag/v1.0.0-build.5)
(`ClonApps-v1.0.0-faa2462.apk`, SHA-256 `af9ba46e22db2ad4bb504b7c07241b9e8e0c757a329898c5808070ee3bfcea5e`).

## 1. JVM (`./gradlew :engine:test :app:testReleaseUnitTest`)

| Rangkaian | Isi | Hasil |
|---|---|---|
| `CoreFormatTest` (15) | baca/patch manifest (nama relatif, authority, permission, `<queries>` tidak disentuh, label ditambah), round-trip XML biner UTF-8/UTF-16, shortcut XML, ARSC (ganti nama paket, tambah string, arahkan ikon di 2 konfigurasi termasuk entri compact), pemindai DEX, alignment 4 B / 16 KiB, ZIP rusak (terpotong, bukan ZIP, CRC, tanpa manifest), sertifikat, aturan nama | lulus |
| `EngineFailureTest` (13) | batal di tengah pengemasan & sebelum mulai (file parsial terhapus), ruang kurang sebelum mulai, disk penuh saat menulis (ENOSPC), entri rusak, split wajib hilang, base ganda/tanpa base/split ganda/split paket lain, ABI & minSdk, validasi identitas, risiko (authority tertulis di kode, PairIP), bundel .apks/.xapk (split ABI asing & OBB), varian bundletool per SDK | lulus |
| `EngineSigningTest` (2) | base + 2 split (tanpa `<uses-sdk>` seperti split Play Store) → tanda tangan apksig, verifikasi, DEX identik, META-INF/services tetap, ikon, shortcut; kunci sama → sertifikat sama | lulus |
| `SampleApksTest` (5) | APK contoh yang dibangun AGP: simple, multidex (classes2.dex), nativelib, provider, split dari bundletool — setiap entri sumber ada di hasil, DEX/.so tidak berubah, alignment, paket resource baru, verifikasi apksig | lulus |
| `:app` `JobRulesTest` (5) | JSON riwayat, entri rusak dilewati, pemulihan setelah proses mati, ulang, urutan antrean & paket ganda | lulus |

## 2. Emulator (GitHub Actions, `sdk_gphone64_x86_64`, API 34, x86_64)

`scripts/device-test.sh` memasang sampel (split lewat `bundletool install-apks`: base + `config.mdpi` + `config.x86_64`),
lalu menjalankan `CloneDeviceTest` terhadap **APK rilis yang sama dengan yang diterbitkan**. Hasil: **OK (10 tests)**.
Semua pemasangan lewat sesi PackageInstaller milik ClonApps (izin shell diadopsi hanya agar konfirmasi tidak perlu diketuk).

| Test | Bukti yang diperiksa |
|---|---|
| t01 | asli + clone terpasang bersamaan; `lastUpdateTime` dan sertifikat asli tidak berubah; label aplikasi & launcher "Simple Kedua"; ikon kustom (piksel tengah `#e61414`); UID 10192 vs 10199; data `/data/user/0/<paket>` terpisah; clone mulai kosong |
| t02 | update dengan kunci yang sama → terpasang, label berubah, data tetap (hitungan buka 1→2); kunci lain → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, data tetap (→3) |
| t03 | multidex: clone berjalan, 6 file DEX |
| t04 | sumber **file** (bukan terpasang): library native dimuat (`ok:x86_64`) |
| t05 | provider: authority `…clone2.notes`, data provider terpisah (asli 0, clone 2), permission kustom milik paket masing-masing |
| t06 | split: 3 APK hasil, split clone = split asli, native dari split ABI |
| t07 | dua clone tambahan + asli = 3 UID berbeda, kunci berbeda per clone |
| t08 | package milik aplikasi lain ditolak (`PACKAGE_IN_USE`) tanpa menyentuh aplikasi itu |
| t09 | pembatalan → tanpa file hasil; APK rusak → `CRC_MISMATCH` |
| t10 | pemulihan proses: job berjalan → Terputus, file parsial dihapus, antrean tetap |

UI smoke (APK rilis dibuka dan dinavigasi, ponsel terang/gelap dan tablet 2560×1600): tidak ada crash.

## 3. Perangkat fisik

- Xiaomi Pad 6 (laporan pengguna, build sebelum `faa2462`): clone WhatsApp gagal di tahap 7 dengan
  `VERIFY_FAILED split_config.arm64_v8a.apk: []`. Penyebab: split dari Play Store tidak punya `<uses-sdk>`, sehingga apksig
  memverifikasinya sebagai API 1. Diperbaiki di `faa2462` (verifikasi memakai minSdk base) — **belum diuji ulang di perangkat**.
- Aplikasi Play Store (WhatsApp dll.) belum terbukti berjalan sebagai clone. Untuk WhatsApp, Periksa sudah menampilkan
  Play Integrity, layanan Google/Firebase, SDK Facebook, dan authority yang tertulis di kode — clone bisa terpasang tetapi
  aplikasinya sendiri mungkin menolak berjalan atau gagal di sebagian fitur.
