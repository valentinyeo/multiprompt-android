package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class RetiredHostsTest {
    @Test
    fun selectsRetiredInneAgentAndKeepsHetznerAndOtherHosts() {
        val retired = host("Inne Agent", "157.173.109.227", "agent")
        val hetzner = host("Hetzner", "46.224.218.39", "valentin")
        val other = host("Other", "example.com", "agent")

        assertEquals(listOf(retired), RetiredHosts.hostsToRemove(listOf(retired, hetzner, other)))
    }

    @Test
    fun selectsEveryProfileAtTheTrimmedRetiredAddressRegardlessOfLabelOrUser() {
        val retired = host("Renamed", " \t157.173.109.227\n", "valentin")
        val another = host("Another user", "157.173.109.227", "root")

        assertEquals(listOf(retired, another), RetiredHosts.hostsToRemove(listOf(retired, another)))
    }

    @Test
    fun selectsTheRetiredInneCroServerWhateverItsLabelOrUserAndKeepsHetzner() {
        val cro = host("Inne CRO", "45.151.123.11", "valentin")
        val renamed = host("Old server", " 45.151.123.11 ", "root")
        val hetzner = host("Hetzner", "46.224.218.39", "valentin")
        val croLabelElsewhere = host("Inne CRO", "46.224.218.39", "inne")

        assertEquals(listOf(cro, renamed), RetiredHosts.hostsToRemove(listOf(cro, hetzner, renamed, croLabelElsewhere)))
    }

    @Test
    fun matchesTheWholeHostnameNotLabelsOrAddressPrefixes() {
        val sameLabel = host("Inne Agent", "46.224.218.39", "valentin")
        val addressInLabel = host("157.173.109.227", "example.com", "agent")
        val addressPrefix = host("Other", "157.173.109.227.example.com", "agent")

        assertEquals(emptyList<HostProfile>(), RetiredHosts.hostsToRemove(listOf(sameLabel, addressInLabel, addressPrefix)))
    }

    @Test
    fun emptyInputHasNoHostsToRemove() {
        assertEquals(emptyList<HostProfile>(), RetiredHosts.hostsToRemove(emptyList()))
    }

    private fun host(label: String, hostname: String, username: String) = HostProfile(
        label = label,
        hostname = hostname,
        username = username,
        keySecretId = "key",
    )
}
