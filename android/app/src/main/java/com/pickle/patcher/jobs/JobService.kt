package com.pickle.patcher.jobs

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pickle.patcher.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the notification visible while a download or the
 * patch runs. It holds no work of its own: [JobProgress] owns the state, this
 * service just follows it, which is what lets a job continue after the app has
 * been backgrounded.
 */
class JobService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        JobProgress.attach(this)
        // Android only allows a few seconds between startForegroundService() and
        // startForeground(), so the service announces itself right away and then
        // swaps in the real progress.
        startInForeground(null)
        scope.launch {
            JobProgress.jobs.collectLatest { jobs ->
                val running = jobs.values.firstOrNull { it.running }
                if (running == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    startInForeground(running)
                }
            }
        }
    }

    private fun startInForeground(job: JobProgress.Job?) {
        val ctx = applicationContext
        JobProgress.ensureChannel()
        val notification = job?.let { JobProgress.notificationFor(ctx, it) }
            ?: NotificationCompat.Builder(ctx, JobProgress.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Nexora")
                .setContentText("Starting…")
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId(job),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(notificationId(job), notification)
        }
    }

    /**
     * The foreground notification carries the running job's own id, so Android
     * shows one notification that keeps being updated rather than a permanent
     * summary plus a per-job copy of the same download.
     */
    private fun notificationId(job: JobProgress.Job?): Int =
        job?.let { JobProgress.idOf(it) } ?: JobService.SUMMARY_ID

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val SUMMARY_ID = 41_001

        fun start(context: Context) {
            val intent = Intent(context, JobService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, JobService::class.java)) }
        }
    }
}