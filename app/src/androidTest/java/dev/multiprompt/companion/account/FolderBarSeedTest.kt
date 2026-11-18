package dev.multiprompt.companion.account

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.data.WorkspaceStore
import dev.multiprompt.companion.model.Workspace
import org.json.JSONArray
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
        val store = WorkspaceStore(context)
        for (index in 1..12) {
            val name = "Folder %02d".format(index)
            store.upsert(Workspace("folder-$index", name, host.getString("id"), "/folder-bar/$index"))
        }
        store.saveSelectedSplitId(null)
        SessionReadStore(context).setAllSplitOnRight(false)
        check(store.load().count { it.id.startsWith("folder-") } == 12)
    }
}
