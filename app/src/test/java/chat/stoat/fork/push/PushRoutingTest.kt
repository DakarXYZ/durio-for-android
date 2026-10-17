package chat.stoat.fork.push

import chat.stoat.c2dm.ChannelRegistrator
import chat.stoat.core.model.fork.ServerEndpoints
import chat.stoat.fork.account.Account
import chat.stoat.fork.account.accountKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Push for several accounts (plan AN-12): the account comes from instance + recipient (BE-30)
class PushRoutingTest {
    private fun server(host: String) = ServerEndpoints(
        api = "https://$host/api",
        ws = "wss://$host/ws",
        files = "https://$host/autumn",
        proxy = "",
        webApp = "https://$host",
    )

    private fun account(host: String, userId: String) = server(host).let {
        Account(accountKey(it.api, userId), it, userId, sessionId = "s-$userId", addedAt = 0)
    }

    private val aliceOnA = account("a.example", "alice")
    private val bobOnA = account("a.example", "bob")
    private val aliceOnB = account("b.example", "alice")
    private val accounts = listOf(aliceOnA, bobOnA, aliceOnB)

    private fun push(vararg pairs: Pair<String, String>) =
        PushMessage(mapOf("type" to "push.message", "channel" to "c1") + pairs)

    @Test
    fun instanceAndRecipientPickTheAccount() {
        assertEquals(
            PushTarget.Bound(bobOnA),
            resolveAccount(push("instance" to "https://a.example/api", "recipient" to "bob"), accounts)
        )
        assertEquals(
            PushTarget.Bound(aliceOnB),
            resolveAccount(push("instance" to "https://b.example/api", "recipient" to "alice"), accounts)
        )
    }

    @Test
    fun instanceIsComparedInCanonicalForm() {
        // pushd normalises the same way, but a trailing slash or default port must not matter
        assertEquals(
            PushTarget.Bound(aliceOnA),
            resolveAccount(push("instance" to "HTTPS://A.example:443/api/", "recipient" to "alice"), accounts)
        )
    }

    @Test
    fun unknownAccountsAreDropped() {
        assertEquals(
            PushTarget.Drop,
            resolveAccount(push("instance" to "https://c.example/api", "recipient" to "alice"), accounts)
        )
        assertEquals(
            PushTarget.Drop,
            resolveAccount(push("instance" to "https://a.example/api", "recipient" to "carol"), accounts)
        )
        assertEquals(
            PushTarget.Drop,
            resolveAccount(push("instance" to "https://a.example/api", "recipient" to "alice"), emptyList())
        )
    }

    @Test
    fun withoutInstanceTheRecipientMustBeUnique() {
        assertEquals(PushTarget.Bound(bobOnA), resolveAccount(push("recipient" to "bob"), accounts))
        // alice has accounts on two servers: no guessing
        assertEquals(PushTarget.Drop, resolveAccount(push("recipient" to "alice"), accounts))
        assertEquals(PushTarget.Drop, resolveAccount(push("instance" to "", "recipient" to "alice"), accounts))
    }

    @Test
    fun withoutRecipientThePushIsUnbound() {
        assertEquals(PushTarget.Unbound, resolveAccount(push("instance" to "https://a.example/api"), accounts))
        assertEquals(PushTarget.Unbound, resolveAccount(push("recipient" to ""), accounts))
        assertEquals(PushTarget.Drop, resolveAccount(push(), emptyList()))
    }

    @Test
    fun notificationNamesArePerAccount() {
        val a = aliceOnA.key
        val b = bobOnA.key

        assertNotEquals(NotificationKeys.tag(a, "c1"), NotificationKeys.tag(b, "c1"))
        assertNotEquals(NotificationKeys.requestCode(a, "c1"), NotificationKeys.requestCode(b, "c1"))
        assertNotEquals(NotificationKeys.requestCode(a, "c1"), NotificationKeys.requestCode(a, "c1", action = 1))
        assertNotEquals(
            NotificationKeys.shortcutId("app", a, "c1"),
            NotificationKeys.shortcutId("app", b, "c1")
        )

        // logout (AN-11) finds them by these rules
        assertTrue(NotificationKeys.isOfAccount(NotificationKeys.tag(a, "c1"), a))
        assertTrue(NotificationKeys.isOfAccount(NotificationKeys.summaryTag(a), a))
        assertFalse(NotificationKeys.isOfAccount(NotificationKeys.tag(b, "c1"), a))
        assertFalse(NotificationKeys.isOfAccount("c1", a))
        assertTrue(".$a." in NotificationKeys.shortcutId("app", a, "c1"))
        assertEquals("acc.$a", NotificationKeys.channelGroup(a))
    }

    @Test
    fun migratedAccountKeepsUpstreamsChannel() {
        val a = aliceOnA.key
        assertEquals(
            ChannelRegistrator.CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES,
            NotificationKeys.messagesChannel(a, legacyKey = a)
        )
        assertEquals("acc.$a.messages", NotificationKeys.messagesChannel(a, legacyKey = bobOnA.key))
        assertEquals("acc.$a.messages", NotificationKeys.messagesChannel(a, legacyKey = null))
    }
}
