package com.clonsapps.work

import android.content.Context
import com.clonsapps.ClonApp
import com.clonsapps.data.BuildState
import com.clonsapps.data.CloneJob
import com.clonsapps.data.JobRepository
import com.clonsapps.data.OutputFile
import com.clonsapps.data.SourceKind
import com.clonsapps.engine.BundleExtractor
import com.clonsapps.engine.CancelToken
import com.clonsapps.engine.CloneEngine
import com.clonsapps.engine.CloneException
import com.clonsapps.engine.CloneSpec
import com.clonsapps.engine.ErrorCode
import com.clonsapps.engine.Stage
import com.clonsapps.platform.Conflicts
import com.clonsapps.platform.PackageStatus
import com.clonsapps.platform.Platform
import com.clonsapps.platform.SigningKeyStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Menjalankan satu job dengan mesin cloning. Dipakai oleh [CloneService] dan oleh pengujian
 * instrumentasi (tanpa service). Status, tahap, dan progres yang ditulis adalah tahap nyata dari mesin.
 */
class CloneProcessor(
    private val context: Context,
    private val repo: JobRepository = ClonApp.get(context).jobs,
    private val keys: SigningKeyStore = ClonApp.get(context).keys,
) {
    companion object {
        private val cancelFlags = ConcurrentHashMap<String, Boolean>()

        fun requestCancel(jobId: String) {
            cancelFlags[jobId] = true
        }

        fun isCancelRequested(jobId: String) = cancelFlags[jobId] == true
    }

    /** @param outDirOverride direktori hasil lain (dipakai pengujian agar dapat dibaca shell). */
    fun process(jobId: String, outDirOverride: File? = null): CloneJob? {
        val job = repo.get(jobId) ?: return null
        if (job.build != BuildState.QUEUED) return job
        val outDir = outDirOverride ?: repo.outDir(jobId)
        repo.update(jobId) {
            it.copy(build = BuildState.RUNNING, stage = "Menyiapkan", stageIndex = -1, progress = 0f, startedAt = System.currentTimeMillis())
        }
        val log = ArrayList<String>()
        var lastProgressSave = 0L
        val cancel = CancelToken { cancelFlags[jobId] == true }
        try {
            // Package tujuan tidak boleh menimpa aplikasi orang lain (diperiksa ulang tepat sebelum proses).
            val status = Conflicts.check(context, job.newPackage, job.sourcePackage, keys, queued = false)
            when (status) {
                is PackageStatus.Invalid -> throw CloneException(status.code, job.newPackage)
                PackageStatus.SameAsOriginal -> throw CloneException(ErrorCode.PACKAGE_SAME_AS_ORIGINAL, job.newPackage)
                is PackageStatus.Taken -> throw CloneException(ErrorCode.PACKAGE_IN_USE, "${job.newPackage} dipakai \"${status.label}\"")
                else -> Unit
            }
            val inputs = resolveInputs(job, cancel, log)
            val identity = keys.getOrCreate(job.newPackage)
            val icon = job.iconPath?.let { File(it) }?.takeIf { it.isFile }?.readBytes()
            val spec = CloneSpec(job.newPackage, job.newLabel, icon, job.outputName)
            val engine = CloneEngine(object : CloneEngine.Listener {
                override fun onStage(stage: Stage, detail: String?) {
                    log.add(stage.label + (detail?.let { " — $it" } ?: ""))
                    repo.update(jobId) { it.copy(stage = stage.label, stageIndex = stage.ordinal, progress = 0f, log = log.toList()) }
                }

                override fun onProgress(stage: Stage, done: Long, total: Long) {
                    val now = System.currentTimeMillis()
                    if (now - lastProgressSave < 250 && done < total) return
                    lastProgressSave = now
                    val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    repo.update(jobId, save = false) { it.copy(progress = p) }
                }

                override fun onLog(line: String) {
                    log.add(line)
                }
            }, cancel, null)
            val result = engine.run(inputs, spec, identity, Platform.device(), outDir)
            File(repo.jobDir(jobId), "extracted").deleteRecursively()
            repo.update(jobId) {
                it.copy(
                    build = BuildState.BUILT,
                    stage = "Selesai",
                    stageIndex = Stage.values().size,
                    progress = 1f,
                    outputs = result.outputs.map { o -> OutputFile(o.file.absolutePath, o.splitName, o.sha256, o.size) },
                    certSha256 = result.certSha256,
                    schemes = result.signatureSchemes,
                    changes = result.changes,
                    warnings = result.warnings.map { w -> "${w.title}: ${w.detail ?: ""}" + (w.action?.let { a -> " — $a" } ?: "") },
                    log = log.toList(),
                    finishedAt = System.currentTimeMillis(),
                    outputsDeleted = false,
                )
            }
        } catch (e: CloneException) {
            val cancelled = e.code() == ErrorCode.CANCELLED
            log.add("Gagal: ${e.code()} ${e.detail() ?: ""}")
            File(repo.jobDir(jobId), "extracted").deleteRecursively()
            repo.update(jobId) {
                it.copy(
                    build = if (cancelled) BuildState.CANCELLED else BuildState.FAILED,
                    errorCode = e.code().name,
                    errorMessage = e.userMessage(),
                    errorAction = e.action(),
                    progress = 0f,
                    outputs = emptyList(),
                    log = log.toList(),
                    finishedAt = System.currentTimeMillis(),
                )
            }
        } catch (t: Throwable) {
            log.add("Gagal: ${t.javaClass.simpleName}: ${t.message}")
            repo.update(jobId) {
                it.copy(
                    build = BuildState.FAILED,
                    errorCode = ErrorCode.INTERNAL.name,
                    errorMessage = ErrorCode.INTERNAL.message + " Detail: ${t.javaClass.simpleName}: ${t.message}",
                    errorAction = ErrorCode.INTERNAL.action,
                    outputs = emptyList(),
                    log = log.toList(),
                    finishedAt = System.currentTimeMillis(),
                )
            }
        } finally {
            cancelFlags.remove(jobId)
        }
        return repo.get(jobId)
    }

    private fun resolveInputs(job: CloneJob, cancel: CancelToken, log: MutableList<String>): List<File> {
        return when (job.sourceKind) {
            SourceKind.INSTALLED -> {
                val files = Platform.installedApkFiles(context, job.sourcePackage)
                    ?: throw CloneException(ErrorCode.MISSING_MANIFEST, "${job.sourcePackage} tidak lagi terpasang")
                val pi = Platform.packageInfo(context.packageManager, job.sourcePackage)
                if (pi != null && Platform.versionCode(pi) != job.sourceVersionCode) {
                    log.add("Catatan: aplikasi asli sudah berubah versi sejak masuk antrean (${job.sourceVersionCode} → ${Platform.versionCode(pi)}); versi terpasang saat ini yang diproses.")
                }
                log.add("Sumber terpasang: ${files.size} file (base + ${files.size - 1} split)")
                files
            }
            SourceKind.FILE -> {
                val extractedDir = File(repo.jobDir(job.id), "extracted").apply { deleteRecursively(); mkdirs() }
                val out = ArrayList<File>()
                job.inputPaths.forEachIndexed { i, p ->
                    val f = File(p)
                    if (!f.isFile) throw CloneException(ErrorCode.IO_ERROR, "file masukan hilang: ${f.name}")
                    val ex = BundleExtractor.extract(f, File(extractedDir, "b$i"), Platform.device(), cancel)
                    ex.notes.forEach { n -> log.add("${n.title}: ${n.detail}") }
                    out.addAll(ex.apks)
                }
                log.add("Sumber file: ${out.size} APK")
                out
            }
        }
    }
}
