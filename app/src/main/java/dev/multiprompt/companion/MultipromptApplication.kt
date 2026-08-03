package dev.multiprompt.companion

import android.app.Application
import dev.multiprompt.companion.data.HostStore
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.dictation.DeepgramDictation
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.update.UpdateManager

class MultipromptApplication : Application() {
    val hostStore by lazy { HostStore(this) }
    val sessionReadStore by lazy { SessionReadStore(this) }
    val secretStore by lazy { SecretStore(this) }
    val sshRepository by lazy { SshRepository(secretStore) }
    val deepgramDictation by lazy { DeepgramDictation(this, secretStore) }
    val updateManager by lazy { UpdateManager(this) }
}
