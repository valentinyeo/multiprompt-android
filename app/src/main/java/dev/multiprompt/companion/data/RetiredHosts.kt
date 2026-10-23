package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.HostProfile

object RetiredHosts {
    private val addresses = setOf("157.173.109.227", "45.151.123.11")

    fun isRetired(hostname: String): Boolean = addresses.any { address -> hostname.trim().equals(address, ignoreCase = true) }

    fun hostsToRemove(hosts: List<HostProfile>): List<HostProfile> = hosts.filter { isRetired(it.hostname) }
}
