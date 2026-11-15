// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app takes from a network's own description. */
class NetworkStatusTest {
    @Test
    fun aNetworkIsMeteredUnlessItSaysThatItIsNot() {
        assertTrue("Mobile data", networkStatus(1, validated = true, notMetered = false).metered)
        assertFalse("Wi-Fi at home", networkStatus(2, validated = true, notMetered = true).metered)
    }

    @Test
    fun aNetworkIsUsableOnceThePhoneHasCheckedIt() {
        assertTrue(networkStatus(1, validated = true, notMetered = true).usable)
        assertFalse(networkStatus(1, validated = false, notMetered = true).usable)
    }

    @Test
    fun withoutANetworkNothingIsUsableAndNothingIsLoaded() {
        assertEquals(NetworkStatus(id = null, validated = false, metered = true), NetworkStatus.None)
        assertFalse(NetworkStatus.None.usable)
    }
}
