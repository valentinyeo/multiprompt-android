package dev.multiprompt.companion.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import dev.multiprompt.companion.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check(force: Boolean = false) {
        if (BuildConfig.DEBUG) {
            _state.value = UpdateState.Current("${BuildConfig.VERSION_NAME} (debug)")
            return
        }
        if (_state.value == UpdateState.Checking || _state.value is UpdateState.Downloading) return
        if (!force && !checkIsDue()) return

        scope.launch {
            _state.value = UpdateState.Checking
            try {
                val release = fetchManifest()
                preferences.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                _state.value = if (release.versionCode > BuildConfig.VERSION_CODE.toLong()) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.Current(BuildConfig.VERSION_NAME, release.notes, release.history)
                }
            } catch (throwable: Throwable) {
                _state.value = UpdateState.Failed(throwable.userMessage())
            }
        }
    }

    fun install(release: UpdateRelease) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.PermissionRequired(release)
            return
        }
        scope.launch {
            try {
                val apk = download(release)
                verifyApk(apk, release)
                _state.value = UpdateState.Installing
                stageInstall(apk)
            } catch (throwable: Throwable) {
                _state.value = UpdateState.Failed(throwable.userMessage(), release)
            }
        }
    }

    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${context.packageName}".toUri(),
    )

    fun resumeAfterPermission(release: UpdateRelease) {
        if (context.packageManager.canRequestPackageInstalls()) install(release)
        else _state.value = UpdateState.Failed("Allow app installs to use one-tap updates", release)
    }

    private fun checkIsDue(): Boolean {
        val last = preferences.getLong(KEY_LAST_CHECK, 0)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    private fun fetchManifest(): UpdateRelease {
        val connection = openHttps(BuildConfig.UPDATE_MANIFEST_URL)
        return connection.useConnection { input ->
            val raw = input.bufferedReader().use { it.readTextLimited(MAX_MANIFEST_BYTES) }
            UpdateRelease.parse(raw)
        }
    }

    private fun download(release: UpdateRelease): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "multiprompt-companion.apk.partial")
        val target = File(directory, "multiprompt-companion.apk")
        partial.delete()
        target.delete()

        val connection = openHttps(release.apkUrl)
        connection.useConnection { input ->
            val declaredLength = connection.contentLengthLong
            if (declaredLength > 0 && declaredLength != release.sizeBytes) {
                error("The release server returned an unexpected APK size")
            }
            partial.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= release.sizeBytes && copied <= UpdateRelease.MAX_APK_BYTES) {
                        "The downloaded APK is larger than the signed manifest"
                    }
                    output.write(buffer, 0, count)
                    _state.value = UpdateState.Downloading(
                        release,
                        (copied.toFloat() / release.sizeBytes.toFloat()).coerceIn(0f, 1f),
                    )
                }
            }
        }
        require(partial.length() == release.sizeBytes) { "The APK download was incomplete" }
        require(partial.renameTo(target)) { "Could not finalize the APK download" }
        return target
    }

    private fun verifyApk(apk: File, release: UpdateRelease) {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualHash == release.sha256) { "The APK checksum does not match the release manifest" }

        val packageManager = context.packageManager
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                },
            )
        } ?: error("The downloaded file is not a valid APK")
        require(archive.packageName == context.packageName) { "The APK belongs to a different app" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        require(archiveVersion == release.versionCode) { "The APK version does not match the manifest" }
        require(archiveVersion > BuildConfig.VERSION_CODE.toLong()) { "The APK is not newer than this app" }

        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                context.packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                },
            )
        }
        val currentSigners = signerDigests(installed)
        val updateSigners = signerDigests(archive)
        require(currentSigners.isNotEmpty() && currentSigners == updateSigners) {
            "The APK signing certificate does not match the installed app"
        }
    }

    private fun stageInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("multiprompt-companion.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun openHttps(rawUrl: String): HttpURLConnection {
        val url = URL(rawUrl)
        require(url.protocol == "https") { "Updates must use HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "multiprompt-android/${BuildConfig.VERSION_NAME}")
        connection.connect()
        require(connection.url.protocol == "https") { "The update redirected to an insecure URL" }
        require(connection.responseCode in 200..299) { "Update server returned HTTP ${connection.responseCode}" }
        return connection
    }

    private fun certificateDigest(signature: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.map(::certificateDigest).toSet()
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (java.io.InputStream) -> T): T =
        try {
            inputStream.use(block)
        } finally {
            disconnect()
        }

    private fun java.io.BufferedReader.readTextLimited(limit: Int): String {
        val result = StringBuilder()
        val buffer = CharArray(2048)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(result.length + count <= limit) { "Update manifest is too large" }
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private fun Throwable.userMessage(): String {
        Log.e(TAG, "Update failed", this)
        return message?.takeIf(String::isNotBlank) ?: "Update failed"
    }

    private companion object {
        const val TAG = "MultipromptUpdate"
        const val KEY_LAST_CHECK = "last_check_ms"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val NETWORK_TIMEOUT_MS = 30_000
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
