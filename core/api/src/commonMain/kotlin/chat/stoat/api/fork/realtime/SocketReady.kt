package chat.stoat.api.fork.realtime

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Waits for the socket's `Ready` ([ready]) or the end of the [connection], whichever comes first
 * (block 5E). The server may close a fresh socket before `Ready` — bonfire closes every socket
 * after an error of its Redis subscriber (`load-chaos.md` B3) — and the reconnect must not wait
 * for the ready timeout then.
 *
 * @return true: `Ready`; false: the connection ended first; null: neither within [timeout]
 */
internal suspend fun awaitReadyOrClosed(ready: Deferred<Unit>, connection: Deferred<Unit>, timeout: Duration): Boolean? =
    withTimeoutOrNull(timeout) {
        select {
            ready.onAwait { true }
            connection.onAwait { false }
        }
    }
