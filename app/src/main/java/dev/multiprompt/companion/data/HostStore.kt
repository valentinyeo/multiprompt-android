package dev.multiprompt.companion.data

import android.content.Context
import dev.multiprompt.companion.model.HostProfile
import org.json.JSONArray
import org.json.JSONObject

class HostStore(context: Context) {
    private val preferences = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)

    fun load(): List<HostProfile> {
        val raw = preferences.getString(KEY_HOSTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        HostProfile(
                            id = item.getString("id"),
                            label = item.getString("label"),
                            hostname = item.getString("hostname"),
                            port = item.optInt("port", 22),
                            username = item.getString("username"),
                            keySecretId = item.getString("keySecretId"),
                            passphraseSecretId = item.optString("passphraseSecretId").ifBlank { null },
                            hostKeyType = item.optString("hostKeyType").ifBlank { null },
                            hostKeyFingerprint = item.optString("hostKeyFingerprint").ifBlank { null },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(profile: HostProfile) {
        val next = load().filterNot { it.id == profile.id } + profile
        save(next.sortedBy { it.label.lowercase() })
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    private fun save(hosts: List<HostProfile>) {
        val array = JSONArray()
        hosts.forEach { host ->
            array.put(
                JSONObject()
                    .put("id", host.id)
                    .put("label", host.label)
                    .put("hostname", host.hostname)
                    .put("port", host.port)
                    .put("username", host.username)
                    .put("keySecretId", host.keySecretId)
                    .put("passphraseSecretId", host.passphraseSecretId ?: "")
                    .put("hostKeyType", host.hostKeyType ?: "")
                    .put("hostKeyFingerprint", host.hostKeyFingerprint ?: ""),
            )
        }
        preferences.edit().putString(KEY_HOSTS, array.toString()).apply()
    }

    private companion object {
        const val KEY_HOSTS = "profiles_json"
    }
}

