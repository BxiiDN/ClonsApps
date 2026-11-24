package com.clonsapps.work

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.clonsapps.data.CloneJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Menyimpan hasil ke folder pilihan pengguna (Storage Access Framework).
 * Split tidak digabung: APK tunggal → "<nama>.apk"; base + split → folder "<nama>" berisi
 * base.apk, split_*.apk, dan clone-info.json (checksum, sertifikat).
 */
object Exporter {
    fun export(context: Context, treeUri: Uri, job: CloneJob): String {
        val cr = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val files = job.outputs.map { File(it.path) }
        require(files.all { it.isFile }) { "File hasil sudah dihapus atau hilang" }
        val apkMime = "application/vnd.android.package-archive"
        return if (job.outputs.size == 1) {
            val doc = DocumentsContract.createDocument(cr, rootDoc, apkMime, job.outputName + ".apk")
                ?: error("Folder tujuan menolak pembuatan file")
            copy(context, files[0], doc)
            job.outputName + ".apk"
        } else {
            val folder = DocumentsContract.createDocument(cr, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, job.outputName)
                ?: error("Folder tujuan menolak pembuatan folder")
            job.outputs.forEach { o ->
                val f = File(o.path)
                val doc = DocumentsContract.createDocument(cr, folder, apkMime, f.name) ?: error("Gagal membuat ${f.name}")
                copy(context, f, doc)
            }
            val info = JSONObject()
                .put("package", job.newPackage)
                .put("label", job.displayLabel)
                .put("originalPackage", job.sourcePackage)
                .put("certificateSha256", job.certSha256)
                .put("files", JSONArray().also { a ->
                    job.outputs.forEach { o -> a.put(JSONObject().put("file", File(o.path).name).put("split", o.split ?: "base").put("sha256", o.sha256)) }
                })
                .put("install", "Pasang SEMUA file sekaligus sebagai satu sesi (misalnya lewat ClonApps atau 'adb install-multiple'); jangan pasang base saja.")
            val doc = DocumentsContract.createDocument(cr, folder, "application/json", "clone-info.json") ?: error("Gagal membuat clone-info.json")
            cr.openOutputStream(doc)?.use { it.write(info.toString(2).toByteArray()) }
            job.outputName + "/ (" + job.outputs.size + " APK)"
        }
    }

    private fun copy(context: Context, src: File, dest: Uri) {
        context.contentResolver.openOutputStream(dest)?.use { out ->
            FileInputStream(src).use { it.copyTo(out, 256 * 1024) }
        } ?: error("Tidak bisa menulis ke folder tujuan")
    }
}
