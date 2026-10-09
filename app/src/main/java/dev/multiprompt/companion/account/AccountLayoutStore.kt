package dev.multiprompt.companion.account

import android.content.Context
import org.json.JSONObject

class AccountLayoutStore(context: Context) {
    private val preferences = context.getSharedPreferences("account_layout", Context.MODE_PRIVATE)

    fun load(account: String?): PendingLayout? = account?.let {
        preferences.getString("layout::$it", null)?.let { raw -> runCatching { PendingLayout.parse(JSONObject(raw)) }.getOrNull() }
    }

    fun save(account: String, state: PendingLayout) {
        check(preferences.edit().putString("layout::$account", state.json().toString()).commit()) { "Could not save pending account edits" }
    }

    fun hostOverrides(): Map<Int, String> = (0..2).mapNotNull { slot ->
        preferences.getString("host::$slot", null)?.let { slot to it }
    }.toMap()

    fun setHost(slot: Int, id: String?) {
        require(slot in 0..2)
        check(preferences.edit().putString("host::$slot", id).commit())
    }
}
