package chat.stoat.api.fork.routes

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadRoutesTest {
    @Test
    fun threadNameFromTheFirstLine() {
        assertEquals("Plans for Friday", threadNameFrom("  \n**Plans** for Friday <@01ABCDEFGHJKMNPQRSTVWXYZ00>\nmore"))
        assertEquals("", threadNameFrom(null))
        assertEquals("", threadNameFrom("<@01ABCDEFGHJKMNPQRSTVWXYZ00>"))

        val long = "x".repeat(150)
        val name = threadNameFrom(long)
        assertEquals(MAX_THREAD_NAME, name.length)
        assertEquals('…', name.last())
    }
}
