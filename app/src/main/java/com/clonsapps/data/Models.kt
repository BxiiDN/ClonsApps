package com.clonsapps.data

import org.json.JSONArray
import org.json.JSONObject

enum class SourceKind { INSTALLED, FILE }

/** Status pembuatan file clone. Terpisah dari status pemasangan dan status berjalan. */
enum class BuildState(val label: String) {
    QUEUED("Menunggu di antrean"),
    RUNNING("Sedang diproses"),
    BUILT("Berhasil dibuat"),
    FAILED("Gagal dibuat"),
    CANCELLED("Dibatalkan"),
    INTERRUPTED("Terputus"),
}

enum class InstallState(val label: String) {
    NOT_INSTALLED("Belum dipasang"),
    INSTALLING("Mengirim ke installer Android"),
    PENDING_USER("Menunggu persetujuan Anda"),
    INSTALLED("Berhasil dipasang"),
    FAILED("Gagal dipasang"),
    UNINSTALLED("Sudah dicopot"),
}

enum class RunState(val label: String) {
    UNKNOWN("Belum dibuka"),
    LAUNCHED("Sudah dibuka, belum terbukti"),
    VERIFIED_SYSTEM("Terbukti berjalan (tercatat sistem)"),
    CONFIRMED_USER("Berjalan (dikonfirmasi Anda)"),
    REPORTED_BROKEN("Dilaporkan tidak berjalan"),
}

data class OutputFile(val path: String, val split: String?, val sha256: String, val size: Long)

data class CloneJob(
    val id: String,
    val createdAt: Long,
    val sourceKind: SourceKind,
    val sourcePackage: String,
    val sourceLabel: String,
    val sourceVersionName: String?,
    val sourceVersionCode: Long,
    /** FILE: salinan file milik job. INSTALLED: kosong, path diambil ulang dari PackageManager saat diproses. */
    val inputPaths: List<String>,
    val newPackage: String,
    val newLabel: String?,
    val iconPath: String?,
    val outputName: String,
    val build: BuildState = BuildState.QUEUED,
    val stage: String? = null,
    val stageIndex: Int = -1,
    val progress: Float = 0f,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val errorAction: String? = null,
    val outputs: List<OutputFile> = emptyList(),
    val certSha256: String? = null,
    val schemes: String? = null,
    val changes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val log: List<String> = emptyList(),
    val install: InstallState = InstallState.NOT_INSTALLED,
    val installMessage: String? = null,
    val installAction: String? = null,
    val installSessionId: Int = -1,
    val run: RunState = RunState.UNKNOWN,
    val launchedAt: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val outputsDeleted: Boolean = false,
) {
    val displayLabel: String get() = newLabel ?: sourceLabel
    val isActive: Boolean get() = build == BuildState.QUEUED || build == BuildState.RUNNING
    val canRetry: Boolean get() = build == BuildState.FAILED || build == BuildState.CANCELLED || build == BuildState.INTERRUPTED
    val canInstall: Boolean get() = build == BuildState.BUILT && !outputsDeleted && outputs.isNotEmpty()
}

/** Aturan murni (tanpa API Android) — diuji di JVM. */
object JobRules {
    const val INTERRUPTED_MESSAGE = "Proses terhenti karena ClonApps ditutup oleh sistem atau pengguna sebelum selesai."
    const val INTERRUPTED_ACTION = "File parsial sudah dibersihkan. Ketuk Coba lagi untuk mengulang dari awal."

    /**
     * Pemulihan setelah proses aplikasi mati: job RUNNING tidak mungkin masih berjalan, jadi
     * ditandai INTERRUPTED (tidak pernah dilaporkan berhasil). Job QUEUED tetap di antrean.
     */
    fun recover(jobs: List<CloneJob>): List<CloneJob> = jobs.map { j ->
        when {
            j.build == BuildState.RUNNING -> j.copy(
                build = BuildState.INTERRUPTED,
                errorCode = "INTERRUPTED",
                errorMessage = INTERRUPTED_MESSAGE,
                errorAction = INTERRUPTED_ACTION,
                progress = 0f,
                outputs = emptyList(),
            )
            j.install == InstallState.INSTALLING -> j.copy(install = InstallState.NOT_INSTALLED, installMessage = "Pengiriman ke installer terputus; periksa ulang status.")
            else -> j
        }
    }

    /** Siap diulang: semua hasil dan status pemasangan sebelumnya dihapus dari catatan job. */
    fun resetForRetry(j: CloneJob): CloneJob = j.copy(
        build = BuildState.QUEUED, stage = null, stageIndex = -1, progress = 0f,
        errorCode = null, errorMessage = null, errorAction = null,
        outputs = emptyList(), certSha256 = null, schemes = null, changes = emptyList(), warnings = emptyList(), log = emptyList(),
        startedAt = 0, finishedAt = 0, outputsDeleted = false,
    )

    fun nextQueued(jobs: List<CloneJob>): CloneJob? =
        jobs.filter { it.build == BuildState.QUEUED }.minByOrNull { it.createdAt }

