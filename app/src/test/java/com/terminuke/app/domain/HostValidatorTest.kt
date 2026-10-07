package com.terminuke.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostValidatorTest {
    @Test
    fun validHostInputHasNoErrors() {
        assertTrue(HostValidator.validate(HostInput("lab", "server.example.net", "22", "ikaz")).isEmpty())
    }

    @Test
    fun invalidAddressPortAndUsernameAreReported() {
        val errors = HostValidator.validate(HostInput("", "https://server/", "70000", "bad user"))
        assertEquals(setOf("label", "hostname", "port", "username"), errors.keys)
    }

    @Test
    fun acceptsValidIpv4AndRejectsOutOfRangeOctet() {
        assertTrue(HostValidator.isValidHost("192.168.1.25"))
        assertFalse(HostValidator.isValidHost("192.168.1.300"))
    }

    @Test
    fun keyAuthenticationRequiresASelectedKey() {
        val host = HostInput("lab", "server.example.net", "22", "ikaz", authType = "key")
        assertTrue(HostValidator.validate(host).containsKey("keyAlias"))
        assertFalse(HostValidator.validate(host.copy(keyAlias = "key-1")).containsKey("keyAlias"))
    }
}
