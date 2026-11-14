package dev.multiprompt.companion.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import dev.multiprompt.companion.reader.NetworkNudgeGate

class NetworkReturnWatcher(
    private val connectivity: ConnectivityManager?,
    private val gate: NetworkNudgeGate = NetworkNudgeGate(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val onReturn: () -> Unit,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        val manager = connectivity ?: return
        if (callback != null) return
        gate.reset()
        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                evaluate(network, manager.getNetworkCapabilities(network))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                evaluate(network, capabilities)
            }

            override fun onLost(network: Network) {
                gate.onNetwork(network.networkHandle, false, clock())
            }
        }
        if (runCatching { manager.registerDefaultNetworkCallback(created) }.isSuccess) callback = created
    }

    @Synchronized
    fun stop() {
        val registered = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(registered) }
    }

    fun resumed(): Boolean = gate.onResume(clock())

    private fun evaluate(network: Network, capabilities: NetworkCapabilities?) {
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (gate.onNetwork(network.networkHandle, validated, clock())) onReturn()
    }
}
