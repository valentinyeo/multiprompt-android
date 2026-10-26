package dev.multiprompt.companion.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.R
import dev.multiprompt.companion.data.SessionReadStore

object ReplyNotifier {
    const val CHANNEL_ID = "replies"
    const val GROUP_KEY = "dev.multiprompt.companion.REPLIES"
    const val EXTRA_HOST_ID = "reply_host_id"
    const val EXTRA_SESSION_NAME = "reply_session_name"
    private const val SESSION_NOTIFICATION_ID = 1
    private const val SUMMARY_ID = 2

    fun initialize(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Replies", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun openIntent(context: Context, hostId: String, sessionName: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_HOST_ID, hostId)
            putExtra(EXTRA_SESSION_NAME, sessionName)
        }

    fun activeKeys(context: Context): Set<String> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.notification.group == GROUP_KEY && it.id == SESSION_NOTIFICATION_ID }
            .mapNotNullTo(linkedSetOf()) { it.tag }

    fun show(context: Context, hostId: String, sessionName: String, title: String, text: String) {
        initialize(context)
        val key = SessionReadStore.key(hostId, sessionName)
        val pending = PendingIntent.getActivity(
            context,
            key.hashCode(),
            openIntent(context, hostId, sessionName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Multiprompt")
            .setContentText("New replies")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(key, SESSION_NOTIFICATION_ID, reply)
        manager.notify(SUMMARY_ID, summary)
    }

    fun cancel(context: Context, key: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(key, SESSION_NOTIFICATION_ID)
        if (activeKeys(context).isEmpty()) manager.cancel(SUMMARY_ID)
    }
}
