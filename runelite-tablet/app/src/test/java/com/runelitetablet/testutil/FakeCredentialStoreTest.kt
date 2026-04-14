package com.runelitetablet.testutil

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeCredentialStoreTest {
    private lateinit var store: FakeCredentialStore

    @Before
    fun setup() {
        store = FakeCredentialStore()
    }

    @Test
    fun `initially has no credentials`() {
        assertFalse(store.hasCredentials())
        assertNull(store.getCredentials())
        assertNull(store.getDisplayName())
        assertNull(store.getRefreshToken())
        assertEquals(0L, store.getAccessTokenExpiry())
    }

    @Test
    fun `storeTokens and retrieve`() {
        store.storeTokens("access", "refresh", 12345L)
        assertEquals("refresh", store.getRefreshToken())
        assertEquals(12345L, store.getAccessTokenExpiry())
    }

    @Test
    fun `storeGameSession makes hasCredentials true`() {
        store.storeGameSession("sid", "cid", "TestUser")
        assertTrue(store.hasCredentials())
        assertEquals("TestUser", store.getDisplayName())
    }

    @Test
    fun `getCredentials returns all stored data`() {
        store.storeTokens("access_tok", "refresh_tok", 99999L)
        store.storeGameSession("session_id", "char_id", "Player1")

        val creds = store.getCredentials()
        assertNotNull(creds)
        assertEquals("session_id", creds!!.sessionId)
        assertEquals("char_id", creds.characterId)
        assertEquals("Player1", creds.displayName)
        assertEquals("access_tok", creds.accessToken)
        assertEquals("refresh_tok", creds.refreshToken)
    }

    @Test
    fun `clearCredentials resets everything`() {
        store.storeTokens("a", "r", 1L)
        store.storeGameSession("s", "c", "name")
        store.clearCredentials()

        assertFalse(store.hasCredentials())
        assertNull(store.getCredentials())
        assertNull(store.getRefreshToken())
        assertEquals(0L, store.getAccessTokenExpiry())
    }

    @Test
    fun `storeTokens with null refreshToken preserves existing`() {
        store.storeTokens("a1", "original_refresh", 100L)
        store.storeTokens("a2", null, 200L)
        assertEquals("original_refresh", store.getRefreshToken())
        assertEquals(200L, store.getAccessTokenExpiry())
    }
}
