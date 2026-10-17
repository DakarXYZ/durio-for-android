package chat.stoat.fork.links

import chat.stoat.core.model.fork.ServerEndpoints
import chat.stoat.fork.account.Account
import chat.stoat.fork.account.accountKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Links open in the account of their server; invite input builds one URL (plan AN-13)
class AccountLinksTest {
    private fun account(host: String, userId: String, addedAt: Long, invites: String? = null): Account {
        val endpoints = ServerEndpoints(
            api = "https://$host/api",
            ws = "wss://$host/ws",
            files = "",
            proxy = "",
            webApp = "https://$host",
            invites = invites,
        )
        return Account(accountKey(endpoints.api, userId), endpoints, userId, "s", addedAt = addedAt)
    }

    private val aliceOnA = account("a.example", "alice", addedAt = 2)
    private val bobOnA = account("a.example", "bob", addedAt = 1)
    private val carolOnB = account("b.example", "carol", addedAt = 3, invites = "https://inv.example")
    private val accounts = listOf(aliceOnA, bobOnA, carolOnB)

    @Test
    fun hostPicksTheAccount() {
        assertEquals(carolOnB, AccountLinks.accountForHost("b.example", accounts, activeKey = null))
        assertEquals(carolOnB, AccountLinks.accountForHost("inv.example", accounts, activeKey = null))
        assertEquals(carolOnB, AccountLinks.accountForHost("B.EXAMPLE", accounts, activeKey = aliceOnA.key))
    }

    @Test
    fun activeAccountWinsOnASharedServer() {
        assertEquals(aliceOnA, AccountLinks.accountForHost("a.example", accounts, activeKey = aliceOnA.key))
        // otherwise the oldest account on that server
        assertEquals(bobOnA, AccountLinks.accountForHost("a.example", accounts, activeKey = carolOnB.key))
    }

    @Test
    fun foreignHostsHaveNoAccount() {
        assertNull(AccountLinks.accountForHost("c.example", accounts, activeKey = aliceOnA.key))
        assertNull(AccountLinks.accountForHost(null, accounts, activeKey = null))
        assertNull(AccountLinks.accountForHost("a.example", emptyList(), activeKey = null))
    }

    @Test
    fun bareCodeGetsOneScheme() {
        // upstream built "https://https://a.example/invite/abc"
        assertEquals("https://a.example/invite/abc", inviteUrlFromInput("abc", "https://a.example/invite"))
        assertEquals("https://inv.example/abc", inviteUrlFromInput(" abc ", "https://inv.example/"))
    }

    @Test
    fun linksAreKept() {
        assertEquals("https://a.example/invite/abc", inviteUrlFromInput("https://a.example/invite/abc", "x"))
        assertEquals("http://localhost:5173/invite/abc", inviteUrlFromInput("http://localhost:5173/invite/abc", "x"))
        assertEquals("https://a.example/invite/abc", inviteUrlFromInput("a.example/invite/abc", "x"))
    }

    @Test
    fun nothingToOpen() {
        assertNull(inviteUrlFromInput("  ", "https://a.example/invite"))
        assertNull(inviteUrlFromInput("abc", ""))
    }
}
