@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clonsapps.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clonsapps.ClonApp
import com.clonsapps.data.BuildState
import com.clonsapps.data.CloneJob
import com.clonsapps.data.InstallState
import com.clonsapps.data.JobRules
import com.clonsapps.data.RunState
import com.clonsapps.engine.Stage
import com.clonsapps.platform.Conflicts
import com.clonsapps.platform.PackageStatus
import com.clonsapps.platform.Platform
import com.clonsapps.work.CloneProcessor
import com.clonsapps.work.CloneService
import com.clonsapps.work.Exporter
import com.clonsapps.work.Installer
import com.clonsapps.work.RunVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun buildTone(j: CloneJob) = when (j.build) {
    BuildState.BUILT -> Tone.OK
    BuildState.RUNNING, BuildState.QUEUED -> Tone.INFO
    BuildState.FAILED, BuildState.INTERRUPTED -> Tone.ERROR
    BuildState.CANCELLED -> Tone.NEUTRAL
}

fun installTone(j: CloneJob) = when (j.install) {
    InstallState.INSTALLED -> Tone.OK
    InstallState.FAILED -> Tone.ERROR
    InstallState.PENDING_USER, InstallState.INSTALLING -> Tone.WARN
    else -> Tone.NEUTRAL
}

fun runTone(j: CloneJob) = when (j.run) {
    RunState.VERIFIED_SYSTEM, RunState.CONFIRMED_USER -> Tone.OK
    RunState.REPORTED_BROKEN -> Tone.ERROR
    RunState.LAUNCHED -> Tone.WARN
    RunState.UNKNOWN -> Tone.NEUTRAL
}

@Composable
private fun jobIcon(j: CloneJob): Bitmap? {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, j.id, j.install) {
        value = withContext(Dispatchers.IO) {
            (if (j.install == InstallState.INSTALLED) Platform.appIcon(context, j.newPackage) else null)
                ?: Platform.appIcon(context, j.sourcePackage)
        }
    }
    return icon
}

