package com.shawnbugs.kaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

/*
 * 导出放在前台服务里跑：切到别的 App、锁屏也不会被系统杀掉，通知栏显示进度，可以点「停止」。
 * 真正干活的是 Studio.runBatch。
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Studio.stopExport()
            return START_NOT_STICKY
        }
        val job = Studio.pendingJob
        Studio.pendingJob = null
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTI_PROGRESS, progressNotification(0f, "准备导出…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job == null) {
            stopSelfNow()
            return START_NOT_STICKY
        }
        thread(name = "export") {
            var lastPost = 0L
            val nm = getSystemService(NotificationManager::class.java)
            val summary = try {
                Studio.runBatch(job) { frac, text ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPost >= 500) {   // 通知刷太快系统会丢
                        lastPost = now
                        nm.notify(NOTI_PROGRESS, progressNotification(frac, text))
                    }
                }
            } catch (e: Exception) {
                Studio.log("导出出错：${e.message ?: e}")
                "导出出错：${e.message ?: e}"
            }
            nm.notify(NOTI_DONE, doneNotification(summary))
            stopSelfNow()
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(frac: Float, text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ExportService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("正在导出")
            .setContentText(text)
            .setProgress(1000, (frac * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, "停止", stop)
            .build()
    }

    private fun doneNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("K剪音")
            .setContentText(text)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    companion object {
        private const val CHANNEL = "export"
        private const val NOTI_PROGRESS = 1
        private const val NOTI_DONE = 2
        private const val ACTION_STOP = "com.shawnbugs.kaudio.STOP_EXPORT"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "导出进度", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "导出时显示进度，完成后提示结果"
                        setShowBadge(false)
                    }
                )
            }
        }
    }
}
