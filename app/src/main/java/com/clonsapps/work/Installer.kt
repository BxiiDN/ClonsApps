package com.clonsapps.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.clonsapps.ClonApp
import com.clonsapps.data.CloneJob
import com.clonsapps.data.InstallState
import com.clonsapps.engine.InstallErrors
import com.clonsapps.platform.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Pemasangan HANYA melalui PackageInstaller Android. Untuk aplikasi biasa Android menampilkan
 * layar konfirmasi; ClonApps tidak pernah memasang diam-diam.
 */
object Installer {
    const val EXTRA_JOB = "com.clonsapps.extra.JOB"

    data class Outcome(val status: Int, val message: String?)

    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<Outcome>>()
    private val _confirmations = MutableSharedFlow<Intent>(extraBufferCapacity = 8)

    /** Intent konfirmasi Android yang harus dibuka aktivitas yang sedang tampil. */
    val confirmations: SharedFlow<Intent> = _confirmations.asSharedFlow()

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Membuat sesi, menulis base + seluruh split, lalu commit. Mengembalikan ID sesi. */
    fun commit(context: Context, jobId: String, files: List<File>, packageName: String): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                files.forEachIndexed { i, f ->
                    val name = if (i == 0) "base.apk" else "split_$i.apk"
                    FileInputStream(f).use { input ->
                        session.openWrite(name, 0, f.length()).use { out ->
                            input.copyTo(out, 256 * 1024)
                            session.fsync(out)
                        }
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_JOB, jobId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
        return sessionId
    }

    /** Untuk pengujian: commit lalu tunggu status akhir (atau PENDING_USER_ACTION). */
    suspend fun commitAndAwait(context: Context, jobId: String, files: List<File>, packageName: String): Outcome {
        val waiter = CompletableDeferred<Outcome>()
        // Pendaftaran waiter sebelum commit menghindari balapan dengan receiver.
        pendingWaiter = waiter
        val id = commit(context, jobId, files, packageName)
        if (!waiter.isCompleted) waiters[id] = waiter
        pendingWaiter = null
        return waiter.await()
    }

    @Volatile
    private var pendingWaiter: CompletableDeferred<Outcome>? = null

    internal fun deliver(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val jobId = intent.getStringExtra(EXTRA_JOB)
        val repo = ClonApp.get(context).jobs
        val waiter = waiters.remove(sessionId) ?: pendingWaiter
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (jobId != null) {
                repo.update(jobId) { it.copy(install = InstallState.PENDING_USER, installMessage = "Konfirmasi pemasangan di layar Android.", installAction = null) }
            }
            if (waiter != null) {
                waiter.complete(Outcome(status, message))
                return
            }
            if (confirm != null && !_confirmations.tryEmit(confirm)) {
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return
        }
        if (jobId != null) {
            if (status == PackageInstaller.STATUS_SUCCESS) {
                markInstalled(context, jobId)
            } else {
                val ex = InstallErrors.explain(status, message)
                repo.update(jobId) {
                    it.copy(
                        install = InstallState.FAILED,
                        installMessage = "[${ex.code}] ${ex.cause}",
                        installAction = ex.action,
                    )
                }
            }
        }
        waiter?.complete(Outcome(status, message))
    }

    /** Status "dipasang" hanya setelah PackageManager memastikan paket ada dan bertanda tangan kunci clone. */
    fun markInstalled(context: Context, jobId: String) {
        val app = ClonApp.get(context)
        val job = app.jobs.get(jobId) ?: return
        val signers = Platform.signerSha256(context, job.newPackage)
        val ok = signers != null && job.certSha256 != null && signers.contains(job.certSha256)
        app.jobs.update(jobId) {
            if (ok) {
                it.copy(install = InstallState.INSTALLED, installMessage = "Terpasang dan tanda tangan cocok dengan kunci clone.", installAction = null)
            } else {
                it.copy(
                    install = InstallState.FAILED,
                    installMessage = "Android melaporkan berhasil, tetapi paket/tanda tangan tidak ditemukan sesuai harapan.",
                    installAction = "Periksa daftar aplikasi; ulangi pemasangan.",
                )
            }
        }
    }

    /** Sinkronkan status dengan keadaan nyata PackageManager (misalnya setelah ClonApps ditutup saat konfirmasi). */
    fun reconcile(context: Context, job: CloneJob) {
        val repo = ClonApp.get(context).jobs
        val signers = Platform.signerSha256(context, job.newPackage)
        val installedOurs = signers != null && job.certSha256 != null && signers.contains(job.certSha256)
        when {
            (job.install == InstallState.PENDING_USER || job.install == InstallState.INSTALLING) && installedOurs ->
                markInstalled(context, job.id)
            job.install == InstallState.INSTALLED && signers == null ->
                repo.update(job.id) { it.copy(install = InstallState.UNINSTALLED, installMessage = "Clone tidak lagi terpasang.") }
            else -> Unit
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Installer.deliver(context, intent)
    }
}
