package chat.stoat.api.fork.e2ee

import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.e2ee.E2eeResponse
import chat.stoat.api.fork.routes.e2ee.E2eeRoutes
import chat.stoat.api.fork.routes.e2ee.ackToDevice
import chat.stoat.api.fork.routes.e2ee.executeE2eeRequest
import chat.stoat.api.fork.routes.e2ee.fetchKeysCount
import chat.stoat.api.fork.routes.e2ee.fetchToDevice
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the crypto core's network side for one account (plan AN-21, AN-24): request passes, the
 * to-device mailbox, one-time key counts, and the send preparation. The core does no HTTP and
 * has no timers; this is its host, following the contract in
 * `stoat-crypto/crates/stoat-crypto/src/lib.rs` step by step:
 *
 * - [start] (after opening and `setServerLimits`): a request pass;
 * - [onReady] (socket `Ready`, every connect): `receiveReconnect`, then **always** the mailbox
 *   (a doorbell lost while offline is never repeated) and `keys/count`, then a pass;
 * - [onToDevice] (`E2eeToDevice` doorbell): mailbox pages of 100 → core → ack → `markAckSent`
 *   while the page was full; [onKeysLow]: `keys/count`;
 * - [onDeviceListUpdate], [onChannelMembers]: into the core, then a pass;
 * - after every cycle the core's `wakeupInMs` schedules the next pass.
 *
 * One actor per account: triggers are merged and handled by one coroutine, and a mutex
 * serializes cycles with [prepareSend] (the core does not want parallel claims/shares for a
 * channel). Each attempt of a request is marked in the core exactly once. Network errors, 5xx
 * and 429 back off (429 by its `retry_after`); other 4xx are marked failed and the core decides
 * when to retry (`wakeupInMs`). 401 stops everything: the account needs a new login.
 *
 * HTTP goes through the account's own [client] (AN-15), never the global one: push handling
 * runs this for accounts that are not active (AN-29).
 */
