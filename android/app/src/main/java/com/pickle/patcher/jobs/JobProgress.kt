package com.pickle.patcher.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pickle.patcher.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every long job the app runs - library downloads, the client APK, addons, the
 * patch itself - reports here.
 *
 * The flows drive two faces of the same state: a strip inside the app while it
 * is in the foreground, and a notification once it is not. Both read this one
 * map, so a job cannot be half finished in one view and invisible in the other.
 */
object JobProgress {

    const val CHANNEL_ID = "jobs"

    /**
     * How long a finished job's result stays in the shade. Long enough to read
     * "Libraries ready" after a long download, short enough that the shade is
     * clean again by the time the user looks at it.
     */
    private const val FINISHED_TIMEOUT_MS = 4_000L

    enum class State { RUNNING, DONE, FAILED }

    data class Job(
        val id: String,
        val title: String,
        val detail: String = "",
        val done: Long = -1,
        val total: Long = -1,
        val state: State = State.RUNNING,
        val message: String = "",
        /** Set when the job produced an APK the user can install. */
        val apkPath: String? = null,
    ) {
        val running: Boolean get() = state == State.RUNNING
        val percent: Int
            get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
    }

    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs.asStateFlow()

    @Volatile
    private var context: Context? = null

    fun attach(appContext: Context) {
        context = appContext.applicationContext
        ensureChannel()
    }

    fun ensureChannel() {
        val ctx = context ?: return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Background jobs",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Downloads and patching that keep running in the background"
                setShowBadge(false)
            }
        )
    }

    fun begin(appContext: Context, id: String, title: String, detail: String = "") {
        context = appContext.applicationContext
        ensureChannel()
        put(Job(id, title, detail))
        context?.let { JobService.start(it) }
    }

    fun progress(appContext: Context, id: String, done: Long, total: Long, detail: String? = null) {
        context = appContext.applicationContext
        val current = _jobs.value[id] ?: return
        put(current.copy(done = done, total = total, detail = detail ?: current.detail))
    }

    fun detail(appContext: Context, id: String, detail: String) {
        context = appContext.applicationContext
        val current = _jobs.value[id] ?: return
        put(current.copy(detail = detail))
    }

    fun finish(
        appContext: Context,
        id: String,
        message: String = "",
        ok: Boolean = true,
        apkPath: String? = null,
    ) {
        context = appContext.applicationContext
        val current = _jobs.value[id] ?: return
        put(
            current.copy(
                state = if (ok) State.DONE else State.FAILED,
                message = message,
                apkPath = apkPath ?: current.apkPath,
            )
        )
        if (_jobs.value.values.none { it.running }) {
            context?.let { JobService.stop(it) }
        }
    }

    fun clear(id: String) {
        _jobs.value = _jobs.value - id
        context?.let { ctx ->
            NotificationManagerCompat.from(ctx).cancel(id.hashCode())
        }
    }

    /** Drops finished jobs from the in-app strip; the notifications stay. */
    fun dismissFinished() {
        _jobs.value = _jobs.value.filterValues { it.running }
    }

    /** Notification id of a job, shared with the service's foreground slot. */
    fun idOf(job: Job): Int = job.id.hashCode()

    fun current(id: String): Job? = _jobs.value[id]

    fun has(id: String): Boolean = _jobs.value.containsKey(id)

    private fun put(job: Job) {
        _jobs.value = _jobs.value + (job.id to job)
        val ctx = context ?: return
        NotificationManagerCompat.from(ctx).notify(
            idOf(job),
            notificationFor(ctx, job),
        )
    }

    /**
     * The job's notification. The service promotes this exact notification to
     * the foreground one instead of posting a summary of its own, so a running
     * job is a single notification on the shade and not two.
     */
    fun notificationFor(ctx: Context, job: Job): android.app.Notification =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.title)
            .setContentText(job.detail.ifBlank { job.message })
            .setOngoing(job.running)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    ctx,
                    idOf(job),
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .apply {
                when {
                    job.running && job.percent >= 0 -> setProgress(100, job.percent, false)
                    job.running -> setProgress(0, 0, true)
                }
                if (!job.running) {
                    setAutoCancel(true)
                    // InboxStyle shows the whole result line; the collapsed text
                    // above already carries it.
                    setStyle(
                        NotificationCompat.InboxStyle().setSummaryText(
                            job.message.ifBlank { job.detail }
                        )
                    )
                    // Finished jobs must not linger. A finished notification is
                    // never ongoing, so Android would keep it in the shade until
                    // the user swiped it away - the app then looked like it was
                    // still busy after every download and patch.
                    setTimeoutAfter(FINISHED_TIMEOUT_MS)
                }
            }
            .build()
}