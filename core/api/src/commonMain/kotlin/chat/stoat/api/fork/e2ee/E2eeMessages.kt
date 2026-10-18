package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.realtime.frames.receivable.MessageUpdateFrame
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.MessagesInChannel
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile

/**
 * Where the active account's messages meet its [MessageDecryptor] (plan AN-25), called by one
 * line at each entry point: the socket's `Message` and `MessageUpdate`, history pages and single
 * messages (the channel routes), pins. Without a decryptor — no E2EE device for this account —
 * encrypted messages are hidden with [DecryptionStatus.Failed.Reason.NoDevice]; once one is
 * installed they are decrypted again.
 *
 * Routes called for another account (push, notification actions: a `client` that is not
 * [ApiClient.Active]) are left alone: they decrypt with that account's own core (AN-29).
 */
object E2eeMessages {
    @Volatile
    private var decryptor: MessageDecryptor? = null

    val installed: Boolean
        get() = decryptor != null

    /** E2EE of the active account is ready ([decryptor]) or stopped (null). */
    suspend fun install(decryptor: MessageDecryptor?) {
        this.decryptor = decryptor
        if (decryptor == null) {
            E2eeState.clear()
        } else {
            redecrypt(StoatAPI.messageCache.values.filter { it.encrypted != null && it.id != null }.mapNotNull { it.id })
        }
    }

    /** Socket `Message`, before the cache and the UI see it. */
    suspend fun incoming(message: Message): Message =
        if (message.encrypted == null) message else decrypt(listOf(message)).single()

    /**
     * Socket `MessageUpdate` of a cached message: [merged] is the cached message with the update
     * applied. Returns what goes into the cache and the frame the UI gets — for an encrypted
     * message rewritten with the plaintext (the view model decodes the frame's `data` itself).
     */
    suspend fun updated(frame: MessageUpdateFrame, old: Message, merged: Message): Pair<Message, MessageUpdateFrame> {
        if (merged.encrypted == null) return merged to frame
        // reactions, pins and the like change nothing encrypted: keep what is shown
        if (merged.encrypted == old.encrypted && E2eeState.status[old.id] != null) {
            val kept = merged.copy(content = old.content, attachments = old.attachments, embeds = old.embeds, mentions = old.mentions)
            return kept to frame.withData(kept)
        }
        val previous = old.id?.let { mapOf(it to old) }.orEmpty()
        val decrypted = decrypt(listOf(merged), previous).single()
        return decrypted to frame.withData(decrypted)
    }

    /** History page of [client]'s account. */
    suspend fun history(page: MessagesInChannel, client: ApiClient = ApiClient.Active): MessagesInChannel {
        val messages = page.messages ?: return page
        if (client != ApiClient.Active || messages.none { it.encrypted != null }) return page
        return page.copy(messages = decrypt(messages))
    }

    suspend fun history(messages: List<Message>, client: ApiClient = ApiClient.Active): List<Message> =
        if (client != ApiClient.Active || messages.none { it.encrypted != null }) messages else decrypt(messages)

    suspend fun single(message: Message, client: ApiClient = ApiClient.Active): Message =
        if (client != ApiClient.Active) message else incoming(message)

    /** New room keys came in: messages waiting for them are decrypted and re-shown. */
    suspend fun onRoomKeys(keys: List<E2eeRoomKey>) {
        val decryptor = decryptor ?: return
        redecrypt(decryptor.waitingFor(keys).toList())
    }

    /**
     * Keys came from elsewhere than the mailbox (a backup restore, AN-32): every message still
     * waiting for its key is decrypted again.
     */
    suspend fun retryWaiting() {
        if (decryptor == null) return
        redecrypt(E2eeState.status.filterValues { it == DecryptionStatus.Pending }.keys.toList())
    }

    private suspend fun redecrypt(ids: List<String>) {
        if (ids.isEmpty()) return
        val cached = ids.mapNotNull { StoatAPI.messageCache[it] }.filter { it.encrypted != null }
        if (cached.isEmpty()) return
        val decrypted = decrypt(cached)
        for (message in decrypted) {
            val id = message.id ?: continue
            val channel = message.channel ?: continue
            if (StoatAPI.messageCache[id]?.encrypted != message.encrypted) continue // edited meanwhile
            StoatAPI.messageCache[id] = message
            // the screen re-creates the item (and its markdown) from an update frame
            StoatAPI.wsFrameChannel.emit(MessageUpdateFrame(id = id, channel = channel, data = message.toJsonObject()))
        }
    }

    private suspend fun decrypt(messages: List<Message>, previous: Map<String, Message> = emptyMap()): List<Message> {
        val decryptor = decryptor
        if (decryptor != null) return decryptor.decrypt(messages, previous)
        return messages.map { message ->
            if (message.encrypted == null || message.id == null) return@map message
            E2eeState.status[message.id!!] = DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.NoDevice)
            message.copy(content = null, attachments = null, embeds = null)
        }
    }

    private fun MessageUpdateFrame.withData(message: Message) = copy(data = message.toJsonObject())

    private fun Message.toJsonObject() = StoatJson.encodeToJsonElement(Message.serializer(), this).jsonObject
}
