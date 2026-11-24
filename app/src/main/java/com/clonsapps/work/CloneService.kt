package com.clonsapps.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.clonsapps.ClonApp
import com.clonsapps.MainActivity
import com.clonsapps.R
import com.clonsapps.data.BuildState
import com.clonsapps.data.JobRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Memproses antrean satu per satu di layanan latar depan (agar proses panjang tidak dihentikan sistem). */
class CloneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var notifier: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = notification("Menyiapkan antrean…", 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (worker?.isActive != true) {
            val repo = ClonApp.get(this).jobs
            notifier = scope.launch {
                repo.jobs.collectLatest { list ->
                    val running = list.firstOrNull { it.build == BuildState.RUNNING } ?: return@collectLatest
                    val queued = list.count { it.build == BuildState.QUEUED }
                    val text = "${running.displayLabel}: ${running.stage ?: ""}" + if (queued > 0) " · $queued menunggu" else ""
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, running.progress))
                }
            }
            worker = scope.launch {
                val processor = CloneProcessor(this@CloneService)
                while (true) {
                    val next = JobRules.nextQueued(repo.jobs.value) ?: break
                    processor.process(next.id)
                }
                notifier?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String, progress: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("ClonApps sedang membuat clone")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .build()
    }

    companion object {
        private const val CHANNEL = "clone_queue"
        private const val NOTIF_ID = 1001

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Antrean clone", NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CloneService::class.java))
        }
    }
}