    /** Package yang sedang dipakai job lain yang masih aktif/berhasil dibuat (cegah dua clone memakai paket sama di antrean). */
    fun packageTakenInQueue(jobs: List<CloneJob>, pkg: String, exceptId: String? = null): Boolean =
        jobs.any { it.id != exceptId && it.newPackage == pkg && it.isActive }
}

object JobCodec {
    fun encode(jobs: List<CloneJob>): String {
        val arr = JSONArray()
        jobs.forEach { arr.put(toJson(it)) }
        return JSONObject().put("version", 1).put("jobs", arr).toString()
    }

    fun decode(text: String): List<CloneJob> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("jobs") ?: return emptyList()
        val out = ArrayList<CloneJob>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { fromJson(o) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun strings(list: List<String>) = JSONArray().also { a -> list.forEach { a.put(it) } }

    private fun JSONObject.strList(name: String): List<String> {
        val a = optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun JSONObject.optStr(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null

    fun toJson(j: CloneJob): JSONObject = JSONObject().apply {
        put("id", j.id)
        put("createdAt", j.createdAt)
        put("sourceKind", j.sourceKind.name)
        put("sourcePackage", j.sourcePackage)
        put("sourceLabel", j.sourceLabel)
        put("sourceVersionName", j.sourceVersionName ?: JSONObject.NULL)
        put("sourceVersionCode", j.sourceVersionCode)
        put("inputPaths", strings(j.inputPaths))
        put("newPackage", j.newPackage)
        put("newLabel", j.newLabel ?: JSONObject.NULL)
        put("iconPath", j.iconPath ?: JSONObject.NULL)
        put("outputName", j.outputName)
        put("build", j.build.name)
        put("stage", j.stage ?: JSONObject.NULL)
        put("stageIndex", j.stageIndex)
        put("progress", j.progress.toDouble())
        put("errorCode", j.errorCode ?: JSONObject.NULL)
        put("errorMessage", j.errorMessage ?: JSONObject.NULL)
        put("errorAction", j.errorAction ?: JSONObject.NULL)
        put("outputs", JSONArray().also { a ->
            j.outputs.forEach { o ->
                a.put(JSONObject().put("path", o.path).put("split", o.split ?: JSONObject.NULL).put("sha256", o.sha256).put("size", o.size))
            }
        })
        put("certSha256", j.certSha256 ?: JSONObject.NULL)
        put("schemes", j.schemes ?: JSONObject.NULL)
        put("changes", strings(j.changes))
        put("warnings", strings(j.warnings))
        put("log", strings(j.log))
        put("install", j.install.name)
        put("installMessage", j.installMessage ?: JSONObject.NULL)
        put("installAction", j.installAction ?: JSONObject.NULL)
        put("installSessionId", j.installSessionId)
        put("run", j.run.name)
        put("launchedAt", j.launchedAt)
        put("startedAt", j.startedAt)
        put("finishedAt", j.finishedAt)
        put("outputsDeleted", j.outputsDeleted)
    }

    fun fromJson(o: JSONObject): CloneJob {
        val outs = ArrayList<OutputFile>()
        o.optJSONArray("outputs")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                outs.add(OutputFile(x.getString("path"), x.optStr("split"), x.optString("sha256"), x.optLong("size")))
            }
        }
        return CloneJob(
            id = o.getString("id"),
            createdAt = o.optLong("createdAt"),
            sourceKind = enumOr(o.optString("sourceKind"), SourceKind.INSTALLED),
            sourcePackage = o.getString("sourcePackage"),
            sourceLabel = o.optString("sourceLabel"),
            sourceVersionName = o.optStr("sourceVersionName"),
            sourceVersionCode = o.optLong("sourceVersionCode"),
            inputPaths = o.strList("inputPaths"),
            newPackage = o.getString("newPackage"),
            newLabel = o.optStr("newLabel"),
            iconPath = o.optStr("iconPath"),
            outputName = o.optString("outputName", "clone"),
            build = enumOr(o.optString("build"), BuildState.FAILED),
            stage = o.optStr("stage"),
            stageIndex = o.optInt("stageIndex", -1),
            progress = o.optDouble("progress", 0.0).toFloat(),
            errorCode = o.optStr("errorCode"),
            errorMessage = o.optStr("errorMessage"),
            errorAction = o.optStr("errorAction"),
            outputs = outs,
            certSha256 = o.optStr("certSha256"),
            schemes = o.optStr("schemes"),
            changes = o.strList("changes"),
            warnings = o.strList("warnings"),
            log = o.strList("log"),
            install = enumOr(o.optString("install"), InstallState.NOT_INSTALLED),
            installMessage = o.optStr("installMessage"),
            installAction = o.optStr("installAction"),
            installSessionId = o.optInt("installSessionId", -1),
            run = enumOr(o.optString("run"), RunState.UNKNOWN),
            launchedAt = o.optLong("launchedAt"),
            startedAt = o.optLong("startedAt"),
            finishedAt = o.optLong("finishedAt"),
            outputsDeleted = o.optBoolean("outputsDeleted"),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(def)
}
