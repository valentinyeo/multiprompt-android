package dev.multiprompt.companion.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.multiprompt.companion.BuildConfig
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object UpdateNotifier {
    private const val CHANNEL_ID = "app_updates"
    private const val WORK_NAME = "check_for_android_updates"

    fun initialize(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun show(context: Context, release: UpdateRelease) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("multiprompt ${release.versionName} is ready")
            .setContentText("Tap to update the Android app")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(release.versionCode.toInt(), notification)
    }
}

class UpdateCheckWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (BuildConfig.DEBUG) return Result.success()
        return runCatching {
            val release = fetchRelease()
            val preferences = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
            val lastNotified = preferences.getLong("last_notified_version", 0)
            if (release.versionCode > BuildConfig.VERSION_CODE && release.versionCode > lastNotified) {
                UpdateNotifier.show(applicationContext, release)
                preferences.edit().putLong("last_notified_version", release.versionCode).apply()
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun fetchRelease(): UpdateRelease {
        val connection = URL(BuildConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "multiprompt-android/${BuildConfig.VERSION_NAME}")
        return try {
            connection.connect()
            require(connection.url.protocol == "https")
            require(connection.responseCode in 200..299)
            val raw = connection.inputStream.bufferedReader().use { it.readText().take(64 * 1024) }
            UpdateRelease.parse(raw)
        } finally {
            connection.disconnect()
        }
    }
}
