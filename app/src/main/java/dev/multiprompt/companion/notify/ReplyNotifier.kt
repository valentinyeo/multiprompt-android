package dev.multiprompt.companion.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.R
import java.util.concurrent.atomic.AtomicInteger

object ReplyNotifier {
    const val CHANNEL_ID = "replies"
    const val GROUP_KEY = "dev.multiprompt.companion.REPLIES"
    const val EXTRA_HOST_ID = "reply_host_id"
    const val EXTRA_SESSION_NAME = "reply_session_name"
    private val ids = AtomicInteger(2000)

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

    fun show(context: Context, hostId: String, sessionName: String, title: String, text: String) {
        initialize(context)
        val id = ids.incrementAndGet()
        val pending = PendingIntent.getActivity(
            context,
            id,
            openIntent(context, hostId, sessionName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
