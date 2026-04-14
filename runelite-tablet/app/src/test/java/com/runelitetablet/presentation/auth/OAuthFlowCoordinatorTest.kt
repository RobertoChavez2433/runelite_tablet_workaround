package com.runelitetablet.presentation.auth

import com.runelitetablet.auth.OAuthFlowCoordinator
import org.junit.Assert.*
import org.junit.Test

class OAuthFlowCoordinatorTest {

    @Test
    fun `parseJagexUri extracts code and state from comma-separated`() {
        val params = OAuthFlowCoordinator.parseJagexUri("jagex:code=abc123,state=xyz789")
        assertEquals("abc123", params["code"])
        assertEquals("xyz789", params["state"])
    }

    @Test
    fun `parseJagexUri extracts code and state from ampersand-separated`() {
        val params = OAuthFlowCoordinator.parseJagexUri("jagex:code=abc123&state=xyz789")
        assertEquals("abc123", params["code"])
        assertEquals("xyz789", params["state"])
    }

    @Test
    fun `parseJagexUri handles URL-encoded values`() {
        val params = OAuthFlowCoordinator.parseJagexUri("jagex:code=abc%20123,state=xyz%3D789")
        assertEquals("abc 123", params["code"])
        assertEquals("xyz=789", params["state"])
    }

    @Test
    fun `parseJagexUri returns empty map for empty uri`() {
        val params = OAuthFlowCoordinator.parseJagexUri("jagex:")
        assertTrue(params.isEmpty())
    }

    @Test
    fun `parseJagexUri handles extra parameters`() {
        val params = OAuthFlowCoordinator.parseJagexUri("jagex:code=abc,state=xyz,intent=social_auth")
        assertEquals("abc", params["code"])
        assertEquals("xyz", params["state"])
        assertEquals("social_auth", params["intent"])
    }

    @Test
    fun `parseJagexUri handles mixed comma and ampersand`() {
        val params = OAuthFlowCoordinator.parseJagexUri("jagex:code=abc,state=xyz&intent=auth")
        assertEquals("abc", params["code"])
        assertEquals("xyz", params["state"])
        assertEquals("auth", params["intent"])
    }

    @Test
    fun `parseFragmentParams extracts id_token and state`() {
        val params = OAuthFlowCoordinator.parseFragmentParams("id_token=eyJ123&state=xyz789&code=abc")
        assertEquals("eyJ123", params["id_token"])
        assertEquals("xyz789", params["state"])
        assertEquals("abc", params["code"])
    }

    @Test
    fun `parseFragmentParams returns empty map for blank input`() {
        val params = OAuthFlowCoordinator.parseFragmentParams("")
        assertTrue(params.isEmpty())
    }

    @Test
    fun `parseFragmentParams handles URL-encoded values`() {
        val params = OAuthFlowCoordinator.parseFragmentParams("id_token=abc%2B123&state=xyz")
        assertEquals("abc+123", params["id_token"])
    }

    @Test
    fun `parseFragmentParams handles single param`() {
        val params = OAuthFlowCoordinator.parseFragmentParams("id_token=abc123")
        assertEquals("abc123", params["id_token"])
        assertEquals(1, params.size)
    }

    @Test
    fun `parseFragmentParams ignores malformed pairs`() {
        val params = OAuthFlowCoordinator.parseFragmentParams("id_token=abc&bad_no_equals&state=xyz")
        assertEquals("abc", params["id_token"])
        assertEquals("xyz", params["state"])
        assertEquals(2, params.size)
    }

    @Test
    fun `parseJagexUri handles no prefix gracefully`() {
        val params = OAuthFlowCoordinator.parseJagexUri("code=abc,state=xyz")
        // removePrefix("jagex:") doesn't remove when not present, so it treats the whole thing as params
        assertEquals("abc", params["code"])
    }
}
