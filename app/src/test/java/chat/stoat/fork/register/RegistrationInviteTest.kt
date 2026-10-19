package chat.stoat.fork.register

import chat.stoat.api.StoatJson
import chat.stoat.api.routes.account.RegistrationBody
import chat.stoat.core.model.fork.ServerEndpoints
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_register_invite_invalid
import chat.stoat.shared.resources.durio_register_invite_missing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Sign-up on an invite-only server: the code goes into the body, its errors are explained
class RegistrationInviteTest {
    private fun json(body: RegistrationBody) = StoatJson.encodeToString(RegistrationBody.serializer(), body)

    @Test
    fun theInviteCodeIsSent() {
        val body = RegistrationInvite.body("a@b.c", "pw", "captcha", "  K7x9Q2  ")
        assertEquals("K7x9Q2", body.invite)
        assertTrue(json(body).contains("\"invite\":\"K7x9Q2\""))
    }

    @Test
    fun aBlankCodeIsLeftOut() {
        val body = RegistrationInvite.body("a@b.c", "pw", null, " ")
        assertNull(body.invite)
        assertEquals("", body.captcha)
        assertFalse(json(body).contains("\"invite\":\""))
    }

    @Test
    fun aPastedLinkGivesItsCode() {
        val link = RegistrationInvite.parse(" https://duri0.duckdns.org/join/K7x9Q2abcd ")
        assertEquals("K7x9Q2abcd", link.code)
        assertEquals("duri0.duckdns.org", link.host)
        assertEquals("K7x9Q2abcd", RegistrationInvite.parse("duri0.duckdns.org/join/K7x9Q2abcd/").code)
        assertEquals("K7x9Q2abcd", RegistrationInvite.parse("https://Duri0.DuckDNS.org:8443/app/join/K7x9Q2abcd?ref=x#y").code)
        assertEquals("duri0.duckdns.org", RegistrationInvite.parse("https://Duri0.DuckDNS.org:8443/join/K7x9Q2abcd").host)
        assertEquals("K7x9Q2abcd", RegistrationInvite.body("a@b.c", "pw", null, "https://duri0.duckdns.org/join/K7x9Q2abcd").invite)

        // a bare code stays as it is, without a host
        assertEquals(RegistrationInvite.Parsed("K7x9Q2abcd", null), RegistrationInvite.parse("K7x9Q2abcd"))
        // a link without an invite in it is no link of ours: sent as typed, the server refuses it
        assertNull(RegistrationInvite.parse("https://example.com/other/K7").host)
    }

    @Test
    fun aServerInviteIsNoSignUpInvite() {
        val guild = RegistrationInvite.parse("https://duri0.duckdns.org/invite/AbCd1234")
        assertTrue(guild.serverInvite)
        assertEquals("", guild.code)
        assertEquals("duri0.duckdns.org", guild.host)
        // nothing of it is sent, and an invite-only server still wants a code
        assertNull(RegistrationInvite.body("a@b.c", "pw", null, "https://duri0.duckdns.org/invite/AbCd1234").invite)
        assertFalse(RegistrationInvite.complete(inviteOnly = true, invite = "duri0.duckdns.org/invite/AbCd1234"))
        assertFalse(RegistrationInvite.parse("https://duri0.duckdns.org/join/K7").serverInvite)
        assertFalse(RegistrationInvite.parse("K7").serverInvite)
    }

    @Test
    fun aLinkOfAnotherServerIsPointedOut() {
        val server = ServerEndpoints(
            api = "https://duri0.duckdns.org/api",
            ws = "wss://duri0.duckdns.org/ws",
            files = "https://duri0.duckdns.org/autumn",
            proxy = "",
            webApp = "https://duri0.duckdns.org",
        )
        assertFalse(RegistrationInvite.otherServer("https://duri0.duckdns.org/join/K7", server))
        assertFalse(RegistrationInvite.otherServer("https://DURI0.duckdns.org/join/K7", server))
        assertTrue(RegistrationInvite.otherServer("https://other.duckdns.org/join/K7", server))
        assertFalse(RegistrationInvite.otherServer("K7", server))
        assertFalse(RegistrationInvite.otherServer("https://other.duckdns.org/join/K7", null))
        assertEquals("duri0.duckdns.org", RegistrationInvite.hostOf(server.api))
    }

    @Test
    fun inviteErrorsAndTheRequiredField() {
        assertEquals(Res.string.durio_register_invite_missing, RegistrationInvite.errorText("MissingInvite"))
        assertEquals(Res.string.durio_register_invite_invalid, RegistrationInvite.errorText("InvalidInvite"))
        assertNull(RegistrationInvite.errorText("EmailInUse"))

        assertFalse(RegistrationInvite.complete(inviteOnly = true, invite = " "))
        assertTrue(RegistrationInvite.complete(inviteOnly = true, invite = "K7"))
        assertTrue(RegistrationInvite.complete(inviteOnly = null, invite = ""))
        assertTrue(RegistrationInvite.complete(inviteOnly = false, invite = ""))
    }
}
