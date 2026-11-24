# Keterbatasan yang diketahui

ClonApps tidak menjanjikan bahwa semua aplikasi bekerja setelah di-clone. Mengganti package name dan tanda tangan adalah
perubahan identitas yang nyata; sebagian aplikasi memang dirancang menolak hal itu.

## Batas teknis yang dilaporkan saat Periksa

| Situasi | Dampak | Yang dilakukan ClonApps |
|---|---|---|
| Pemeriksaan lisensi / sumber instalasi (PairIP, Google Play Licensing) | Clone biasanya menolak berjalan atau meminta instal dari Play Store | Dideteksi dari kode, diberi peringatan. **Tidak** dinonaktifkan. |
| Play Integrity / SafetyNet | Verifikasi integritas gagal untuk paket/sertifikat baru | Peringatan. Integritas perangkat **tidak** dipalsukan. |
| Login Google, Firebase, FCM, SDK Facebook | Kunci API/klien OAuth terikat package + SHA sertifikat asli | Peringatan; fitur tersebut mungkin tidak berfungsi. |
| Authority atau permission ditulis langsung di kode (`BuildConfig.APPLICATION_ID + ".provider"` yang dilipat kompiler, string literal) | Fitur berbagi file, sinkronisasi, widget dapat gagal karena manifest sudah memakai authority baru | Dideteksi dari tabel string DEX dan dilaporkan. DEX tidak diubah (sengaja: penggantian string massal merusak kelas). |
| Pemeriksaan tanda tangan buatan sendiri di aplikasi | Tidak terdeteksi otomatis | Status "Dilaporkan tidak berjalan" bisa dipilih pengguna setelah dibuka. |
| `sharedUserId` | Tidak lagi berbagi UID dengan aplikasi pendamping dari pengembang yang sama | Dipetakan ke identitas baru + peringatan. |
| `testOnly` | Android menolak pemasangan via installer biasa | Peringatan; penanda tidak dihapus. |
| targetSdk < 23 di Android 14+ | Android menolak pemasangan | Error sebelum diproses; targetSdk tidak diubah. |
| ZIP64 (> 65.535 entri atau > 4 GB) | — | Ditolak dengan pesan spesifik. |
| Bundel terenkripsi (.apkm) | — | Ditolak; gunakan APK/.apks/.xapk biasa atau aplikasi terpasang. |
| File OBB di .xapk | Lokasi OBB terikat package name | Tidak ikut; dicatat. |
| Akun (`AccountAuthenticator`) dengan tipe akun yang sama | Android hanya memakai autentikator pertama untuk satu tipe akun | Tidak diubah (tipe akun bukan identitas paket); fitur akun di clone bisa tidak lengkap. |
| Ikon adaptif | Diganti gambar persegi bila memilih ikon sendiri | Dijelaskan di layar Atur Clone. |

## Batas proses

- Pembatalan diperiksa di setiap tahap dan di dalam penyalinan data; penandatanganan satu file APK oleh apksig tidak bisa
  dihentikan di tengah, jadi pembatalan berlaku setelah file itu selesai.
- Entri yang dibaca utuh ke memori (manifest, `resources.arsc`, XML, DEX saat Periksa) dibatasi 512 MB per entri.
- Status "terbukti berjalan (tercatat sistem)" membutuhkan izin Akses penggunaan; itu membuktikan aktivitas clone tampil,
  bukan bahwa seluruh fitur bekerja.
- Kunci clone tersimpan di data ClonApps. Bila ClonApps dicopot/datanya dihapus tanpa cadangan, clone lama tidak bisa
  diperbarui (Android menolak tanda tangan berbeda) — hanya bisa dicopot lalu dipasang ulang dengan data kosong.
- APK ClonApps dari CI tanpa secret ditandatangani kunci debug runner yang berbeda di setiap build: memperbarui ClonApps
  dari build lain memerlukan copot-pasang (cadangkan kunci clone dulu). Dengan secret `CLONAPPS_KEYSTORE_*`, CI membuat
  APK rilis bertanda tangan tetap.

## Tidak disediakan

- **Metode profil kerja.** Metode itu tidak menghasilkan APK mandiri dengan identitas baru (aplikasinya sama, hanya di profil
  lain), jadi tidak termasuk di aplikasi ini.
- Mengganti/menamai ulang folder data privat secara bebas: direktori data selalu ditentukan Android dari package name.
- Menyalin akun atau data privat aplikasi asli ke clone.
- Mempertahankan tanda tangan pengembang asli (tidak mungkin tanpa kunci privat pengembang).
