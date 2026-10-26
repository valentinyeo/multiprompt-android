package dev.multiprompt.companion.account

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.data.HostStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class SeedAccountDeviceTest {
    @Test
    fun seedsDeviceLoginFromInstrumentationArguments() {
        val arguments = InstrumentationRegistry.getArguments()
        val refresh = arguments.getString("refresh_token")
        assumeTrue(refresh != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = SecretStore(context)
        val login = JSONObject()
            .put("refresh_token", refresh)
            .put("access_token", "A".repeat(43))
            .put("expires_at", 0L)
            .put("account_id", arguments.getString("account_id"))
            .put("device_id", arguments.getString("device_id"))
        secrets.put(AccountAuth.TOKENS, login.toString().toByteArray())
        val privateKey = arguments.getString("ssh_private_key")
        val hostKey = arguments.getString("ssh_host_key")
        if (privateKey != null && hostKey != null) {
            val profile = HostProfile(
                label = "Hetzner",
                hostname = arguments.getString("ssh_host") ?: "10.0.2.2",
                port = arguments.getString("ssh_port")?.toInt() ?: 2222,
                username = "valentin",
                hostKeys = listOf("ssh-ed25519 $hostKey"),
            )
            secrets.put(profile.keySecretId, Base64.decode(privateKey, Base64.DEFAULT))
            HostStore(context).upsert(profile)
        }
    }
}
