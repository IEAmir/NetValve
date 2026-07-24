package dev.netvalve.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.netvalve.MainActivity
import dev.netvalve.R
import dev.netvalve.stats.StatsSnapshot
import dev.netvalve.utils.Format

/** Builds the ongoing foreground-service notification and its channel. */
object NotificationHelper {
    const val CHANNEL_ID = "netvalve_tunnel"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW, // silent, no sound/vibration
            ).apply {
                description = context.getString(R.string.vpn_channel_desc)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, status: VpnStatus, snapshot: StatsSnapshot): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, NetValveVpnService::class.java).setAction(VpnActions.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseResume = if (status.state == TunnelState.PAUSED) {
            VpnActions.ACTION_RESUME to context.getString(R.string.action_pause_all)
        } else {
            VpnActions.ACTION_PAUSE to context.getString(R.string.action_pause_all)
        }
        val pauseIntent = PendingIntent.getService(
            context, 2,
            Intent(context, NetValveVpnService::class.java).setAction(pauseResume.first),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = context.getString(
            R.string.vpn_notification_text,
            status.controlledAppCount,
            Format.rate(snapshot.liveDownloadBps),
            Format.rate(snapshot.liveUploadBps),
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, context.getString(R.string.action_stop), stopIntent)
            .addAction(0, pauseResume.second, pauseIntent)
            .build()
    }
}
