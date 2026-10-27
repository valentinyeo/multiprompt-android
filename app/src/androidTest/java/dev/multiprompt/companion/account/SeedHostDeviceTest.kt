package dev.multiprompt.companion.account

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.data.HostStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import org.junit.Test

class SeedHostDeviceTest {
    @Test fun importsCliHostFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val host = HostProfile(
            label = arguments.getString("host_label") ?: "Verify",
            hostname = arguments.getString("ssh_host") ?: "10.0.2.2",
            port = arguments.getString("ssh_port")?.toInt() ?: 2222,
            username = arguments.getString("ssh_user") ?: "verifyssh",
        )
        val key = requireNotNull(arguments.getString("ssh_private_key"))
        SecretStore(context).put(host.keySecretId, Base64.decode(key, Base64.DEFAULT))
        HostStore(context).upsert(host)
        val hosts = context.getSharedPreferences("hosts", android.content.Context.MODE_PRIVATE)
        check(hosts.edit().putString("profiles_json", hosts.getString("profiles_json", null)).commit())
        check(HostStore(context).load().any { it.id == host.id })
    }
}
