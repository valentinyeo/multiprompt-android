package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.HostProfile

object RetiredHosts {
    private val addresses = setOf("157.173.109.227")

    fun hostsToRemove(hosts: List<HostProfile>): List<HostProfile> = hosts.filter { host ->
        addresses.any { address -> host.hostname.trim().equals(address, ignoreCase = true) }
    }
}
