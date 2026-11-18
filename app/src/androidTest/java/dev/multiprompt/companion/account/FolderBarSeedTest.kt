package dev.multiprompt.companion.account

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.WorkspaceStore
import dev.multiprompt.companion.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class FolderBarSeedTest {
    @Test fun seedsOverflowingFolders() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mode") == "folder-bar-seed")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.multiprompt.companion.debug")
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val hosts = JSONArray(context.getSharedPreferences("hosts", Context.MODE_PRIVATE).getString("profiles_json", "[]"))
        check(hosts.length() == 1)
        val host = hosts.getJSONObject(0)
        check(host.getString("hostname") == "10.0.2.2" && host.getInt("port") == 2222)
        check(host.getString("hostKeyFingerprint").startsWith("SHA256:"))
        val secrets = SecretStore(context)
        check(!secrets.contains(AccountAuth.TOKENS))
        val preferences = context.getSharedPreferences("account_layout", Context.MODE_PRIVATE)
        check(preferences.all.isEmpty())
        val account = "isolated-folder-bar-fixture"
        val login = JSONObject()
            .put("account_id", account)
            .put("device_id", "github-emulator")
            .put("access_token", "offline-fixture")
            .put("refresh_token", "offline-fixture")
            .put("expires_at", System.currentTimeMillis() + 3_600_000L)
        secrets.put(AccountAuth.TOKENS, login.toString().toByteArray())
        val folders = (1..9).map { "Folder %02d".format(it) }
        val sessions = listOf("verify-session", "cl-long-chat") + (0..39).map { "row-%02d".format(it) }
        val layout = AccountLayout(
            revision = 0,
            workspaces = folders,
            tabs = sessions.map { AccountTab(name = it, session = it, host = 1, workspace = folders.last()) },
            settings = mapOf("all" to true),
        ).validate()
        AccountLayoutStore(context).save(account, PendingLayout(layout))
        check(preferences.edit().putString("host::1", host.getString("id")).commit())
        WorkspaceStore(context).saveSelectedSplitId(null)
        SessionReadStore(context).setAllSplitOnRight(false)
        check(AccountLayoutStore(context).load(account)?.edited?.workspaces == folders)
    }
}
