package com.clonsapps.work

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.clonsapps.ClonApp
import com.clonsapps.data.InstallState
import com.clonsapps.data.RunState

/**
 * Status "terbukti berjalan" dipisah dari "terpasang":
 * - VERIFIED_SYSTEM: UsageStatsManager mencatat aktivitas clone tampil di layar setelah dibuka
 *   (memerlukan izin Akses penggunaan yang diberikan pengguna).
 * - CONFIRMED_USER: pengguna menyatakan clone berjalan normal.
 */
object RunVerifier {
    fun launch(context: Context, jobId: String): Boolean {
        val repo = ClonApp.get(context).jobs
        val job = repo.get(jobId) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(job.newPackage) ?: return false
        val now = System.currentTimeMillis()
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        repo.update(jobId) {
            it.copy(run = if (it.run == RunState.UNKNOWN || it.run == RunState.LAUNCHED) RunState.LAUNCHED else it.run, launchedAt = now)
        }
        return true
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** True bila sistem mencatat aktivitas paket tampil di depan sejak [since]. */
    fun cameToForeground(context: Context, pkg: String, since: Long): Boolean {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return false
        val events = usm.queryEvents(since - 1000, System.currentTimeMillis() + 1000) ?: return false
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName == pkg && e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) return true
        }
        return false
    }

    /** Dipanggil saat ClonApps kembali tampil. */
    fun refresh(context: Context) {
        val repo = ClonApp.get(context).jobs
        val usage = hasUsageAccess(context)
        for (job in repo.jobs.value) {
            Installer.reconcile(context, job)
            if (usage && job.run == RunState.LAUNCHED && job.launchedAt > 0 && job.install == InstallState.INSTALLED) {
                if (cameToForeground(context, job.newPackage, job.launchedAt)) {
                    repo.update(job.id) { it.copy(run = RunState.VERIFIED_SYSTEM) }
                }
            }
        }
    }

    fun confirm(context: Context, jobId: String, works: Boolean) {
        ClonApp.get(context).jobs.update(jobId) {
            it.copy(run = if (works) RunState.CONFIRMED_USER else RunState.REPORTED_BROKEN)
        }
    }
}
