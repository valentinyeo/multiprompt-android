package dev.multiprompt.companion.account

import org.json.JSONObject
import java.io.IOException

class DeviceKeyClient(private val http: AccountHttp, private val token: () -> String) {
    fun publish(publicKey: String): DeviceKeyStatus? {
        require(DeviceKeys.validKey(publicKey)) { "Invalid device public key" }
        val reply = http.request("PUT", "/v1/device-key", token(), JSONObject().put("public_key", publicKey))
        if (reply.status == 404) return null
        requireSuccess(reply)
        return when (reply.json.opt("status")) {
            "pending" -> DeviceKeyStatus.PENDING
            "approved" -> DeviceKeyStatus.APPROVED
            "denied" -> DeviceKeyStatus.DENIED
            "revoked" -> DeviceKeyStatus.REVOKED
            else -> throw IOException("Invalid device key response")
        }
    }

    fun list(): DeviceKeyListing? {
        val reply = http.request("GET", "/v1/device-keys", token(), null)
        if (reply.status == 404) return null
        requireSuccess(reply)
        return DeviceKeys.parseListing(reply.json) ?: throw IOException("Invalid device key listing")
    }

    fun approve(id: String): Boolean = decide(id, "approve", "approved")
    fun deny(id: String): Boolean = decide(id, "deny", "denied")

    fun revoke(id: String): Boolean {
        require(DeviceKeys.validDeviceId(id)) { "Invalid device id" }
        val reply = http.request("DELETE", "/v1/devices/$id", token(), null)
        if (reply.status in listOf(403, 404, 409)) return false
        requireSuccess(reply)
        if (reply.json.opt("revoked") != true) throw IOException("Invalid device revocation response")
        return true
    }

    private fun decide(id: String, action: String, status: String): Boolean {
        require(DeviceKeys.validDeviceId(id)) { "Invalid device id" }
        val reply = http.request("POST", "/v1/device-keys/$id/$action", token(), JSONObject())
        if (reply.status in listOf(403, 404, 409)) return false
        requireSuccess(reply)
        if (reply.json.opt("status") != status) throw IOException("Invalid device decision response")
        return true
    }

    private fun requireSuccess(reply: AccountReply) {
        if (reply.status != 200) throw IOException("Device key service unavailable (${reply.status}); retry when online")
    }
}
