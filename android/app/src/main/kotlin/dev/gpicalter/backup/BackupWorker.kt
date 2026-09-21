package dev.gpicalter.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.gpicalter.di.AppContainer
import java.util.concurrent.TimeUnit

private const val CHANNEL = "backup"
private const val NOTIF_ID = 2

/** Marks a run the user asked for, as opposed to the periodic one. */
internal const val KEY_MANUAL = "manual"

/**
 * Runs a backup pass.
 *
 * WorkManager rather than a bare foreground service, because backup is **bounded, deferrable**
 * work: it should wait for Wi-Fi, survive reboots, and stop when there is nothing to send. That is
 * the opposite of the HTTP server, which is unbounded and must never be deferred -- which is why
 * the server uses a `specialUse` foreground service and this does not.
 *
 * The same distinction decides the foreground service type here: `dataSync` carries a six-hour
 * cumulative cap on Android 15+, which would be fatal for a permanent server but is entirely
 * appropriate for a backup pass that is supposed to finish.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)

        // The switch governs *automatic* backup only. A run the user explicitly asked for must
        // always happen: silently succeeding while doing nothing is indistinguishable from
        // working, which is the worst way for this to fail.
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && !container.prefs.backupEnabled) return Result.success()

        // Uploading a camera roll is long and visible work, so it runs in the foreground rather
        // than risking a mid-transfer kill.
        runCatching { setForeground(foregroundInfo("Backing up…")) }

        // A queued explicit selection takes precedence and is consumed exactly once, so a retry
        // after a network failure does not silently turn into a full-library run.
        val manualIds = container.prefs.pendingManualIds
            .mapNotNull { it.toLongOrNull() }
            .toSet()
            .ifEmpty { null }

        val engine = BackupEngine(applicationContext, container.db, container.api, container.prefs)
        val outcome = engine.run(onlyIds = manualIds)
        if (manualIds != null) container.prefs.pendingManualIds = emptySet()

        return when {
            // Retry covers the ordinary case of the server being briefly unreachable; WorkManager
            // applies its own backoff, so a phone out of Wi-Fi range does not spin.
            outcome.failed > 0 && outcome.uploaded == 0 -> Result.retry()
            else -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Backing up…")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Photo backup", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val notification: Notification = Notification.Builder(applicationContext, CHANNEL)
            .setContentTitle("gpicAlter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }
}

object BackupScheduler {

    private const val PERIODIC = "gpic-backup-periodic"
    private const val ONE_SHOT = "gpic-backup-now"

    /**
     * @param wifiOnly metered connections are excluded, because a camera roll is measured in
     *   gigabytes and nobody wants to discover that over cellular
     */
    fun schedulePeriodic(context: Context, wifiOnly: Boolean, requireCharging: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(requireCharging)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            // KEEP, so toggling the switch or reopening the app does not reset the interval and
            // starve the job of ever actually firing.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Queues an explicit selection, then runs. */
    fun runSelection(context: Context, ids: Set<Long>, wifiOnly: Boolean) {
        dev.gpicalter.core.Prefs(context).pendingManualIds = ids.map { it.toString() }.toSet()
        runNow(context, wifiOnly)
    }

    /** A user-initiated run: still waits for a network, but not for charging or the switch. */
    fun runNow(context: Context, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_MANUAL to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops a run in progress without touching the automatic schedule. */
    fun stopRun(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT)
    }
}
