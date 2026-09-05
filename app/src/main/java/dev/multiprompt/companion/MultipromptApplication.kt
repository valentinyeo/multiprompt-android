package dev.multiprompt.companion

import android.app.Application
import dev.multiprompt.companion.data.HostStore
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.SessionCacheStore
import dev.multiprompt.companion.data.DissolvedSessionStore
import dev.multiprompt.companion.data.CrashReportStore
import dev.multiprompt.companion.data.WorkspaceStore
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.update.UpdateManager
import dev.multiprompt.companion.upload.ScreencastUploader
import dev.multiprompt.companion.update.UpdateNotifier

class MultipromptApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        crashReportStore.install()
        UpdateNotifier.initialize(this)
    }

    val hostStore by lazy { HostStore(this) }
    val sessionReadStore by lazy { SessionReadStore(this) }
    val sessionCacheStore by lazy { SessionCacheStore(this) }
    val dissolvedSessionStore by lazy { DissolvedSessionStore(this) }
    val crashReportStore by lazy { CrashReportStore(this) }
    val workspaceStore by lazy { WorkspaceStore(this) }
    val secretStore by lazy { SecretStore(this) }
    val sshRepository by lazy { SshRepository(secretStore) }
    val deepgramDictation by lazy { DeepgramDictation(this, secretStore) }
    val updateManager by lazy { UpdateManager(this) }
    val screencastUploader by lazy { ScreencastUploader(this, secretStore) }
}
