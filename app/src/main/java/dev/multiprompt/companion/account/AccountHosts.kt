package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

data class AccountHost(
    val slot: Int,
    val name: String,
    val hostname: String,
    val port: Int,
    val user: String,
    val hostKeys: List<String> = emptyList(),
) {
    fun json(): JSONObject = JSONObject().put("slot", slot).put("name", name).put("hostname", hostname)
        .put("port", port).put("user", user).apply { if (hostKeys.isNotEmpty()) put("host_keys", JSONArray(hostKeys)) }
}

object AccountHosts {
    data class Merge(val profiles: List<HostProfile>, val overrides: Map<Int, String>)

    fun parse(value: Any?): List<AccountHost> {
        if (value !is JSONArray || value.length() > 3) return emptyList()
        val slots = mutableSetOf<Int>()
        return (0 until value.length()).mapNotNull { index ->
            runCatching {
                val item = value.getJSONObject(index)
                val required = setOf("slot", "name", "hostname", "port", "user")
                val keys = item.keys().asSequence().toSet()
                require(keys.containsAll(required) && (keys - required).all { it == "host_keys" })
                fun integer(key: String, range: IntRange): Int {
                    val number = item.get(key)
                    require(number is Number && number.toInt() in range &&
                        number.toString().toBigDecimalOrNull()?.compareTo(number.toInt().toBigDecimal()) == 0)
                    return number.toInt()
                }
                fun text(key: String): String = (item.get(key) as? String) ?: error("Invalid host text")
                val slot = integer("slot", 0..2)
                val name = text("name")
                require(name.isNotEmpty() && name.codePointCount(0, name.length) <= 32 && name.none { Character.isISOControl(it) } && Charsets.UTF_8.newEncoder().canEncode(name))
                val hostname = text("hostname")
                require(Regex("[A-Za-z0-9.-]{1,253}|[0-9A-Fa-f:]{2,45}").matches(hostname))
                val user = text("user")
                require(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,31}").matches(user))
                val port = integer("port", 1..65535)
                val hostKeys = if (item.has("host_keys")) {
                    val array = item.getJSONArray("host_keys")
                    require(array.length() <= 4)
                    (0 until array.length()).map { key ->
                        val raw = array.get(key)
                        require(raw is String && validHostKey(raw))
                        raw
                    }.also { require(it.distinct().size == it.size) }
                } else emptyList()
                require(slots.add(slot))
                AccountHost(slot, name, hostname, port, user, hostKeys)
            }.getOrNull()
        }.sortedBy { it.slot }
    }

    fun validHostKey(key: String): Boolean = key.length <= 16384 &&
        Regex("(ssh-ed25519|ecdsa-sha2-nistp256|ecdsa-sha2-nistp384|ecdsa-sha2-nistp521|ssh-rsa) [A-Za-z0-9+/]+={0,2}").matches(key) &&
        runCatching { Base64.getDecoder().decode(key.substringAfter(' ')).isNotEmpty() }.getOrDefault(false)

    fun merge(remote: List<AccountHost>, profiles: List<HostProfile>, overrides: Map<Int, String>): Merge {
        val next = profiles.toMutableList()
        val mapping = AccountHostMap.resolve(profiles, overrides).toMutableMap()
        val nextOverrides = overrides.toMutableMap()
        remote.forEach { host ->
            if (host.slot == 0 && host.user != "valentin") return@forEach
            val profile = mapping[host.slot] ?: run {
                val matched = next.firstOrNull { it.hostname.equals(host.hostname, ignoreCase = true) && it.port == host.port && it.username == host.user }
                    ?: run {
                        val firstKey = host.hostKeys.firstOrNull()
                        HostProfile(label = host.name, hostname = host.hostname, port = host.port, username = host.user,
                            hostKeys = host.hostKeys,
                            hostKeyType = firstKey?.substringBefore(' '),
                            hostKeyFingerprint = firstKey?.let {
                                "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(it.substringAfter(' '))))
                            }).also(next::add)
                    }
                if (mapping.values.any { it.id == matched.id }) return@forEach
                matched
            }
            val renamed = profile.copy(label = host.name)
            next[next.indexOfFirst { it.id == profile.id }] = renamed
            mapping[host.slot] = renamed
            nextOverrides[host.slot] = profile.id
        }
        return Merge(next, nextOverrides)
    }

    fun outgoing(mapping: Map<Int, HostProfile>): List<AccountHost> = parse(JSONArray(mapping.toSortedMap().map { (slot, host) ->
        AccountHost(slot, host.label, host.hostname, host.port, host.username, host.hostKeys.filter(::validHostKey).distinct().take(4)).json()
    }))

    fun publish(remote: List<AccountHost>?, mapping: Map<Int, HostProfile>): List<AccountHost> =
        (outgoing(mapping).associateBy { it.slot } + remote.orEmpty().associateBy { it.slot }).values.sortedBy { it.slot }
}
