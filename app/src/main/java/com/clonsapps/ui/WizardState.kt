package com.clonsapps.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clonsapps.ClonApp
import com.clonsapps.data.CloneJob
import com.clonsapps.data.JobRules
import com.clonsapps.data.SourceKind
import com.clonsapps.engine.BundleExtractor
import com.clonsapps.engine.CancelToken
import com.clonsapps.engine.CloneException
import com.clonsapps.engine.CloneSpec
import com.clonsapps.engine.Inspection
import com.clonsapps.engine.Inspector
import com.clonsapps.engine.PackageNames
import com.clonsapps.platform.Conflicts
import com.clonsapps.platform.InstalledApp
import com.clonsapps.platform.PackageStatus
import com.clonsapps.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class WizardStep(val title: String) {
    SOURCE("Pilih Sumber"),
    CONFIGURE("Atur Clone"),
    REVIEW("Periksa"),
}

class SelectedSource(
    val kind: SourceKind,
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    /** APK yang akan diperiksa (base + split). */
    val apks: List<File>,
    /** FILE: file asli yang dipilih (disalin), dipindahkan ke job saat masuk antrean. */
    val picked: List<File>,
    val icon: Bitmap?,
    val notes: List<String>,
)

/** Status wizard Pilih Sumber → Atur Clone → Periksa. Disimpan di Application agar tahan rotasi layar. */
class WizardState(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = ClonApp.get(context)

    var step by mutableStateOf(WizardStep.SOURCE)
    var source by mutableStateOf<SelectedSource?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    var label by mutableStateOf("")
    var packageName by mutableStateOf("")
        private set
    var outputName by mutableStateOf("")
    var useCustomIcon by mutableStateOf(false)
    var iconPng by mutableStateOf<ByteArray?>(null)
        private set
    var iconPreview by mutableStateOf<Bitmap?>(null)
        private set
    var packageStatus by mutableStateOf<PackageStatus?>(null)
        private set

    var inspection by mutableStateOf<Inspection?>(null)
        private set
    var inspectError by mutableStateOf<String?>(null)
        private set

    private var statusJob: Job? = null
    private var pickDir: File? = null

    fun reset() {
        step = WizardStep.SOURCE
        source = null
        error = null
        busy = null
        label = ""
        packageName = ""
        outputName = ""
        useCustomIcon = false
        iconPng = null
        iconPreview = null
        packageStatus = null
        inspection = null
        inspectError = null
        pickDir?.deleteRecursively()
        pickDir = null
    }

    private fun takenInQueue(pkg: String) = JobRules.packageTakenInQueue(app.jobs.jobs.value, pkg)

    fun selectInstalled(a: InstalledApp) {
        val files = Platform.installedApkFiles(context, a.packageName)
        if (files == null) {
            error = "${a.label} tidak lagi terpasang."
            return
        }
        source = SelectedSource(
            SourceKind.INSTALLED, a.packageName, a.label, a.versionName, a.versionCode, files, emptyList(),
            Platform.appIcon(context, a.packageName), emptyList(),
        )
        prepareConfig()
    }

    /** Menyalin file pilihan (SAF) lalu membaca APK/bundel di perangkat. */
    fun pickFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        error = null
        busy = "Menyalin dan membaca file…"
        scope.launch {
            try {
                val sel = withContext(Dispatchers.IO) { readPicked(uris) }
                source = sel
                prepareConfig()
            } catch (e: CloneException) {
                error = e.userMessage() + "\n" + e.action()
            } catch (e: Exception) {
                error = "Gagal membaca file: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    private fun readPicked(uris: List<Uri>): SelectedSource {
        pickDir?.deleteRecursively()
        val dir = File(context.cacheDir, "picked/" + UUID.randomUUID()).apply { mkdirs() }
        pickDir = dir
        val picked = ArrayList<File>()
        uris.forEachIndexed { i, uri ->
            val name = Platform.displayName(context, uri)
            val ext = name.substringAfterLast('.', "apk").lowercase().take(5)
            val f = File(dir, "orig_$i.$ext")
            context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it, 256 * 1024) } }
                ?: throw IllegalStateException("Tidak bisa membuka $name")
            picked.add(f)
        }
        val notes = ArrayList<String>()
        val apks = ArrayList<File>()
        picked.forEachIndexed { i, f ->
            val ex = BundleExtractor.extract(f, File(dir, "ex_$i"), Platform.device(), CancelToken.NONE)
            ex.notes.forEach { notes.add("${it.title}: ${it.detail}") }
            apks.addAll(ex.apks)
        }
        val insp = Inspector.inspect(apks, Platform.device(), null, CancelToken.NONE)
        val base = insp.base
        val baseFile = apks.firstOrNull { f ->
            runCatching { com.clonsapps.engine.ApkSet.openApk(f).let { a -> a.zip.close(); a.isBase } }.getOrDefault(false)
        }
        return SelectedSource(
            SourceKind.FILE, base.packageName, insp.label ?: base.packageName, base.versionName, base.versionCode,
            apks, picked, baseFile?.let { Platform.archiveIcon(context, it) }, notes,
        )
    }

    private fun prepareConfig() {
        val s = source ?: return
        val pkg = Conflicts.suggest(context, s.packageName) { takenInQueue(it) }
        val n = pkg.substringAfterLast(".clone", "2")
        label = "${s.label} ($n)"
        outputName = PackageNames.sanitizeFileName("${s.label} $n")
        useCustomIcon = false
        iconPng = null
        iconPreview = null
        inspection = null
        inspectError = null
        updatePackage(pkg)
        step = WizardStep.CONFIGURE
    }

    fun updatePackage(value: String) {
        packageName = value.trim()
        val s = source ?: return
        statusJob?.cancel()
        statusJob = scope.launch {
            delay(200)
            val pkg = packageName
            val st = withContext(Dispatchers.IO) {
                Conflicts.check(context, pkg, s.packageName, app.keys, takenInQueue(pkg))
            }
            if (pkg == packageName) packageStatus = st
        }
    }

    fun pickIcon(uri: Uri) {
        scope.launch {
            val png = withContext(Dispatchers.IO) { Platform.iconPngFromUri(context, uri) }
            if (png == null) {
                error = "Gambar ikon tidak dapat dibaca. Pilih PNG/JPEG/WebP lain."
            } else {
                iconPng = png
                iconPreview = BitmapFactory.decodeByteArray(png, 0, png.size)
                useCustomIcon = true
            }
        }
    }

    val labelValid get() = PackageNames.validLabel(label)
    val fileNameValid get() = PackageNames.validFileName(outputName)

    fun canReview(): Boolean = source != null && labelValid && fileNameValid && packageStatus?.blocking == false

    fun spec(): CloneSpec = CloneSpec(packageName, label.trim(), if (useCustomIcon) iconPng else null, outputName.trim())

    fun inspect() {
        val s = source ?: return
        inspection = null
        inspectError = null
        step = WizardStep.REVIEW
        busy = "Memeriksa APK (manifest, split, ABI, resource, kode)…"
        val spec = spec()
        scope.launch {
            try {
                val apks = if (s.kind == SourceKind.INSTALLED) {
                    Platform.installedApkFiles(context, s.packageName) ?: throw IllegalStateException("${s.label} tidak lagi terpasang")
                } else s.apks
                inspection = withContext(Dispatchers.IO) { Inspector.inspect(apks, Platform.device(), spec, CancelToken.NONE) }
                // Konflik dengan aplikasi terpasang dicek ulang tepat sebelum masuk antrean.
                val st = withContext(Dispatchers.IO) { Conflicts.check(context, spec.newPackage, s.packageName, app.keys, takenInQueue(spec.newPackage)) }
                packageStatus = st
            } catch (e: CloneException) {
                inspectError = e.userMessage() + "\n" + e.action()
            } catch (e: Exception) {
                inspectError = "Pemeriksaan gagal: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun canEnqueue(): Boolean = inspection?.hasErrors() == false && packageStatus?.blocking == false && busy == null

    /** Menambahkan job ke antrean. Mengembalikan ID job. */
    fun enqueue(): String? {
        val s = source ?: return null
        if (!canEnqueue()) return null
        val id = UUID.randomUUID().toString().take(12)
        val repo = app.jobs
        val jobDir = repo.jobDir(id).apply { mkdirs() }
        val inputs = if (s.kind == SourceKind.FILE) {
            val inDir = repo.inputDir(id).apply { mkdirs() }
            s.picked.mapIndexed { i, f ->
                val dest = File(inDir, "in_$i." + f.extension)
                if (!f.renameTo(dest)) {
                    f.copyTo(dest, overwrite = true)
                }
                dest.absolutePath
            }
        } else emptyList()
        val iconPath = if (useCustomIcon && iconPng != null) {
            File(jobDir, "icon.png").apply { writeBytes(iconPng!!) }.absolutePath
        } else null
        val job = CloneJob(
            id = id,
            createdAt = System.currentTimeMillis(),
            sourceKind = s.kind,
            sourcePackage = s.packageName,
            sourceLabel = s.label,
            sourceVersionName = s.versionName,
            sourceVersionCode = s.versionCode,
            inputPaths = inputs,
            newPackage = packageName,
            newLabel = label.trim(),
            iconPath = iconPath,
            outputName = outputName.trim(),
        )
        repo.add(job)
        reset()
        return id
    }
}
