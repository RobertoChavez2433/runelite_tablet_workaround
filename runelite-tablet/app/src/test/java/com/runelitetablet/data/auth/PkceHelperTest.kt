package com.runelitetablet.data.auth

import com.runelitetablet.auth.PkceHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PkceHelperTest {

    @Test
    fun `generateVerifier produces base64url string of correct length`() {
        val verifier = PkceHelper.generateVerifier()
        // 64 bytes -> base64url without padding = 86 characters
        assertEquals(86, verifier.length)
        assertBase64Url(verifier)
    }

    @Test
    fun `generateVerifier produces unique values`() {
        val verifiers = (1..10).map { PkceHelper.generateVerifier() }.toSet()
        assertEquals(10, verifiers.size)
    }

    @Test
    fun `deriveChallenge produces valid base64url SHA-256 hash`() {
        val verifier = PkceHelper.generateVerifier()
        val challenge = PkceHelper.deriveChallenge(verifier)
        // SHA-256 = 32 bytes -> base64url without padding = 43 characters
        assertEquals(43, challenge.length)
        assertBase64Url(challenge)
    }

    @Test
    fun `deriveChallenge is deterministic for same verifier`() {
        val verifier = PkceHelper.generateVerifier()
        val challenge1 = PkceHelper.deriveChallenge(verifier)
        val challenge2 = PkceHelper.deriveChallenge(verifier)
        assertEquals(challenge1, challenge2)
    }

    @Test
    fun `deriveChallenge differs for different verifiers`() {
        val v1 = PkceHelper.generateVerifier()
        val v2 = PkceHelper.generateVerifier()
        assertNotEquals(PkceHelper.deriveChallenge(v1), PkceHelper.deriveChallenge(v2))
    }

    @Test
    fun `deriveChallenge matches known RFC 7636 test vector`() {
        // RFC 7636 Appendix B test vector
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, PkceHelper.deriveChallenge(verifier))
    }

    @Test
    fun `generateState produces base64url string of correct length`() {
        val state = PkceHelper.generateState()
        // 32 bytes -> base64url without padding = 43 characters
        assertEquals(43, state.length)
        assertBase64Url(state)
    }

    @Test
    fun `generateState produces unique values`() {
        val states = (1..10).map { PkceHelper.generateState() }.toSet()
        assertEquals(10, states.size)
    }

    @Test
    fun `verifier can be decoded back to 64 bytes`() {
        val verifier = PkceHelper.generateVerifier()
        val decoded = Base64.getUrlDecoder().decode(verifier)
        assertEquals(64, decoded.size)
    }

    private fun assertBase64Url(value: String) {
        assertTrue(
            "Expected base64url characters only, got: $value",
            value.matches(Regex("[A-Za-z0-9_-]+"))
        )
    }
}
