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
        val notification = buildNotification(job)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                JobService.SUMMARY_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(JobService.SUMMARY_ID, notification)
        }
    }

    private fun buildNotification(job: JobProgress.Job?): Notification {
        val builder = NotificationCompat.Builder(this, JobProgress.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job?.title ?: getString(android.R.string.app_name))
            .setContentText(
                job?.detail?.ifBlank { job.message } ?: getString(android.R.string.app_name)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
        when {
            job == null -> builder.setProgress(0, 0, true)
            job.percent >= 0 -> builder.setProgress(100, job.percent, false)
            else -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

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