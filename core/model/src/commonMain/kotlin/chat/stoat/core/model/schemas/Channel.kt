package chat.stoat.core.model.schemas

import chat.stoat.core.model.fork.ChannelEncryption
import chat.stoat.core.model.fork.ThreadInfo
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class MessagesInChannel(
    val messages: List<Message>? = null,
    val users: List<User>? = null,
    val members: List<Member>? = null
)

@Serializable
data class ServerUserChoice(
    val server: String,
    val user: String
)

@Serializable
data class Member(
    @SerialName("_id")
    val id: ServerUserChoice? = null,

    @SerialName("joined_at")
    val joinedAt: String? = null,

    val avatar: AutumnResource? = null,
    val roles: List<String>? = null,
    val nickname: String? = null,
    val pronouns: String? = null,

    val timeout: String? = null,

    @SerialName("can_publish")
    val canPublish: Boolean? = null,

    @SerialName("can_receive")
    val canReceive: Boolean? = null,
) {
    fun mergeWithPartial(other: Member): Member {
        return Member(
            id = other.id ?: id,
            joinedAt = other.joinedAt ?: joinedAt,
            avatar = other.avatar ?: avatar,
            roles = other.roles ?: roles,
            nickname = other.nickname ?: nickname,
            pronouns = other.pronouns ?: pronouns,
            timeout = other.timeout ?: timeout,
            canPublish = other.canPublish ?: canPublish,
            canReceive = other.canReceive ?: canReceive,
        )
    }

    fun timeoutTimestamp(): Instant? {
        return timeout?.let { Instant.parse(it) }
    }
}

@Serializable
data class Channel(
    @SerialName("_id")
    val id: String? = null,
    @SerialName("channel_type")
    val channelType: ChannelType? = null,
    val user: String? = null,
    val name: String? = null,
    val owner: String? = null,
    val description: String? = null,
    val recipients: List<String>? = null,
    val icon: AutumnResource? = null,
    @SerialName("last_message_id")
    val lastMessageID: String? = null,
    val active: Boolean? = null,
    val permissions: Long? = null,
    val server: String? = null,
    @SerialName("role_permissions")
    val rolePermissions: Map<String, PermissionDescription>? = null,
    @SerialName("default_permissions")
    val defaultPermissions: PermissionDescription? = null,
    val nsfw: Boolean? = null,
    val voice: VoiceInformation? = null,
    val slowmode: Long? = null,
    val type: String? = null, // this is _only_ used for websocket events!
    // durio: E2EE of a DM or group, irreversible (plan AN-27, AN-39)
    val encryption: ChannelEncryption? = null,
    // durio: set when this server channel is a thread of another channel
    val thread: ThreadInfo? = null,
    // durio: threads of this channel are deleted after this many days without a message
    @SerialName("thread_auto_delete")
    val threadAutoDelete: Int? = null,
    // durio: permissions of single members in this server channel
    @SerialName("user_permissions")
    val userPermissions: Map<String, PermissionDescription>? = null,
) {
    fun mergeWithPartial(partial: Channel): Channel {
        return Channel(
            channelType = partial.channelType ?: channelType,
            id = partial.id ?: id,
            user = partial.user ?: user,
            name = partial.name ?: name,
            owner = partial.owner ?: owner,
            description = partial.description ?: description,
            recipients = partial.recipients ?: recipients,
            icon = partial.icon ?: icon,
            lastMessageID = partial.lastMessageID ?: lastMessageID,
            active = partial.active ?: active,
            permissions = partial.permissions ?: permissions,
            server = partial.server ?: server,
            rolePermissions = partial.rolePermissions ?: rolePermissions,
            defaultPermissions = partial.defaultPermissions ?: defaultPermissions,
            nsfw = partial.nsfw ?: nsfw,
            voice = partial.voice ?: voice,
            slowmode = partial.slowmode ?: slowmode,
            type = partial.type ?: type,
            encryption = partial.encryption ?: encryption,
            thread = partial.thread ?: thread,
            threadAutoDelete = partial.threadAutoDelete ?: threadAutoDelete,
            userPermissions = partial.userPermissions ?: userPermissions,
        )
    }
}

@Serializable
data class VoiceInformation(
    @SerialName("max_users")
    val maxUsers: Int? = null
)

@Serializable
data class ChannelSlowmode(
    @SerialName("channel_id")
    val channelId: String,
    val duration: Long,
    @SerialName("retry_after")
    val retryAfter: Long
)

@Serializable
enum class ChannelType(val value: String) {
    DirectMessage("DirectMessage"),
    Group("Group"),
    SavedMessages("SavedMessages"),
    TextChannel("TextChannel"),
    VoiceChannel("VoiceChannel");

    companion object : KSerializer<ChannelType> {
        override val descriptor: SerialDescriptor
            get() {
                return PrimitiveSerialDescriptor(
                    "chat.stoat.core.model.schemas.ChannelType",
                    PrimitiveKind.STRING
                )
            }

        override fun deserialize(decoder: Decoder): ChannelType =
            when (val value = decoder.decodeString()) {
                "DirectMessage" -> DirectMessage
                "Group" -> Group
                "SavedMessages" -> SavedMessages
                "TextChannel" -> TextChannel
                "VoiceChannel" -> VoiceChannel
                else -> throw IllegalArgumentException("ChannelType could not parse: $value")
            }

        override fun serialize(encoder: Encoder, value: ChannelType) {
            return encoder.encodeString(value.value)
        }
    }
}

@Serializable
data class ChannelUserChoice(
    val channel: String,
    val user: String
)

@Serializable
data class ChannelUnreadResponse(
    @SerialName("_id")
    val id: ChannelUserChoice,
    val last_id: String? = null,
    val mentions: List<String>? = null
)

@Serializable
data class ChannelUnread(
    @SerialName("_id")
    val id: String,
    val last_id: String? = null,
    val mentions: List<String>? = null
)
