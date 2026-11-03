package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ssh.SshProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceApproval(
    private val client: DeviceKeyClient,
    private val run: suspend (HostProfile, String) -> Boolean,
) {
    data class HostFailure(val host: HostProfile, val deviceId: String, val message: String)
    data class Result(val approved: Boolean, val failures: List<HostFailure>, val skipped: List<HostProfile> = emptyList())

    suspend fun approve(entry: DeviceKeyEntry, hosts: List<HostProfile>): Result = withContext(Dispatchers.IO) {
        require(entry.status == DeviceKeyStatus.PENDING && DeviceKeys.validDeviceId(entry.deviceId)) { "Device key is not pending" }
        require(hosts.isNotEmpty()) { "Add a host before approving a device" }
        val refused = mutableListOf<HostFailure>()
        val failures = execute(entry, hosts, DeviceKeys.addCommand(entry.publicKey), refused)
        val reachable = hosts.filter { host -> refused.none { it.host == host } }
        if (failures.isNotEmpty() || reachable.isEmpty()) {
            execute(entry, reachable, DeviceKeys.removeCommand(entry.publicKey))
            return@withContext Result(false, failures + refused)
        }
        Result(client.approve(entry.deviceId), emptyList(), refused.map { it.host })
    }

    suspend fun deny(entry: DeviceKeyEntry, hosts: List<HostProfile>): Boolean = withContext(Dispatchers.IO) {
        execute(entry, hosts, DeviceKeys.removeCommand(entry.publicKey))
        client.deny(entry.deviceId)
    }

    suspend fun cleanupRevoked(listing: DeviceKeyListing, hosts: List<HostProfile>): List<HostFailure> = withContext(Dispatchers.IO) {
        listing.keys.filter { it.status == DeviceKeyStatus.REVOKED || it.status == DeviceKeyStatus.DENIED }.flatMap { entry ->
            execute(entry, hosts, DeviceKeys.removeCommand(entry.publicKey))
        }
    }

    private suspend fun execute(
        entry: DeviceKeyEntry,
        hosts: List<HostProfile>,
        command: String,
        refused: MutableList<HostFailure>? = null,
    ): List<HostFailure> =
        hosts.mapNotNull { host ->
            try {
                if (run(host, command)) null else HostFailure(host, entry.deviceId, "Host did not confirm the device key change")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SshProblem.Authentication) {
                val problem = HostFailure(host, entry.deviceId, failure.message ?: "This server refused this phone")
                if (refused == null) problem else null.also { refused.add(problem) }
            } catch (failure: Exception) {
                HostFailure(host, entry.deviceId, failure.message ?: "Could not change the device key")
            }
        }
}
