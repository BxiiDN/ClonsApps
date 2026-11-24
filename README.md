# ClonApps

Aplikasi Android untuk membuat **clone APK mandiri** dengan package name baru — dari aplikasi yang sudah terpasang
atau dari file APK/bundel split di penyimpanan. Seluruh pemrosesan berjalan **di perangkat** (tanpa komputer atau server).

<img src="docs/assets/logo.png" width="96" alt="Logo ClonApps">

## Fitur

- **Dua sumber:** aplikasi terpasang (base + semua split diambil dari PackageManager) atau file `.apk`, `.apks`, `.xapk`
  lewat pemilih file Android. Daftar menampilkan nama, ikon, versi, package name, rentang API, ukuran, dan jumlah split.
- **Atur clone:** nama aplikasi, ikon (asli atau gambar sendiri), package name (divalidasi, dicek bentrok dengan
  aplikasi terpasang dan antrean), nama file hasil. Bagian *Identitas data aplikasi* menjelaskan bahwa direktori data
  privat ditentukan Android dari package name; clone mulai dengan data kosong.
- **Alur:** Pilih Sumber → Atur Clone → Periksa → Proses → Pasang atau Simpan. Ada pencarian, antrean, pembatalan,
  riwayat, hapus file hasil, simpan ke folder (Storage Access Framework), mode gelap, layout ponsel/tablet.
- **Status terpisah:** *berhasil dibuat* (lolos verifikasi apksig), *berhasil dipasang* (dikonfirmasi PackageManager +
  sertifikat cocok), *terbukti berjalan* (tercatat UsageStats atau dikonfirmasi pengguna).
- **Kunci tanda tangan per clone** dibuat di perangkat, disimpan terenkripsi (kunci induk Android Keystore), dipakai ulang
  saat clone diperbarui; bisa dicadangkan sebagai PKCS#12 berkata sandi.

## Mesin cloning

Modul [`engine`](engine/) (Java murni, berjalan di Android): pembaca ZIP ketat, XML biner Android, `resources.arsc`,
pemindai string DEX (baca saja), alignment (4 byte / 16 KiB untuk `.so`) **sebelum** tanda tangan, tanda tangan v2/v3
(+v1 bila minSdk < 24) dengan [apksig](https://android.googlesource.com/platform/tools/apksig) dan verifikasi hasil.
Rincian: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · audit pustaka: [docs/LIBRARIES.md](docs/LIBRARIES.md).

## Unduh

APK dibangun oleh GitHub Actions dan diterbitkan di halaman **Releases** bersama SHA-256 dan ringkasan hasil uji.

## Pengujian dan keterbatasan

- [docs/TESTING.md](docs/TESTING.md) — uji JVM (format + APK contoh nyata) dan uji perangkat di emulator.
- [docs/LIMITATIONS.md](docs/LIMITATIONS.md) — apa yang tidak bisa dijamin, dan mengapa.