@Composable
fun JobList(jobs: List<CloneJob>, selected: String?, empty: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (jobs.isEmpty()) {
        Column(modifier.fillMaxSize().padding(24.dp)) { Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        items(jobs, key = { it.id }) { j ->
            val sel = j.id == selected
            SectionCard(modifier = Modifier.clickable { onSelect(j.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(jobIcon(j), 44)
                    Column(Modifier.weight(1f)) {
                        Text(
                            j.displayLabel, style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
                        )
                        Text(j.newPackage, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(j.build.label, buildTone(j))
                    if (j.build == BuildState.BUILT) StatusPill(j.install.label, installTone(j))
                    if (j.install == InstallState.INSTALLED) StatusPill(j.run.label, runTone(j))
                }
                if (j.build == BuildState.RUNNING) {
                    Text(j.stage ?: "", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private val timeFormat = SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("id-ID"))

@Composable
fun JobDetail(jobId: String, snack: (String) -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val app = ClonApp.get(context)
    val jobs by app.jobs.jobs.collectAsState()
    val j = jobs.firstOrNull { it.id == jobId } ?: return
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    runCatching { "Disimpan: " + Exporter.export(context, uri, j) }.getOrElse { "Gagal menyimpan: ${it.message}" }
                }
                snack(msg)
            }
        }
    }

    fun install() {
        if (!Installer.canInstall(context)) {
            snack("Izinkan ClonApps memasang aplikasi, lalu ketuk Pasang lagi.")
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
            return
        }
        scope.launch {
            val status = withContext(Dispatchers.IO) { Conflicts.check(context, j.newPackage, j.sourcePackage, app.keys, false) }
            if (status is PackageStatus.Taken || status is PackageStatus.SameAsOriginal) {
                snack(status.message(j.newPackage))
                return@launch
            }
            app.jobs.update(j.id) { it.copy(install = InstallState.INSTALLING, installMessage = null, installAction = null) }
            val err = withContext(Dispatchers.IO) {
                runCatching { Installer.commit(context, j.id, j.outputs.map { File(it.path) }, j.newPackage) }.exceptionOrNull()
            }
            if (err != null) {
                app.jobs.update(j.id) {
                    it.copy(install = InstallState.FAILED, installMessage = "Sesi pemasangan gagal: ${err.message}", installAction = "Pastikan ruang cukup lalu coba lagi.")
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(jobIcon(j), 56)
                Column(Modifier.weight(1f)) {
                    Text(j.displayLabel, style = MaterialTheme.typography.titleLarge)
                    Text(j.newPackage, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("Dari ${j.sourceLabel} · ${j.sourcePackage} · v${j.sourceVersionName ?: "?"}", style = MaterialTheme.typography.labelSmall)
                    Text("Dibuat ${timeFormat.format(Date(j.createdAt))}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // ---- 1. Dibuat
        SectionCard("1 · Status pembuatan") {
            StatusPill(j.build.label, buildTone(j))
            if (j.build == BuildState.QUEUED) {
                val pos = jobs.filter { it.build == BuildState.QUEUED }.sortedBy { it.createdAt }.indexOfFirst { it.id == j.id } + 1
                Text("Posisi antrean: $pos", style = MaterialTheme.typography.bodySmall)
            }
            if (j.build == BuildState.RUNNING || j.build == BuildState.BUILT || j.stageIndex >= 0) {
                Stage.values().forEachIndexed { i, st ->
                    val tone = when {
                        j.build == BuildState.BUILT || i < j.stageIndex -> Tone.OK
                        i == j.stageIndex && j.build == BuildState.RUNNING -> Tone.INFO
                        i == j.stageIndex -> Tone.ERROR
                        else -> Tone.NEUTRAL
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("${i + 1}", tone)
                        Spacer(Modifier.width(8.dp))
                        Text(st.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (j.build == BuildState.RUNNING) LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
            if (j.errorMessage != null && j.build != BuildState.BUILT) {
                MessageRow(Icons.Filled.Warning, if (j.build == BuildState.CANCELLED) Tone.NEUTRAL else Tone.ERROR,
                    "Penyebab [${j.errorCode}]", j.errorMessage, j.errorAction)
            }
            if (j.build == BuildState.BUILT) {
                if (j.outputsDeleted) Text("File hasil sudah dihapus.", style = MaterialTheme.typography.bodySmall)
                j.outputs.forEach { o ->
                    KeyValue(o.split ?: "base", "${File(o.path).name} · ${Platform.formatSize(o.size)}\nSHA-256 ${o.sha256}", mono = true)
                }
                j.certSha256?.let { KeyValue("Sertifikat", "SHA-256 $it", mono = true) }
                j.schemes?.let { KeyValue("Skema tanda tangan", it + " (terverifikasi apksig)") }
            }
            ButtonRow {
                if (j.isActive) OutlinedButton(onClick = {
                    if (j.build == BuildState.QUEUED) {
                        app.jobs.update(j.id) { it.copy(build = BuildState.CANCELLED, errorCode = "CANCELLED", errorMessage = "Dibatalkan sebelum diproses.", errorAction = null) }
                    } else {
                        CloneProcessor.requestCancel(j.id)
                    }
                }) { Text("Batalkan") }
                if (j.canRetry) Button(onClick = {
                    app.jobs.update(j.id) { JobRules.resetForRetry(it) }
                    CloneService.start(context)
                }) { Text("Coba lagi") }
            }
        }

        // ---- 2. Dipasang / disimpan
        if (j.build == BuildState.BUILT) {
            SectionCard("2 · Pasang atau simpan") {
                StatusPill(j.install.label, installTone(j))
                j.installMessage?.let {
                    MessageRow(
                        if (j.install == InstallState.FAILED) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                        installTone(j), it, null, j.installAction,
                    )
                }
                Text(
                    "Pemasangan memakai installer Android dan selalu meminta persetujuan Anda. Aplikasi asli tidak disentuh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (j.canInstall && j.install != InstallState.INSTALLING) {
                        Button(onClick = { install() }) { Text(if (j.install == InstallState.INSTALLED) "Pasang ulang/perbarui" else "Pasang") }
                    }
                    if (j.canInstall) FilledTonalButton(onClick = { exportLauncher.launch(null) }) { Text("Simpan ke folder…") }
                    if (!j.outputsDeleted) OutlinedButton(onClick = { app.jobs.deleteOutputs(j.id); snack("File hasil dihapus.") }) { Text("Hapus file hasil") }
                }
            }
        }

        // ---- 3. Berjalan
        if (j.install == InstallState.INSTALLED) {
            SectionCard("3 · Terbukti berjalan") {
                StatusPill(j.run.label, runTone(j))
                KeyValue("Data clone", Platform.dataDirFor(context, j.newPackage) + " (kosong saat pertama dibuka)", mono = true)
                Text(
                    "Dibuat dan dipasang belum berarti aplikasi berjalan. Buka clone lalu kembali ke ClonApps. " +
                        "Dengan izin Akses penggunaan, ClonApps memastikan dari catatan sistem bahwa aktivitas clone tampil; tanpa izin itu, konfirmasikan sendiri.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!RunVerifier.hasUsageAccess(context)) {
                    TextButton(onClick = { context.startActivity(RunVerifier.usageAccessIntent()) }) { Text("Beri izin Akses penggunaan (opsional)") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (!RunVerifier.launch(context, j.id)) snack("Clone tidak memiliki aktivitas launcher.") }) { Text("Buka clone") }
                    if (j.run == RunState.LAUNCHED || j.run == RunState.VERIFIED_SYSTEM || j.run == RunState.REPORTED_BROKEN) {
                        OutlinedButton(onClick = { RunVerifier.confirm(context, j.id, true) }) { Text("Berjalan normal") }
                        OutlinedButton(onClick = { RunVerifier.confirm(context, j.id, false) }) { Text("Tidak berjalan") }
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + j.newPackage)))
                    }) { Text("Copot clone") }
                }
                if (j.run == RunState.REPORTED_BROKEN) {
                    MessageRow(
                        Icons.Filled.Warning, Tone.ERROR, "Clone dilaporkan tidak berjalan",
                        "Aplikasi ini kemungkinan memeriksa tanda tangan, sumber pemasangan, atau package name aslinya. " +
                            (if (j.warnings.isEmpty()) "" else "Peringatan saat pembuatan: ${j.warnings.size}."),
                        "ClonApps tidak menonaktifkan proteksi aplikasi. Laporkan aplikasi ini (ekspor log di bawah).",
                    )
                }
            }
        }

        if (j.warnings.isNotEmpty()) {
            SectionCard("Peringatan kompatibilitas (${j.warnings.size})") {
                j.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (j.changes.isNotEmpty() || j.log.isNotEmpty()) {
            SectionCard("Perubahan & log") {
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Sembunyikan" else "Tampilkan ${j.changes.size} perubahan dan log") }
                if (showLog) {
                    j.changes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    if (j.log.isNotEmpty()) {
                        Text("Log", style = MaterialTheme.typography.labelLarge)
                        j.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }

        ButtonRow {
            if (!j.isActive) TextButton(onClick = { confirmDelete = true }) { Text("Hapus dari riwayat") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus dari riwayat?") },
            text = {
                Text(
                    "Catatan dan file hasil job ini dihapus. Clone yang sudah terpasang TIDAK dicopot, dan kunci tanda tangannya tetap disimpan agar clone bisa diperbarui."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.jobs.remove(j.id)
                    onDeleted()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } },
        )
    }
}

@Composable
fun KeyBackupDialog(export: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    val ok = pass.length >= 8 && (!export || pass == pass2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (export) "Cadangkan kunci tanda tangan" else "Pulihkan kunci tanda tangan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (export) "File PKCS#12 dilindungi kata sandi ini. Simpan di tempat aman; siapa pun yang memiliki file dan kata sandinya dapat menandatangani pembaruan clone Anda."
                    else "Masukkan kata sandi cadangan. Kunci yang sudah ada dengan sertifikat berbeda tidak akan ditimpa.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(pass, { pass = it }, label = { Text("Kata sandi (min. 8)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                if (export) OutlinedTextField(pass2, { pass2 = it }, label = { Text("Ulangi kata sandi") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onConfirm(pass.toCharArray()) }) { Text("Lanjut") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
