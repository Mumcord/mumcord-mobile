// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.securestore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineBootstrapTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun prepareLoadsTheLibraryAndEnablesSystemCertificateChecking() {
        val storage = EngineBootstrap.prepare(context)

        assertTrue(storage is KeystoreSecureStorage)
        // The native side must accept a repeated call.
        SystemCertificates.init(context.applicationContext)
    }

    @Test
    fun prepareTwiceReturnsTheSameStorage() {
        assertSame(EngineBootstrap.prepare(context), EngineBootstrap.prepare(context))
    }

    @Test
    fun companionLibraryOfTheCertificateVerifierIsShipped() {
        // The native library looks this class up through JNI when it checks a certificate.
        Class.forName("org.rustls.platformverifier.CertificateVerifier")
    }
}
