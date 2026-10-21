package dev.multiprompt.companion.account

import dev.multiprompt.companion.ssh.TmuxParser
import org.json.JSONObject

enum class DeviceKeyStatus { PENDING, APPROVED, DENIED, REVOKED }

data class DeviceKeyEntry(val deviceId: String, val name: String, val publicKey: String, val status: DeviceKeyStatus)
data class DeviceKeyListing(val deviceId: String, val keys: List<DeviceKeyEntry>)

object DeviceKeys {
    fun validKey(key: String): Boolean = key.length <= 200 && "PRIVATE" !in key && "BEGIN" !in key &&
        Regex("ssh-ed25519 [A-Za-z0-9+/]+={0,2}").matches(key)

    fun parsePublicKey(value: String): String? {
        val fields = value.split(Regex("[ \\t\\r\\n]+")).filter(String::isNotEmpty)
        if (fields.size < 2) return null
        return "${fields[0]} ${fields[1]}".takeIf(::validKey)
    }

    fun addCommand(key: String): String {
        require(validKey(key)) { "Invalid device public key" }
        return "umask 077 && mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && if awk -v t=ssh-ed25519 -v k=${TmuxParser.shellQuote(key.substringAfter(' '))} '\$1 == t && \$2 == k { found=1 } END { exit found ? 0 : 3 }' ~/.ssh/authorized_keys; then :; else r=\$?; [ \$r = 3 ] && printf '\\n%s\\n' ${TmuxParser.shellQuote(key)} >> ~/.ssh/authorized_keys; fi"
    }

    fun removeCommand(key: String): String {
        require(validKey(key)) { "Invalid device public key" }
        return "umask 077 && if [ -f ~/.ssh/authorized_keys ]; then tmp=\$(mktemp ~/.ssh/authorized_keys.XXXXXX) && trap 'rm -f \"\$tmp\"' EXIT && awk -v t=ssh-ed25519 -v k=${TmuxParser.shellQuote(key.substringAfter(' '))} '\$1 != t || \$2 != k' ~/.ssh/authorized_keys > \"\$tmp\" && mv \"\$tmp\" ~/.ssh/authorized_keys; fi"
    }

    internal fun validCommand(command: String): Boolean {
        val data = Regex("-v k='([A-Za-z0-9+/]+={0,2})'").find(command)?.groupValues?.get(1) ?: return false
        val key = "ssh-ed25519 $data"
        return validKey(key) && (command == addCommand(key) || command == removeCommand(key))
    }

    fun validDeviceId(id: String): Boolean = Regex("[A-Za-z0-9_-]{1,128}").matches(id)

    fun parseListing(json: JSONObject): DeviceKeyListing? = runCatching {
        fun text(item: JSONObject, key: String): String = item.get(key) as? String ?: error("Invalid device key text")
        val deviceId = text(json, "device_id")
        require(validDeviceId(deviceId))
        val keys = json.getJSONArray("keys")
        DeviceKeyListing(deviceId, (0 until keys.length()).map { index ->
            val item = keys.getJSONObject(index)
            val id = text(item, "device_id")
            val key = text(item, "public_key")
            val status = when (text(item, "status")) {
                "pending" -> DeviceKeyStatus.PENDING
                "approved" -> DeviceKeyStatus.APPROVED
                "denied" -> DeviceKeyStatus.DENIED
                "revoked" -> DeviceKeyStatus.REVOKED
                else -> error("Invalid device key status")
            }
            require(validDeviceId(id) && validKey(key))
            DeviceKeyEntry(id, text(item, "name"), key, status)
        })
    }.getOrNull()

    fun isCleaned(state: String, key: String): Boolean = state.split('\n').any { it == key }
}
