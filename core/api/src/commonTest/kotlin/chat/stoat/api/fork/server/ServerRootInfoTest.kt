package chat.stoat.api.fork.server

import chat.stoat.api.StoatJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// `GET /` → features.limits (backend root.rs): the attachment limit the clients follow
class ServerRootInfoTest {
    private fun parse(json: String) = StoatJson.decodeFromString(ServerRootInfo.serializer(), json)

    @Test
    fun attachmentLimitOfTheDefaultLimits() {
        val info = parse(
            """{"revolt":"0.16.0","features":{"limits":{
                "global":{"group_size":100,"body_limit_size":105000000},
                "new_user":{"message_attachments":5,"file_upload_size_limits":{"attachments":20000000,"avatars":4000000}},
                "default":{"message_attachments":5,"video":true,"file_upload_size_limits":{"attachments":100000000,"avatars":4000000}}
            }}}"""
        )
        assertEquals(100_000_000L, info.attachmentLimitBytes)
    }

    @Test
    fun noLimitWhenTheServerDoesNotSay() {
        assertNull(parse("""{"revolt":"0.8.0","features":{}}""").attachmentLimitBytes)
        assertNull(parse("""{"features":{"limits":{"default":{"file_upload_size_limits":{"avatars":1}}}}}""").attachmentLimitBytes)
        assertNull(parse("""{"features":{"limits":{"default":{"file_upload_size_limits":{"attachments":0}}}}}""").attachmentLimitBytes)
    }
}
