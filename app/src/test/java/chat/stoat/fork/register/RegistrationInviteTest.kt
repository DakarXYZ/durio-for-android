package chat.stoat.fork.register

import chat.stoat.api.StoatJson
import chat.stoat.api.routes.account.RegistrationBody
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
