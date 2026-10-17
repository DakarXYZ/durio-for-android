package chat.stoat.fork.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 00-architecture §6: sha256(canonicalApiUrl + "\n" + userId), first 16 hex characters
class AccountKeyTest {
    private val user = "01HZY0000000000000000000AA"

    @Test
    fun matchesTheSpecifiedDigest() {
        // python: hashlib.sha256(b"https://duri0.duckdns.org/api\n" + user).hexdigest()[:16]
        assertEquals("1d81889494246dac", accountKey("https://duri0.duckdns.org/api", user))
    }

    @Test
    fun isSixteenLowercaseHexCharacters() {
        val key = accountKey("https://duri0.duckdns.org/api", user)
        assertEquals(16, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun usesTheCanonicalApiUrl() {
        val canonical = accountKey("https://duri0.duckdns.org/api", user)
        assertEquals(canonical, accountKey("HTTPS://Duri0.DuckDNS.org:443/api/", user))
        assertEquals(canonical, accountKey("duri0.duckdns.org/api", user))
    }

    @Test
    fun differsPerServerAndPerUser() {
        val key = accountKey("https://duri0.duckdns.org/api", user)
        assertNotEquals(key, accountKey("https://duri1.duckdns.org/api", user))
        assertNotEquals(key, accountKey("https://duri0.duckdns.org/api", "01HZY0000000000000000000AB"))
        // the path is part of the API address
        assertNotEquals(key, accountKey("https://duri0.duckdns.org", user))
    }
}
