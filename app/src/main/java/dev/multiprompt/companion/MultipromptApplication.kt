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
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MultipromptApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android 16 advertises Ed25519 from AndroidKeyStore only, which rejects software keys, and
        // no platform provider can verify an ssh-ed25519 host key (sshlib's own provider has no
        // Signature service). Replace the stripped platform "BC" with full BouncyCastle, first in
        // line, so Ed25519 KeyFactory + Signature resolve to it. Same fix ConnectBot uses.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
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
