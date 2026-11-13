package com.edu.standalone

import org.junit.Assert.*
import org.junit.Test

class EndpointIdentityTest {
    @Test fun equivalentUrlsShareStorageButIndependentEndpointsDoNot() {
        assertEquals("https://example.com/api", EndpointIdentity.normalize(" HTTPS://EXAMPLE.COM:443/api/ "))
        assertEquals(EndpointIdentity.key("http://localhost:80/"), EndpointIdentity.key("http://LOCALHOST"))
        assertNotEquals(EndpointIdentity.key("https://example.com/a"), EndpointIdentity.key("https://example.com/b"))
        assertNotEquals(EndpointIdentity.key("http://localhost:8080"), EndpointIdentity.key("http://localhost:8081"))
        assertEquals("https://example.com/a%20b", EndpointIdentity.normalize("https://example.com/a%20b/"))
    }

    @Test fun rejectsMalformedUrlsAndCleartextHostPrefixTricks() {
        listOf("http://localhost.evil.test", "http://10.0.2.2.evil.test", "http://192.168.0.2",
            "ftp://localhost", "https://", "https://user:pass@example.com", "https://example.com?a=b",
            "https://example.com/#fragment", "https://example.com:99999").forEach {
            assertThrows(Exception::class.java) { EndpointIdentity.normalize(it) }
        }
    }
}
