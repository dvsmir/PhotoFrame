package app.framealt.send

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.framealt.R
import app.framealt.ui.MainActivity

/**
 * The one notification channel, `send_progress`, and everything posted to it.
 * Copy is `Spec/00 - Initial/03 - UX.md` §8: one notification per batch, never per photo.
 */
class SendNotifications(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Sending photos", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while photos are sent to your frame"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Shown by the foreground service while a drain runs. */
    fun progress(frameName: String, done: Int, total: Int): Notification =
        base()
            .setContentTitle("Sending to $frameName")
            .setContentText(if (total > 0) "${minOf(done + 1, total)} of $total" else "Connecting…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancelIntent())
            .build()

    fun waiting(count: Int) = post(
        ID_STATUS,
        base()
            .setContentTitle(if (count == 1) "1 photo waiting" else "$count photos waiting")
            .setContentText("Waiting for your home Wi-Fi.")
            .setAutoCancel(true)
            .build(),
    )

    fun finished(frameName: String, sent: Int, failed: Int) {
        val title = when {
            failed == 0 && sent == 1 -> "1 photo sent to $frameName"
            failed == 0 -> "$sent photos sent to $frameName"
            else -> "$sent sent, $failed failed"
        }
        post(ID_STATUS, base().setContentTitle(title).setAutoCancel(true).build())
    }

    fun stopped(message: String) = post(
        ID_STATUS,
        base().setContentTitle("Sending stopped").setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true).build(),
    )

    fun clearStatus() = NotificationManagerCompat.from(context).cancel(ID_STATUS)

    private fun base() = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(openApp())
        .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, CancelSendReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** Without the permission sends still work; only the notification is missing (UX §7). */
    private fun post(id: Int, notification: Notification) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        const val CHANNEL = "send_progress"
        const val ID_PROGRESS = 1
        const val ID_STATUS = 2
    }
}
