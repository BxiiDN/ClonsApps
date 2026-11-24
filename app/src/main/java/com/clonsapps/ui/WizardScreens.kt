package com.clonsapps.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clonsapps.engine.Issue
import com.clonsapps.platform.InstalledApp
import com.clonsapps.platform.PackageStatus
import com.clonsapps.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StepHeader(current: WizardStep) {
    val all = listOf("Pilih Sumber", "Atur Clone", "Periksa", "Proses", "Pasang/Simpan")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        all.forEachIndexed { i, t ->
            val active = i == current.ordinal
            val done = i < current.ordinal
            val color = when {
                active -> MaterialTheme.colorScheme.primary
                done -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.outline
            }
            Text(
                "${i + 1}. $t",
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
            if (i < all.size - 1) Text("›", color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ---------------------------------------------------------------- 1. Pilih sumber

@Composable
fun SourceScreen(w: WizardState) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Aplikasi terpasang") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("File APK dari penyimpanan") })
        }
        w.error?.let {
            Column(Modifier.padding(16.dp)) { MessageRow(Icons.Filled.Warning, Tone.ERROR, "Tidak dapat memakai sumber ini", it, null) }
        }
        if (tab == 0) InstalledList(w) else FilePicker(w)
    }
}

@Composable
private fun InstalledList(w: WizardState) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val apps by produceState<List<InstalledApp>?>(null, showSystem) {
        value = withContext(Dispatchers.IO) { Platform.installedApps(context, showSystem) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Cari nama atau package") },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tampilkan aplikasi sistem", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = showSystem, onCheckedChange = { showSystem = it })
        }
        val list = apps
        if (list == null) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Memuat daftar aplikasi…")
            }
        } else {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) list else list.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            Text("${filtered.size} aplikasi", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { a ->
                    AppRow(a) { w.selectInstalled(a) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AppRow(a: InstalledApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, a.packageName) {
        value = withContext(Dispatchers.IO) { Platform.appIcon(context, a.packageName) }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(a.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                buildString {
                    append("v${a.versionName ?: "?"} (${a.versionCode})")
                    append(" · API ${a.minSdk}–${a.targetSdk}")
                    append(" · ").append(Platform.formatSize(a.sizeBytes))
                    if (a.splitNames.isNotEmpty()) append(" · ${a.splitNames.size} split")
                    if (a.isSystem) append(" · sistem")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilePicker(w: WizardState) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        w.pickFiles(uris)
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Pilih dari penyimpanan") {
            Text(
                "Pilih satu APK, atau bundel split (.apks dari bundletool/SAI, .xapk). Untuk aplikasi split, Anda juga boleh memilih base.apk beserta semua split_*.apk sekaligus. Split diproses satu per satu dan tidak digabung.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { launcher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "*/*")) },
                enabled = w.busy == null,
            ) { Text("Pilih file…") }
            w.busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it)
                }
            }
        }
        SectionCard("Catatan") {
            Text("• File disalin ke penyimpanan privat ClonApps lalu diproses sepenuhnya di perangkat ini.", style = MaterialTheme.typography.bodySmall)
            Text("• Bundel terenkripsi (misalnya .apkm) tidak didukung.", style = MaterialTheme.typography.bodySmall)
            Text("• Bila aplikasi sudah terpasang, memilih dari daftar aplikasi terpasang menjamin semua split ikut diambil.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- 2. Atur clone

@Composable
fun ConfigureScreen(w: WizardState) {
    val context = LocalContext.current
    val s = w.source ?: return
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) w.pickIcon(uri) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Sumber") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(s.icon, 52)
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = MaterialTheme.typography.titleMedium)
                    Text(s.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(
                        "v${s.versionName ?: "?"} (${s.versionCode}) · ${if (s.kind == com.clonsapps.data.SourceKind.INSTALLED) "aplikasi terpasang" else "file"} · ${s.apks.size} APK",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = { w.step = WizardStep.SOURCE }) { Text("Ganti") }
            }
            s.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }

        SectionCard("Identitas clone") {
            OutlinedTextField(
                value = w.label,
                onValueChange = { w.label = it },
                label = { Text("Nama aplikasi") },
                isError = !w.labelValid,
                supportingText = { Text(if (w.labelValid) "Nama yang tampil di launcher." else "Isi 1–100 karakter.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = w.packageName,
                onValueChange = { w.updatePackage(it) },
                label = { Text("Package name") },
                isError = w.packageStatus?.blocking == true,
                supportingText = { Text(w.packageStatus?.message(w.packageName) ?: "Memeriksa…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = w.outputName,
                onValueChange = { w.outputName = it },
                label = { Text("Nama file hasil") },
                isError = !w.fileNameValid,
                supportingText = {
                    Text(if (w.fileNameValid) "Disimpan sebagai \"${w.outputName.trim()}.apk\" (atau folder dengan nama ini bila ada split)." else "Gunakan huruf, angka, spasi, titik, tanda hubung, atau garis bawah.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("Ikon") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !w.useCustomIcon, onClick = { w.useCustomIcon = false })
                Text("Ikon asli", Modifier.clickable { w.useCustomIcon = false })
                Spacer(Modifier.width(16.dp))
                RadioButton(selected = w.useCustomIcon, onClick = { if (w.iconPng != null) w.useCustomIcon = true else iconPicker.launch(arrayOf("image/*")) })
                Text("Gambar sendiri", Modifier.clickable { iconPicker.launch(arrayOf("image/*")) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val preview = if (w.useCustomIcon) w.iconPreview else s.icon
                if (preview != null) {
                    Image(preview.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                }
                OutlinedButton(onClick = { iconPicker.launch(arrayOf("image/*")) }) { Text("Pilih gambar…") }
            }
            Text(
                "Ikon kustom diterapkan dengan mengarahkan resource ikon aplikasi ke gambar baru (di base dan split density). Ikon adaptif asli diganti gambar persegi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Identitas data aplikasi") {
            KeyValue("Direktori data", Platform.dataDirFor(context, w.packageName.ifBlank { "…" }), mono = true)
            Text(
                "Direktori data privat ditentukan dan dikelola Android berdasarkan package name; ClonApps tidak dapat dan tidak akan memindahkan atau menamai ulang folder privat. " +
                    "Dengan package name berbeda, clone otomatis memiliki data sendiri yang terpisah dari aplikasi asli.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Clone mulai dengan data kosong: akun, login, dan data privat aplikasi asli tidak diambil atau disalin.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (w.packageStatus is PackageStatus.OurClone) {
                Text("Clone dengan package ini sudah ada; memperbarui clone mempertahankan datanya karena kunci tanda tangannya sama.", style = MaterialTheme.typography.bodySmall)
            }
        }

        ButtonRow {
            TextButton(onClick = { w.reset() }) { Text("Batal") }
            Button(onClick = { w.inspect() }, enabled = w.canReview()) { Text("Periksa") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- 3. Periksa

@Composable
fun ReviewScreen(w: WizardState, onQueued: (String) -> Unit) {
    val s = w.source ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        w.busy?.let {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(it)
                }
            }
        }
        w.inspectError?.let { MessageRow(Icons.Filled.Warning, Tone.ERROR, "Pemeriksaan gagal", it, null) }
        val insp = w.inspection
        if (insp != null) {
            val b = insp.base
            SectionCard("Aplikasi asli") {
                KeyValue("Nama", insp.label ?: "—")
                KeyValue("Package", b.packageName, mono = true)
                KeyValue("Versi", "${b.versionName ?: "?"} (${b.versionCode})")
                KeyValue("SDK", "min ${b.minSdk} · target ${b.targetSdk}")
                KeyValue("APK", "base" + if (insp.splits.isEmpty()) "" else " + ${insp.splits.size} split: " + insp.splitNames().joinToString(", "))
                KeyValue("Ukuran", Platform.formatSize(insp.totalSize))
                KeyValue("DEX", "${insp.dexCount} file" + if (insp.dexCount > 1) " (multidex)" else "")
                KeyValue("Native", if (insp.nativeAbis.isEmpty()) "tidak ada" else insp.nativeAbis.joinToString(", "))
                KeyValue("Perangkat", "API ${Platform.device().sdkInt} · ${Platform.device().supportedAbis.joinToString(", ")}")
            }
            SectionCard("Rencana perubahan") {
                KeyValue("Package", "${b.packageName} → ${w.packageName}", mono = true)
                KeyValue("Nama", "${insp.label ?: "—"} → ${w.label.trim()}")
                KeyValue("Ikon", if (w.useCustomIcon) "gambar sendiri (${insp.iconResIds.size} resource ikon)" else "asli")
                KeyValue("File hasil", w.outputName.trim() + if (insp.splits.isEmpty()) ".apk" else "/ (${insp.splits.size + 1} APK)")
                if (insp.authorityMap.isNotEmpty()) {
                    Text("Authority provider", style = MaterialTheme.typography.labelLarge)
                    insp.authorityMap.forEach { (k, v) -> Text("$k\n  → $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
                if (insp.permissionMap.isNotEmpty()) {
                    Text("Permission milik aplikasi", style = MaterialTheme.typography.labelLarge)
                    insp.permissionMap.forEach { (k, v) -> Text("$k\n  → $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
                Text(
                    "Nama kelas komponen tetap (kode tidak dipindahkan); nama relatif dijadikan absolut. Tidak ada penggantian string massal pada kode atau resource.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            w.packageStatus?.let { st ->
                if (st.blocking) MessageRow(Icons.Filled.Warning, Tone.ERROR, "Package name tidak dapat dipakai", st.message(w.packageName), null)
            }
            val errors = insp.issues.filter { it.severity == Issue.Severity.ERROR }
            val warns = insp.issues.filter { it.severity == Issue.Severity.WARNING }
            val infos = insp.issues.filter { it.severity == Issue.Severity.INFO }
            if (errors.isNotEmpty()) SectionCard("Harus diperbaiki (${errors.size})") { errors.forEach { IssueRow(it) } }
            if (warns.isNotEmpty()) SectionCard("Peringatan (${warns.size})") { warns.forEach { IssueRow(it) } }
            if (errors.isEmpty() && warns.isEmpty()) OkRow("Tidak ada masalah yang terdeteksi. Fungsi tetap perlu diuji setelah dipasang.")
            SectionCard("Info") { infos.forEach { IssueRow(it) } }
        }
        ButtonRow {
            TextButton(onClick = { w.step = WizardStep.CONFIGURE }) { Text("Kembali") }
            Button(onClick = { w.enqueue()?.let(onQueued) }, enabled = w.canEnqueue()) { Text("Tambahkan ke antrean") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
