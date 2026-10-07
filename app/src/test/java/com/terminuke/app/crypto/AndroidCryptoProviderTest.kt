package com.terminuke.app.crypto

import java.security.Security
import javax.crypto.KeyAgreement
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidCryptoProviderTest {
    @Test
    fun installsBundledProviderWithX25519SupportAndIsIdempotent() {
        AndroidCryptoProvider.install()
        AndroidCryptoProvider.install()

        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        assertNotNull(provider)
        assertEquals(BouncyCastleProvider::class.java.name, provider?.javaClass?.name)
        assertEquals("X25519", KeyAgreement.getInstance("X25519", BouncyCastleProvider.PROVIDER_NAME).algorithm)
    }
}
