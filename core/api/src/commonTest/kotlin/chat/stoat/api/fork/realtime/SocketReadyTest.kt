package chat.stoat.api.fork.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

// A socket the server closes (block 5E, load-chaos.md B3): the reconnect does not wait for the timeout
@OptIn(ExperimentalCoroutinesApi::class)
class SocketReadyTest {
    @Test
    fun readyWins() = runTest {
        val ready = CompletableDeferred<Unit>()
        val connection = async { ready.complete(Unit); delay(60_000) }
        assertEquals(true, awaitReadyOrClosed(ready, connection, 20.seconds))
        connection.cancel()
    }

    @Test
    fun closedBeforeReadyEndsTheWaitAtOnce() = runTest {
        val ready = CompletableDeferred<Unit>()
        // the server closes the socket 300 ms after it opened, before Ready
        val connection = async { delay(300) }
        assertEquals(false, awaitReadyOrClosed(ready, connection, 20.seconds))
        assertEquals(300, currentTime)
    }

    @Test
    fun brokenConnectionIsThrown() = runTest {
        val ready = CompletableDeferred<Unit>()
        val connection = CompletableDeferred<Unit>()
        connection.completeExceptionally(IllegalStateException("reset"))
        assertFailsWith<IllegalStateException> { awaitReadyOrClosed(ready, connection, 20.seconds) }
    }

    @Test
    fun silenceTimesOut() = runTest {
        val ready = CompletableDeferred<Unit>()
        val connection = CompletableDeferred<Unit>()
        assertNull(awaitReadyOrClosed(ready, connection, 20.seconds))
        assertEquals(20_000, currentTime)
    }
}