class E2eeSync(
    private val core: E2eeCore,
    private val client: ApiClient,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
) {
    enum class State { Idle, Running, BackingOff, Unauthorized, Stopped }

    private sealed interface Trigger {
        data object Pass : Trigger
        data object Retry : Trigger
        data object Ready : Trigger
        data object Mailbox : Trigger
        data object KeysCount : Trigger
        data class DeviceList(val user: String) : Trigger
        data class Members(val channelId: String, val members: List<String>, val settings: E2eeChannelSettings) : Trigger
    }

    // What is still to do; a flag is cleared only once its step went through
    private class Pending {
        var ready = false
        var mailbox = false
        var keysCount = false
        val users = linkedSetOf<String>()
        val members = linkedMapOf<String, Trigger.Members>()

        fun add(trigger: Trigger) {
            when (trigger) {
                Trigger.Pass, Trigger.Retry -> Unit
                Trigger.Ready -> ready = true
                Trigger.Mailbox -> mailbox = true
                Trigger.KeysCount -> keysCount = true
                is Trigger.DeviceList -> users += trigger.user
                is Trigger.Members -> members[trigger.channelId] = trigger
            }
        }
    }

    private sealed interface Step {
        data object Ok : Step

        /** Marked failed with a 4xx; the core schedules the retry. */
        data object Rejected : Step

        /** Network, 5xx or 429: back off; [delayMs] from the server if it said so. */
        data class Retry(val delayMs: Long?) : Step

        data object Unauthorized : Step
    }

    private val triggers = Channel<Trigger>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val pending = Pending()
    private val inFlight = mutableSetOf<String>()
    private var loop: Job? = null
    private var timer: Job? = null
    private var holding = false

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<E2eeSyncEvent>(extraBufferCapacity = 64)

    /** Room keys (re-decrypt, AN-25), verification flows (AN-31), actions for the UI, 401. */
    val events: SharedFlow<E2eeSyncEvent> = _events.asSharedFlow()

    fun start() {
        if (loop != null) return
        loop = scope.launch { run() }
        triggers.trySend(Trigger.Pass)
    }

    /** Stops the loop; the core stays open (its owner closes it). */
    fun stop() {
        timer?.cancel()
        loop?.cancel()
        triggers.close()
        if (_state.value != State.Unauthorized) _state.value = State.Stopped
    }

    fun requestPass() {
        triggers.trySend(Trigger.Pass)
    }

    /** Socket `Ready` after every (re)connect. */
    fun onReady() {
        triggers.trySend(Trigger.Ready)
    }

    /** `E2eeToDevice` for this device: only a doorbell, the content is in the mailbox. */
    fun onToDevice() {
        triggers.trySend(Trigger.Mailbox)
    }

    fun onKeysLow() {
        triggers.trySend(Trigger.KeysCount)
    }

    fun onDeviceListUpdate(user: String) {
        triggers.trySend(Trigger.DeviceList(user))
    }

    /** The full member list of an encrypted DM/group (load, Ready, join/leave, enabled). */
    fun onChannelMembers(channelId: String, members: List<String>, settings: E2eeChannelSettings = E2eeChannelSettings()) {
        triggers.trySend(Trigger.Members(channelId, members, settings))
    }

    /**
     * Runs the core's `prepareSend` requests for [channelId] until none are left (contract step
     * 7); then the caller may `encrypt`. Serialized with the background cycles.
     *
     * The core must know the channel (`UnknownChannel` otherwise): pass [members] when the send
     * path has the member list at hand, they go into the core first — [onChannelMembers] is
     * asynchronous and may not have run yet.
     *
     * @throws E2eeSendPreparationException if a request failed: the message is not sent
     */
    suspend fun prepareSend(
        channelId: String,
        members: List<String>? = null,
        settings: E2eeChannelSettings = E2eeChannelSettings(),
    ) = mutex.withLock {
        if (members != null) core.setChannelMembers(channelId, members, settings)
        repeat(MAX_PASSES) {
            val requests = core.prepareSend(channelId).filter { it.id !in inFlight }
            if (requests.isEmpty()) return@withLock
            for (request in requests) {
                when (runRequest(request)) {
                    Step.Ok -> Unit
                    Step.Unauthorized -> {
                        unauthorized()
                        throw E2eeSendPreparationException(retryable = false)
                    }
                    Step.Rejected -> throw E2eeSendPreparationException(retryable = false)
                    is Step.Retry -> throw E2eeSendPreparationException(retryable = true)
                }
            }
        }
        throw E2eeSendPreparationException(retryable = true)
    }

    /**
     * One mailbox fetch and request pass right now, for push handling (AN-29): a room key that
     * came with the push's message is in the mailbox. Serialized with the loop; room keys go out
     * on [events] as usual. Returns whether the mailbox could be read.
     */
    suspend fun syncOnce(): Boolean = mutex.withLock {
        try {
            if (mailbox() != Step.Ok) return@withLock false
            requestPasses()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w { "E2EE one-off sync failed: ${e::class.simpleName}" }
            false
        }
    }

    /**
     * Runs one request the user started (the backup version of a recovery setup, AN-32) right
     * now, serialized with the loop, and marks it in the core; a request pass follows (secret
     * and key uploads). [mfaTicket] goes along as `x-mfa-ticket`.
     *
     * @throws E2eeException.ActionRequired [E2eeAction.MfaRequired]: get a ticket and run the
     *   same request again with it
     * @throws E2eeRequestException any other failure; [E2eeRequestException.status] 0 without
     *   an answer
     */
    suspend fun runNow(request: E2eeRequest, mfaTicket: String? = null) {
        mutex.withLock {
            val response = try {
                executeE2eeRequest(client, request, mfaTicket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w { "E2EE ${request.kind} failed: ${e::class.simpleName}" }
                core.markRequestFailed(request, 0)
                throw E2eeRequestException(0)
            }
            if (response.isSuccess) {
                core.markRequestSent(request, response.body)
            } else {
                Logger.w { "E2EE ${request.kind} failed: ${response.status}" }
                // a 401 here is the missing MFA ticket: the core says so (ActionRequired)
                core.markRequestFailed(request, response.status)
                if (response.status == 401) unauthorized()
                throw E2eeRequestException(response.status)
            }
        }
        requestPass()
    }

    private suspend fun run() {
        for (first in triggers) {
            pending.add(first)
            var next = triggers.tryReceive().getOrNull()
            var resume = first == Trigger.Retry || first == Trigger.Ready
            while (next != null) {
                pending.add(next)
                if (next == Trigger.Retry || next == Trigger.Ready) resume = true
                next = triggers.tryReceive().getOrNull()
            }
            // backing off: collect, run when the timer fires (or the socket is back)
            if (holding && !resume) continue
            holding = false

            _state.value = State.Running
            val step = try {
                mutex.withLock { cycle() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a core error (bad response, store trouble): what is pending is kept, try later
                Logger.e { "E2EE cycle failed: ${e::class.simpleName}" }
                Step.Retry(null)
            }

            when (step) {
                Step.Ok, Step.Rejected -> {
                    backoff.reset()
                    _state.value = State.Idle
                    val wakeup = try {
                        core.wakeupInMs()
                    } catch (e: E2eeException) {
                        null
                    }
                    schedule(wakeup, Trigger.Pass)
                }
                is Step.Retry -> {
                    holding = true
                    _state.value = State.BackingOff
                    schedule(step.delayMs ?: backoff.next(), Trigger.Retry)
                }
                Step.Unauthorized -> {
                    unauthorized()
                    return
                }
            }
        }
    }

    private fun schedule(delayMs: Long?, trigger: Trigger) {
        timer?.cancel()
        if (delayMs == null) return
        timer = scope.launch {
            delay(delayMs)
            triggers.trySend(trigger)
        }
    }

    private suspend fun unauthorized() {
        timer?.cancel()
        _state.value = State.Unauthorized
        _events.emit(E2eeSyncEvent.Unauthorized)
        triggers.close()
    }

    // Contract order: reconnect → device lists → members → mailbox → key counts → request pass
    private suspend fun cycle(): Step {
        // local steps: an input the core refuses is dropped, it must not block the mailbox
        if (pending.ready) {
            local("reconnect") { core.receiveReconnect() }
            pending.ready = false
            pending.mailbox = true
            pending.keysCount = true
        }
        if (pending.users.isNotEmpty()) {
            local("device list") { core.receiveDeviceListUpdate(pending.users.toList()) }
            pending.users.clear()
        }
        while (pending.members.isNotEmpty()) {
            val (channelId, m) = pending.members.entries.first()
            local("members") { core.setChannelMembers(channelId, m.members, m.settings) }
            pending.members.remove(channelId)
        }
        if (pending.mailbox) {
            mailbox().let { if (it != Step.Ok) return it }
            pending.mailbox = false
        }
        if (pending.keysCount) {
            keysCount().let { if (it != Step.Ok) return it }
            pending.keysCount = false
        }
        return requestPasses()
    }

    private inline fun local(step: String, block: () -> Unit) {
        try {
            block()
        } catch (e: E2eeException) {
            Logger.w { "E2EE $step refused: ${e::class.simpleName}" }
        }
    }

    private suspend fun mailbox(): Step {
        repeat(MAX_MAILBOX_PAGES) {
            val page = call { fetchToDevice(client) }
            if (page !is Called.Response) return (page as Called.Failed).step
            page.response.failure()?.let { return it }

            val outcome = core.receiveToDevice(page.response.body)
            if (outcome.roomKeys.isNotEmpty()) _events.emit(E2eeSyncEvent.RoomKeys(outcome.roomKeys))
            if (outcome.verifications.isNotEmpty()) {
                _events.emit(E2eeSyncEvent.Verifications(outcome.verifications))
            }

            for (ids in outcome.ackIds.chunked(E2eeRoutes.ACK_LIMIT)) {
                val ack = call { ackToDevice(client, ids) }
                if (ack !is Called.Response) return (ack as Called.Failed).step
                ack.response.failure()?.let { return it }
                core.markAckSent(ids)
            }

            if (outcome.fetched < E2eeRoutes.TO_DEVICE_LIMIT) return Step.Ok
        }
        // a very long mailbox: let other work in, continue in the next cycle
        triggers.trySend(Trigger.Mailbox)
        return Step.Ok
    }

    private suspend fun keysCount(): Step {
        val counts = call { fetchKeysCount(client) }
        if (counts !is Called.Response) return (counts as Called.Failed).step
        counts.response.failure()?.let { return it }
        core.receiveOtkCounts(counts.response.body)
        return Step.Ok
    }

    // Until a pass returns nothing new; after a rejected request the core's wakeup takes over
    private suspend fun requestPasses(): Step {
        repeat(MAX_PASSES) {
            val requests = core.outgoingRequests().filter { it.id !in inFlight }
            if (requests.isEmpty()) return Step.Ok
            var rejected = false
            for (request in requests) {
                when (val step = runRequest(request)) {
                    Step.Ok -> Unit
                    Step.Rejected -> rejected = true
                    is Step.Retry, Step.Unauthorized -> return step
                }
            }
            if (rejected) return Step.Rejected
        }
        // still busy (a long backup upload): continue after other work
        triggers.trySend(Trigger.Pass)
        return Step.Ok
    }

    /** One attempt of one request, marked in the core exactly once. */
    private suspend fun runRequest(request: E2eeRequest): Step {
        inFlight += request.id
        try {
            val called = call { executeE2eeRequest(client, request) }
            if (called !is Called.Response) {
                core.markRequestFailed(request, 0)
                return (called as Called.Failed).step
            }
            val response = called.response
            if (response.isSuccess) {
                core.markRequestSent(request, response.body)
                return Step.Ok
            }
            Logger.w { "E2EE ${request.kind} failed: ${response.status}" }
            try {
                core.markRequestFailed(request, response.status)
            } catch (e: E2eeException.ActionRequired) {
                _events.emit(E2eeSyncEvent.ActionRequired(e.action))
            }
            return when {
                response.status == 401 -> Step.Unauthorized
                response.status == 429 -> Step.Retry(response.retryAfterMs)
                response.status >= 500 -> Step.Retry(null)
                else -> Step.Rejected
            }
        } finally {
            inFlight -= request.id
        }
    }

    private sealed interface Called {
        class Response(val response: E2eeResponse) : Called
        class Failed(val step: Step) : Called
    }

    private suspend fun call(block: suspend () -> E2eeResponse): Called = try {
        Called.Response(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w { "E2EE request failed: ${e::class.simpleName}" }
        Called.Failed(Step.Retry(null))
    }

    // Mailbox, ack and key counts: null for 2xx; any other answer is retried later
    // (404: E2EE switched off on the server; 400: nothing this client can fix right now)
    private fun E2eeResponse.failure(): Step? = when {
        isSuccess -> null
        status == 401 -> Step.Unauthorized
        status == 429 -> Step.Retry(retryAfterMs)
        else -> {
            Logger.w { "E2EE mailbox/key count failed: $status" }
            Step.Retry(null)
        }
    }

    companion object {
        private const val MAX_PASSES = 16
        private const val MAX_MAILBOX_PAGES = 50
    }
}

sealed interface E2eeSyncEvent {
    data class RoomKeys(val keys: List<E2eeRoomKey>) : E2eeSyncEvent
    data class Verifications(val flowIds: List<String>) : E2eeSyncEvent
    data class ActionRequired(val action: E2eeAction) : E2eeSyncEvent
    data object Unauthorized : E2eeSyncEvent
}

/** The message could not be prepared for encryption; [retryable]: network or server trouble. */
class E2eeSendPreparationException(val retryable: Boolean) :
    Exception(if (retryable) "Send preparation failed, retry later" else "Send preparation rejected")

/** A request the user started failed; [status] 0: no answer. */
class E2eeRequestException(val status: Int) : Exception("E2EE request failed: $status")

/** Exponential backoff for network failures: 1 s doubling up to 5 min. */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 300_000) {
    private var current = 0L

    fun next(): Long {
        current = if (current == 0L) initialMs else (current * 2).coerceAtMost(maxMs)
        return current
    }

    fun reset() {
        current = 0L
    }
}
