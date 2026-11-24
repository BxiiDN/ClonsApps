package com.clonsapps.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Penyimpanan antrean + riwayat job (JSON, ditulis atomik). Dimuat ulang setelah proses mati,
 * lalu job yang sedang berjalan ditandai terputus (lihat [JobRules.recover]).
 */
class JobRepository(context: Context, private val baseDir: File = context.filesDir) {
    private val file = File(baseDir, "jobs.json")
    private val lock = Any()
    private val _jobs = MutableStateFlow<List<CloneJob>>(emptyList())
    val jobs: StateFlow<List<CloneJob>> = _jobs.asStateFlow()

    init {
        val loaded = runCatching { if (file.isFile) JobCodec.decode(file.readText()) else emptyList() }.getOrElse { emptyList() }
        val recovered = JobRules.recover(loaded)
        recovered.filter { it.build == BuildState.INTERRUPTED }.forEach { cleanPartial(it.id) }
        _jobs.value = recovered
        if (recovered != loaded) persist(recovered)
    }

    fun jobDir(id: String): File = File(baseDir, "jobs/$id")
    fun outDir(id: String): File = File(jobDir(id), "out")
    fun inputDir(id: String): File = File(jobDir(id), "input")

    private fun cleanPartial(id: String) {
        File(outDir(id), ".tmp").deleteRecursively()
        outDir(id).listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
        File(jobDir(id), "extracted").deleteRecursively()
    }

    fun get(id: String): CloneJob? = _jobs.value.firstOrNull { it.id == id }

    fun add(job: CloneJob) = synchronized(lock) {
        val list = _jobs.value + job
        _jobs.value = list
        persist(list)
    }

    /** @param save false untuk pembaruan progres yang sering (tidak ditulis ke disk). */
    fun update(id: String, save: Boolean = true, transform: (CloneJob) -> CloneJob) = synchronized(lock) {
        val list = _jobs.value.map { if (it.id == id) transform(it) else it }
        _jobs.value = list
        if (save) persist(list)
    }

    fun remove(id: String) = synchronized(lock) {
        val list = _jobs.value.filterNot { it.id == id }
        _jobs.value = list
        persist(list)
        jobDir(id).deleteRecursively()
    }

    fun deleteOutputs(id: String) {
        outDir(id).deleteRecursively()
        File(jobDir(id), "extracted").deleteRecursively()
        update(id) { it.copy(outputsDeleted = true) }
    }

    private fun persist(list: List<CloneJob>) {
        runCatching {
            val tmp = File(file.parentFile, "jobs.json.tmp")
            tmp.writeText(JobCodec.encode(list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
