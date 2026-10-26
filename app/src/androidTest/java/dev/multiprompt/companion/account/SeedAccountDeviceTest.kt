package dev.multiprompt.companion.account

import androidx.test.platform.app.InstrumentationRegistry
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
        val login = JSONObject()
            .put("refresh_token", refresh)
            .put("access_token", "A".repeat(43))
            .put("expires_at", 0L)
            .put("account_id", arguments.getString("account_id"))
            .put("device_id", arguments.getString("device_id"))
        SecretStore(InstrumentationRegistry.getInstrumentation().targetContext)
            .put(AccountAuth.TOKENS, login.toString().toByteArray())
    }
}
