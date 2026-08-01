package dev.multiprompt.companion

import android.app.Application
import dev.multiprompt.companion.data.HostStore
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.update.UpdateManager

class MultipromptApplication : Application() {
    val hostStore by lazy { HostStore(this) }
    val secretStore by lazy { SecretStore(this) }
    val sshRepository by lazy { SshRepository(secretStore) }
    val updateManager by lazy { UpdateManager(this) }
}

