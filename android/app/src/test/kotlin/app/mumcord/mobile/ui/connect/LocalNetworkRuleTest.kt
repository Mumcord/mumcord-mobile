// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalNetworkRuleTest {
    private val wifi = listOf(prefix("192.168.1.37/24"), prefix("fd12:3456:789a:1::37/64"))

    private fun prefix(text: String) =
        IpPrefix(InetAddress.getByName(text.substringBefore('/')), text.substringAfter('/').toInt())

    private fun locality(vararg addresses: String, onLink: List<IpPrefix> = wifi) =
        localityOf(addresses.map(InetAddress::getByName), onLink)

    // ----- which addresses count as local -----

    @Test
    fun anAddressOnTheDevicesOwnNetworkIsOnLink() {
        assertEquals(Locality.OnLink, locality("192.168.1.20"))
        assertEquals(Locality.OnLink, locality("fd12:3456:789a:1::20"))
        // The emulator's host, as the end-to-end test uses it.
        assertEquals(Locality.OnLink, locality("10.0.2.2", onLink = listOf(prefix("10.0.2.15/24"))))
    }

    @Test
    fun aPrefixThatDoesNotEndOnAByteIsCompared() {
        val network = listOf(prefix("10.42.1.12/20"))
        assertEquals(Locality.OnLink, locality("10.42.15.254", onLink = network))
        assertEquals(Locality.Private, locality("10.42.16.1", onLink = network))
    }

    @Test
    fun aPrivateAddressBehindARouterIsOnlyPrivate() {
        assertEquals(Locality.Private, locality("192.168.2.20"))
        assertEquals(Locality.Private, locality("10.8.0.1"))
        assertEquals(Locality.Private, locality("172.31.255.1"))
        assertEquals(Locality.Private, locality("100.100.1.1"))
        assertEquals(Locality.Private, locality("fd00::1"))
    }

    @Test
    fun internetAddressesAreNeitherLocalNorPrivate() {
        assertEquals(Locality.Other, locality("203.0.113.7"))
        assertEquals(Locality.Other, locality("172.32.0.1"))
        assertEquals(Locality.Other, locality("100.128.0.1"))
        assertEquals(Locality.Other, locality("2001:db8::1"))
        assertEquals(Locality.Other, locality("127.0.0.1"))
        assertEquals(Locality.Other, locality("::1"))
        assertEquals(Locality.Other, locality())
    }

    @Test
    fun linkLocalMulticastAndBroadcastAreOnLinkWithoutAnyNetwork() {
        assertEquals(Locality.OnLink, locality("169.254.10.1", onLink = emptyList()))
        assertEquals(Locality.OnLink, locality("fe80::1", onLink = emptyList()))
        assertEquals(Locality.OnLink, locality("224.0.0.251", onLink = emptyList()))
        assertEquals(Locality.OnLink, locality("ff02::fb", onLink = emptyList()))
        assertEquals(Locality.OnLink, locality("255.255.255.255", onLink = emptyList()))
    }

    @Test
    fun theMostLocalOfSeveralAddressesDecides() {
        assertEquals(Locality.OnLink, locality("203.0.113.7", "192.168.1.20"))
        assertEquals(Locality.Private, locality("203.0.113.7", "10.8.0.1"))
    }

    @Test
    fun namesEndingInLocalAreLocalNames() {
        assertTrue(isLocalName("nas.local"))
        assertTrue(isLocalName("NAS.Local."))
        assertFalse(isLocalName("local.example"))
        assertFalse(isLocalName("localhost"))
    }

    // ----- when the system stops asking -----

    @Test
    fun onlyARefusalAfterAnEarlierRefusalBlocksThePrompt() {
        assertTrue(LocalNetworkAnswer(granted = false, refusedBefore = true, refusedAfter = false).blocksPrompt)
        // The first refusal: the system will ask again.
        assertFalse(LocalNetworkAnswer(granted = false, refusedBefore = false, refusedAfter = true).blocksPrompt)
        // The prompt was closed without an answer.
        assertFalse(LocalNetworkAnswer(granted = false, refusedBefore = false, refusedAfter = false).blocksPrompt)
        assertFalse(LocalNetworkAnswer(granted = false, refusedBefore = true, refusedAfter = true).blocksPrompt)
        assertFalse(LocalNetworkAnswer(granted = true, refusedBefore = true, refusedAfter = false).blocksPrompt)
    }

    // ----- what happens before a connect -----

    private fun gateway(permission: LocalNetworkPermission = LocalNetworkPermission.Missing) =
        FakeLocalNetworkGateway(permission).apply {
            names["home.example"] = listOf("192.168.1.20")
            names["office.example"] = listOf("10.8.0.1")
            names["public.example"] = listOf("203.0.113.7")
        }

    @Test
    fun anAndroidVersionWithoutThePermissionConnectsWithoutLookingAtTheAddress() = runTest {
        val gateway = gateway(LocalNetworkPermission.NotRequired)
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Connect), gateway.check("192.168.1.20", suspected = false))
        assertTrue(gateway.resolved.isEmpty())
    }

    @Test
    fun withThePermissionEverythingConnectsAndAnOldBlockIsForgotten() = runTest {
        val gateway = gateway(LocalNetworkPermission.Granted).apply { promptBlocked = true }
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Connect), gateway.check("home.example", suspected = false))
        assertTrue(gateway.resolved.isEmpty())
        assertFalse(gateway.promptBlocked)
    }

    @Test
    fun aLocalServerWithoutThePermissionIsExplainedByAddressAndByName() = runTest {
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Explain), gateway().check("192.168.1.20", suspected = false))
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Explain), gateway().check("home.example", suspected = false))
    }

    @Test
    fun aLocalServerGoesStraightToTheRefusalOnceTheSystemNoLongerAsks() = runTest {
        val gateway = gateway().apply { promptBlocked = true }
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Denied), gateway.check("home.example", suspected = false))
    }

    @Test
    fun aLocalNameIsExplainedWithoutBeingResolved() = runTest {
        val gateway = gateway()
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Explain), gateway.check("nas.local", suspected = false))
        assertTrue(gateway.resolved.isEmpty())
    }

    @Test
    fun anInternetServerConnectsWithoutAnyQuestion() = runTest {
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Connect), gateway().check("public.example", suspected = false))
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Connect), gateway().check("public.example", suspected = true))
    }

    @Test
    fun aPrivateAddressBehindARouterConnectsButIsRememberedAsSuspect() = runTest {
        assertEquals(
            LocalNetworkCheck(LocalNetworkStep.Connect, suspect = true),
            gateway().check("office.example", suspected = false),
        )
    }

    @Test
    fun aPrivateAddressThatWasOutOfReachIsExplainedOnTheNextTry() = runTest {
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Explain), gateway().check("office.example", suspected = true))
    }

    @Test
    fun aNameThatDoesNotResolveConnectsAsBefore() = runTest {
        assertEquals(LocalNetworkCheck(LocalNetworkStep.Connect), gateway().check("unknown.example", suspected = false))
    }
}
