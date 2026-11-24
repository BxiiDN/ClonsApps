package com.clonsapps.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clonsapps.BuildConfig
import com.clonsapps.ClonApp
import com.clonsapps.data.ThemeMode
import com.clonsapps.work.Installer
import com.clonsapps.work.RunVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(snack: (String) -> Unit) {
    val context = LocalContext.current
    val app = ClonApp.get(context)
    val theme by app.settings.theme.collectAsState()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }

    val exportDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri: Uri? ->
        val pw = pendingPassword
        pendingPassword = null
        if (uri != null && pw != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { out -> "Cadangan berisi ${app.keys.exportBackup(out, pw)} kunci tersimpan." }
                }.getOrElse { "Gagal mencadangkan: ${it.message}" }
            }
            snack(msg)
        }
    }
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val pw = pendingPassword
        pendingPassword = null
        if (uri != null && pw != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val r = context.contentResolver.openInputStream(uri)!!.use { app.keys.importBackup(it, pw) }
                    "Dipulihkan ${r.imported} kunci, ${r.alreadySame} sudah ada" +
                        if (r.skippedDifferent.isEmpty()) "." else "; dilewati karena berbeda: ${r.skippedDifferent.joinToString()}"
                }.getOrElse { "Gagal memulihkan (kata sandi salah atau file rusak): ${it.message}" }
            }
            refresh++
            snack(msg)
        }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Tampilan") {
            ThemeMode.values().forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { app.settings.setTheme(m) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = theme == m, onClick = { app.settings.setTheme(m) })
                    Text(m.label)
                }
            }
            Text("Bahasa: Indonesia", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Izin") {
            refresh.let { }
            val canInstall = Installer.canInstall(context)
            StatusPill(if (canInstall) "Boleh memasang aplikasi" else "Belum boleh memasang aplikasi", if (canInstall) Tone.OK else Tone.WARN)
            if (!canInstall) TextButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
            }) { Text("Buka pengaturan Instal aplikasi tak dikenal") }
            val usage = RunVerifier.hasUsageAccess(context)
            StatusPill(if (usage) "Akses penggunaan aktif" else "Akses penggunaan tidak aktif (opsional)", if (usage) Tone.OK else Tone.NEUTRAL)
            Text("Dipakai hanya untuk membuktikan dari catatan sistem bahwa clone benar-benar tampil setelah dibuka.", style = MaterialTheme.typography.bodySmall)
            if (!usage) TextButton(onClick = { context.startActivity(RunVerifier.usageAccessIntent()) }) { Text("Buka pengaturan Akses penggunaan") }
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                StatusPill(if (granted) "Notifikasi progres aktif" else "Notifikasi progres nonaktif", if (granted) Tone.OK else Tone.NEUTRAL)
                if (!granted) TextButton(onClick = { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Izinkan notifikasi") }
            }
        }

        SectionCard("Kunci tanda tangan clone") {
            refresh.let { }
            val pkgs = app.keys.packages()
            Text(
                "Setiap clone ditandatangani kunci miliknya sendiri yang dibuat di perangkat ini dan disimpan terenkripsi (kunci induk di Android Keystore). " +
                    "Kunci yang sama dipakai saat clone diperbarui sehingga datanya tetap ada. Clone tidak memakai tanda tangan pengembang asli.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("${pkgs.size} kunci tersimpan", style = MaterialTheme.typography.labelLarge)
            pkgs.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "Jika ClonApps dicopot atau datanya dihapus, kunci ikut hilang dan clone yang ada tidak bisa diperbarui lagi. Buat cadangan terenkripsi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = "export" }, enabled = pkgs.isNotEmpty()) { Text("Cadangkan…") }
                OutlinedButton(onClick = { dialog = "import" }) { Text("Pulihkan…") }
            }
        }

        SectionCard("Tentang metode clone") {
            Text("• ClonApps membuat APK mandiri dengan package name baru, diproses sepenuhnya di perangkat (tanpa server).", style = MaterialTheme.typography.bodySmall)
            Text("• Metode profil kerja tidak disediakan di sini: metode itu hanya menjalankan salinan aplikasi yang sama di profil lain dan TIDAK menghasilkan APK mandiri dengan identitas baru.", style = MaterialTheme.typography.bodySmall)
            Text("• Tidak semua aplikasi dapat berjalan setelah package dan tanda tangannya diubah (pemeriksaan lisensi, Play Integrity, login Google/Firebase). ClonApps melaporkannya, tidak memalsukan integritas, dan tidak menonaktifkan proteksi aplikasi.", style = MaterialTheme.typography.bodySmall)
            Text("Versi ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${if (BuildConfig.DEBUG) "build debug" else "build rilis"}", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(24.dp))
    }

    when (dialog) {
        "export" -> KeyBackupDialog(true, { dialog = null }) { pw ->
            dialog = null
            pendingPassword = pw
            exportDoc.launch("clonapps-keys.p12")
        }
        "import" -> KeyBackupDialog(false, { dialog = null }) { pw ->
            dialog = null
            pendingPassword = pw
            importDoc.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"))
        }
    }
}
