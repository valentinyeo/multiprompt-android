package dev.multiprompt.companion.account

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class AccountLayoutStore(context: Context) {
    private val preferences = context.getSharedPreferences("account_layout", Context.MODE_PRIVATE)

    fun load(account: String?): PendingLayout? = account?.let {
        preferences.getString("layout::$it", null)?.let { raw -> runCatching { PendingLayout.parse(JSONObject(raw)) }.getOrNull() }
    }

    fun save(account: String, state: PendingLayout) {
        try {
            saveAsync(account, state).get()
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
    }

    fun saveAsync(account: String, state: PendingLayout): Future<Unit> = writer.submit<Unit> {
        check(preferences.edit().putString("layout::$account", state.json().toString()).commit()) { "Could not save pending account edits" }
    }

    fun archived(account: String?): List<ArchivedWorkspace> = account?.let {
        preferences.getString("archived::$it", null)?.let { raw ->
            runCatching {
                val values = JSONArray(raw)
                (0 until values.length()).map { index -> ArchivedWorkspace.parse(values.getJSONObject(index)) }
            }.getOrNull()
        }
    }.orEmpty()

    fun saveArchived(account: String, archived: List<ArchivedWorkspace>) {
        check(preferences.edit().putString("archived::$account", JSONArray(archived.map { it.json() }).toString()).commit()) {
            "Could not save archived workspaces"
        }
    }

    fun hostOverrides(): Map<Int, String> = (0..2).mapNotNull { slot ->
        preferences.getString("host::$slot", null)?.let { slot to it }
    }.toMap()

    fun setHost(slot: Int, id: String?) {
        require(slot in 0..2)
        check(preferences.edit().putString("host::$slot", id).commit())
    }
    private companion object {
        val writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "account-layout-writes").apply { isDaemon = true }
        }
    }
}
