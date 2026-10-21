package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceApproval(
    private val client: DeviceKeyClient,
    private val run: suspend (HostProfile, String) -> Boolean,
) {
    data class HostFailure(val host: HostProfile, val deviceId: String, val message: String)
    data class Result(val approved: Boolean, val failures: List<HostFailure>)

    suspend fun approve(entry: DeviceKeyEntry, hosts: List<HostProfile>): Result = withContext(Dispatchers.IO) {
        require(entry.status == DeviceKeyStatus.PENDING && DeviceKeys.validDeviceId(entry.deviceId)) { "Device key is not pending" }
        require(hosts.isNotEmpty()) { "Add a host before approving a device" }
        val failures = execute(entry, hosts, DeviceKeys.addCommand(entry.publicKey))
        Result(failures.isEmpty() && client.approve(entry.deviceId), failures)
    }

    fun deny(entry: DeviceKeyEntry): Boolean = client.deny(entry.deviceId)

    suspend fun cleanupRevoked(listing: DeviceKeyListing, hosts: List<HostProfile>): List<HostFailure> = withContext(Dispatchers.IO) {
        listing.keys.filter { it.status == DeviceKeyStatus.REVOKED }.flatMap { entry ->
            execute(entry, hosts, DeviceKeys.removeCommand(entry.publicKey))
        }
    }

    private suspend fun execute(entry: DeviceKeyEntry, hosts: List<HostProfile>, command: String): List<HostFailure> =
        hosts.mapNotNull { host ->
            try {
                if (run(host, command)) null else HostFailure(host, entry.deviceId, "Host did not confirm the device key change")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                HostFailure(host, entry.deviceId, failure.message ?: "Could not change the device key")
            }
        }
}
