package dev.multiprompt.companion.account

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class FavoriteDotSeedTest {
    @Test fun seedsOfflineFavoriteLayout() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("mode") == "favorite-dot-seed")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.multiprompt.companion.debug") { "Debug package required" }
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) { "Emulator required" }
        val hosts = JSONArray(context.getSharedPreferences("hosts", Context.MODE_PRIVATE).getString("profiles_json", "[]"))
        check(hosts.length() == 1) { "Exactly one fixture host required" }
        val host = hosts.getJSONObject(0)
        check(host.getString("label") == "Verify" && host.getString("hostname") == "10.0.2.2" && host.getInt("port") == 2222 && host.getString("username") == "verifyssh") { "Unexpected fixture host" }
        check(host.getString("id").isNotEmpty() && host.getString("keySecretId").isNotEmpty()) { "Fixture host identity missing" }
        check(host.getString("hostKeyType") == "ssh-ed25519" && host.getString("hostKeyFingerprint").startsWith("SHA256:")) { "Fixture host is not trusted" }
        val secrets = SecretStore(context)
        check(!secrets.contains(AccountAuth.TOKENS)) { "Account session already present" }
        val preferences = context.getSharedPreferences("account_layout", Context.MODE_PRIVATE)
        check(preferences.all.isEmpty()) { "Layout store must be empty" }
        val login = JSONObject()
            .put("account_id", ACCOUNT)
            .put("device_id", "github-emulator")
            .put("access_token", "offline-fixture")
            .put("refresh_token", "offline-fixture")
            .put("expires_at", System.currentTimeMillis() + 3_600_000L)
        secrets.put(AccountAuth.TOKENS, login.toString().toByteArray())
        val layout = AccountLayout(
            revision = 0,
            workspaces = listOf("Fixture"),
            tabs = listOf(
                AccountTab(name = "verify-session", session = "verify-session", host = 1, workspace = "Fixture", favorite = true),
                AccountTab(name = "verify-keep", session = "verify-keep", host = 1, workspace = "Fixture", favorite = false),
            ),
            settings = mapOf("favorites" to true, "all" to true),
        ).validate()
        AccountLayoutStore(context).save(ACCOUNT, PendingLayout(layout))
        check(preferences.edit().putString("host::1", host.getString("id")).commit()) { "Fixture host was not mapped" }
        val loaded = AccountLayoutStore(context).load(ACCOUNT)
        check(loaded?.edited?.tabs?.single { it.session == "verify-session" }?.favorite == true) { "Starred chat was not saved" }
        check(loaded?.edited?.tabs?.single { it.session == "verify-keep" }?.favorite == false) { "Plain chat was starred" }
        check(preferences.getString("host::1", null) == host.getString("id")) { "Fixture host mapping was lost" }
    }

    private companion object {
        const val ACCOUNT = "isolated-debug-fixture"
    }
}
