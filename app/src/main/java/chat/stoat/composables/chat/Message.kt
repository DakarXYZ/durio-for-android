package chat.stoat.composables.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.icu.text.DateFormat
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import chat.stoat.R
import chat.stoat.fork.e2ee.ui.EncryptedMessageNotice
import chat.stoat.fork.polls.PollCard
import chat.stoat.fork.archive.ArchiveBadge
import chat.stoat.fork.archive.ArchivedMessages
import chat.stoat.fork.media.GridPicture
import chat.stoat.fork.media.LinkPreviews
import chat.stoat.fork.media.MessagePictures
import chat.stoat.fork.media.PictureGrid
import chat.stoat.fork.media.PictureGridPanels
import chat.stoat.activities.media.ImageViewActivity
import chat.stoat.activities.media.VideoViewActivity
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.internals.MessageFlag
import chat.stoat.api.internals.Roles
import chat.stoat.api.internals.SpecialUsers
import chat.stoat.api.internals.ULID
import chat.stoat.api.internals.has
import chat.stoat.api.routes.channel.react
import chat.stoat.api.routes.channel.unreact
import chat.stoat.api.routes.microservices.january.asJanuaryProxyUrl
import chat.stoat.api.settings.Experiments
import chat.stoat.api.settings.LoadedSettings
import chat.stoat.api.settings.MessageReplyStyle
import chat.stoat.callbacks.Action
import chat.stoat.callbacks.ActionChannel
import chat.stoat.composables.generic.RemoteImage
import chat.stoat.fork.messages.RecentEmoji
import chat.stoat.composables.generic.UserAvatar
import chat.stoat.composables.generic.UserAvatarWidthPlaceholder
import chat.stoat.composables.markdown.prose.ChatMarkdown
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.core.model.schemas.AutumnResource
import chat.stoat.core.model.schemas.User
import chat.stoat.core.model.schemas.UserBadges
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.edited
import chat.stoat.shared.resources.message_blocked
import chat.stoat.shared.resources.message_context_sheet_actions_react
import chat.stoat.shared.resources.unknown
import org.jetbrains.compose.resources.stringResource
import chat.stoat.core.model.schemas.has as hasUserBadge
import chat.stoat.internals.text.Gigamoji
import chat.stoat.internals.text.GigamojiState
import chat.stoat.internals.text.MessageProcessor
import chat.stoat.internals.text.stripPUAChars
import chat.stoat.internals.toNavigationAction
import chat.stoat.internals.toStoatWebLinkOrNull
import chat.stoat.media.parseVoiceMessageMetadata
import chat.stoat.persistence.KVStorage
import chat.stoat.ui.colour.BrushCompat
import chat.stoat.ui.colour.solidColor
import chat.stoat.ui.theme.TeamMemberFlairs
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.launch
import chat.stoat.core.model.schemas.Message as MessageSchema

@Composable
fun authorColour(message: MessageSchema): Brush {
    return if (message.masquerade?.colour != null) {
        BrushCompat.parseColour(message.masquerade!!.colour!!)
    } else {
        val defaultColour = Brush.solidColor(LocalContentColor.current)

        val serverId = StoatAPI.channelCache[message.channel]?.server ?: return defaultColour

        val highestRole = message.author?.let {
            Roles.resolveHighestRole(serverId, it, withColour = true)
        } ?: return defaultColour

        highestRole.colour?.let { BrushCompat.parseColour(it) }
            ?: defaultColour
    }
}

@Composable
fun authorRoleIcon(message: MessageSchema): AutumnResource? {
    val serverId = StoatAPI.channelCache[message.channel]?.server ?: return null

    val highestRole = message.author?.let {
        Roles.resolveHighestRole(serverId, it)
    } ?: return null

    return highestRole.icon
}

@Composable
fun displayNameInChannel(userId: String, channelId: String): String {
    val serverId =
        StoatAPI.channelCache[channelId]?.server
            ?: return StoatAPI.userCache[userId]?.let { User.resolveDefaultName(it) }
                ?: stringResource(Res.string.unknown)

    val member = userId.let { StoatAPI.members.getMember(serverId, it) }
        ?: return stringResource(Res.string.unknown)
    return member.nickname
        ?: StoatAPI.userCache[userId]?.let { User.resolveDefaultName(it) }
        ?: stringResource(Res.string.unknown)
}

@Composable
fun authorName(message: MessageSchema): String {
    if (message.masquerade?.name != null) {
        return message.masquerade!!.name!!
    }

    // durio: a webhook's message (e.g. one imported from an archive) has no user behind it
    message.webhook?.name?.takeIf { it.isNotBlank() }?.let { return it }

    val serverId =
        StoatAPI.channelCache[message.channel]?.server
            ?: return StoatAPI.userCache[message.author]?.let { User.resolveDefaultName(it) }
                ?: stringResource(Res.string.unknown)

    val member = message.author?.let { StoatAPI.members.getMember(serverId, it) }
        ?: return stringResource(Res.string.unknown)
    return member.nickname
        ?: StoatAPI.userCache[message.author]?.let { User.resolveDefaultName(it) }
        ?: stringResource(Res.string.unknown)
}

fun authorPronouns(message: MessageSchema, author: User): String? {
    val serverId = StoatAPI.channelCache[message.channel]?.server
        ?: return author.pronouns

    val memberPronouns = message.author
        ?.let { StoatAPI.members.getMember(serverId, it) }
        ?.pronouns

    return memberPronouns ?: author.pronouns
}

internal fun messageTimestampText(pronouns: String?, timestamp: String): String {
    val visiblePronouns = pronouns?.trim()?.takeIf { it.isNotEmpty() }
    return visiblePronouns?.let { "$it · $timestamp" } ?: timestamp
}

@Composable
fun authorAvatarUrl(message: MessageSchema): String? {
    if (message.masquerade?.avatar != null) {
        return asJanuaryProxyUrl(message.masquerade!!.avatar!!)
    }

    // durio: the avatar a webhook's message carries (e.g. one imported from an archive)
    ArchivedMessages.webhookAvatarUrl(message)?.let { return it }

    val serverId =
        StoatAPI.channelCache[message.channel]?.server ?: return null
    val member = message.author?.let { StoatAPI.members.getMember(serverId, it) }
        ?: return null

    return member.avatar?.let { "$STOAT_FILES/avatars/${it.id}" }
}

fun viewUrlInBrowser(ctx: android.content.Context, url: String) {
    val customTab = CustomTabsIntent
        .Builder()
        .build()
    customTab.launchUrl(ctx, Uri.parse(url))
}

fun viewAttachmentInBrowser(ctx: android.content.Context, attachment: AutumnResource) {
    val url = "$STOAT_FILES/attachments/${attachment.id}/${attachment.filename}"
    viewUrlInBrowser(ctx, url)
}

fun formatLongAsTime(time: Long): String {
    val date = java.util.Date(time)

    val withinLastWeek = System.currentTimeMillis() - time < 604800000

    return if (withinLastWeek) {
        val relativeDate = DateUtils.getRelativeTimeSpanString(
            time,
            System.currentTimeMillis(),
            DateUtils.DAY_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_ALL
        )
        val relativeTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)

        if (DateUtils.isToday(time)) {
            return relativeTime
        }
        "$relativeDate $relativeTime"
    } else {
        val absoluteDate = DateFormat.getDateInstance(DateFormat.SHORT).format(date)
        val absoluteTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)

        "$absoluteDate $absoluteTime"
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun Message(
    message: MessageSchema,
    onClick: () -> Unit = {},
    onMessageContextMenu: () -> Unit = {},
    onAvatarClick: () -> Unit = {},
    onNameClick: (() -> Unit)? = null,
    canReply: Boolean = false,
    onReply: () -> Unit = {},
    onAddReaction: () -> Unit = {},
    onJumpToMessage: (String) -> Unit = {},
    fromWebhook: Boolean = false,
    webhookName: String? = null,
    modifier: Modifier = Modifier,
    mdAst: State? = null,
    pictures: MessagePictures.Panels? = null,
    onPictureLongPress: (String) -> Unit = {}
) {
    // durio: a webhook has no user; its placeholder is not waited for (pins, search)
    val author = StoatAPI.userCache[message.author]
        ?: ArchivedMessages.webhookAuthorPlaceholder(message)
        ?: return CircularProgressIndicator()
    // durio: an imported (archived) message's author is a phantom: no profile, sheet or mention
    val imported = message.imported
    val context = LocalContext.current
    val voiceMessageMetadata = remember(author.badges, message.content, message.attachments) {
        if (!author.badges.hasUserBadge(UserBadges.Developer)) {
            return@remember null
        }
        val attachment = message.attachments?.singleOrNull()
        parseVoiceMessageMetadata(message.content).takeIf {
            attachment?.isValidVoiceMessageAttachment() == true
        }
    }

    val scope = rememberCoroutineScope()
    val openMessageLinkOrBrowser: (String) -> Unit = { url ->
        val stoatLink = url.toUri().toStoatWebLinkOrNull()
        if (stoatLink != null) {
            scope.launch {
                ActionChannel.send(stoatLink.toNavigationAction())
            }
        } else {
            viewUrlInBrowser(context, url)
        }
    }
    var kv by remember { mutableStateOf<KVStorage?>(null) }
    var showUsernameDiscriminator by remember { mutableStateOf(false) }
    var ignoreServerAvatar by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (Experiments.enableServerIdentityOptions.isEnabled) {
            val userId = author.id ?: return@LaunchedEffect
            kv = KVStorage(context)
            kv?.let {
                showUsernameDiscriminator =
                    it.getBoolean("exp/serverIdentityOptions/$userId/showUsernameDiscriminator") == true
                ignoreServerAvatar =
                    it.getBoolean("exp/serverIdentityOptions/$userId/ignoreServerAvatar") == true
            }
        }
    }

    val attachmentView = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            // do nothing
        }
    )

    val authorIsBlocked = remember(author) { author.relationship == "Blocked" }

    // durio: the pictures drawn in this message's grid instead of one by one
    val griddedPictures = remember(pictures, message.attachments, message.embeds, message.content) {
        if (pictures != null) PictureGrid.pictures(message) else emptyList()
    }
    val openImageAttachment: (AutumnResource) -> Unit = { attachment ->
        attachmentView.launch(
            Intent(
                context,
                ImageViewActivity::class.java
            ).apply {
                putExtra("autumnResource", StoatJson.encodeToString(AutumnResource.serializer(), attachment))
            }
        )
    }
    val openGridPicture: (GridPicture) -> Unit = { picture ->
        val attachment = picture.attachment
        val embedUrl = picture.embed?.url
        if (attachment != null) {
            openImageAttachment(attachment)
        } else if (embedUrl != null) {
            openMessageLinkOrBrowser(embedUrl)
        }
    }

    var mentionsSelfRole by remember(message) { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val serverId =
            StoatAPI.channelCache[message.channel]?.server ?: return@LaunchedEffect
        var selfMember = StoatAPI.selfId?.let { StoatAPI.members.getMember(serverId, it) }
            ?: return@LaunchedEffect
        var messageRoleMentions = MessageProcessor.findMentionedRoleIDs(message.content)

        mentionsSelfRole = selfMember.roles?.any { it in messageRoleMentions } == true
    }

    Column(modifier.animateContentSize()) {
        if (message.tail == false) {
            // durio: more room above a new author than between their messages (upstream 10dp)
            Spacer(modifier = Modifier.height(18.dp))
        }

        if (authorIsBlocked) {
            Row(
                modifier = Modifier
                    .combinedClickable(
                        onClick = onClick,
                        onDoubleClick = {},
                        onLongClick = {
                            onMessageContextMenu()
                        }
                    )
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth()
            ) {
                UserAvatarWidthPlaceholder()

                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_block_24dp),
                            contentDescription = null
                        )

                        Text(
                            text = stringResource(Res.string.message_blocked),
                            fontSize = 12.sp,
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.then(
                    if ((message.mentions?.contains(StoatAPI.selfId) == true)
                        || mentionsSelfRole
                        || message.flags has MessageFlag.MentionsOnline
                        || message.flags has MessageFlag.MentionsEveryone
                    ) {
                        Modifier.background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    } else {
                        Modifier
                    }
                )
            ) {
                message.replies?.forEach { reply ->
                    val replyMessage = StoatAPI.messageCache[reply]

                    message.channel?.let { chId ->
                        InReplyTo(
                            channelId = chId,
                            messageId = reply,
                            withMention = (replyMessage?.author?.let {
                                message.mentions?.contains(
                                    replyMessage.author
                                )
                            } == true),
                            onMessageClick = onJumpToMessage,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = onClick,
                            onDoubleClick = {
                                if (canReply && LoadedSettings.messageReplyStyle == MessageReplyStyle.DoubleTap) {
                                    onReply()
                                }
                            },
                            onLongClick = {
                                onMessageContextMenu()
                            }
                        )
                        .padding(horizontal = 10.dp)
                        .fillMaxWidth()
                ) {
                    if (message.tail == false) {
                        Column {
                            Spacer(modifier = Modifier.height(4.dp))
                            val webhookAvatar = ArchivedMessages.webhookAvatar(message)
                            UserAvatar(
                                username = webhookName ?: message.webhook?.name ?: User.resolveDefaultName(author),
                                userId = author.id ?: message.id ?: ULID.makeSpecial(0),
                                avatar = webhookAvatar ?: author.avatar,
                                rawUrl = if (ignoreServerAvatar && webhookAvatar == null) null else authorAvatarUrl(message),
                                onClick = if (imported != null) null else onAvatarClick
                            )
                        }
                    } else {
                        UserAvatarWidthPlaceholder()
                    }

                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        if (message.tail == false) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = buildAnnotatedString {
                                        if (showUsernameDiscriminator) {
                                            pushStyle(
                                                SpanStyle(
                                                    color = LocalContentColor.current.copy(
                                                        alpha = 0.5f
                                                    ),
                                                    fontWeight = FontWeight.Bold,
                                                    textDecoration = TextDecoration.LineThrough
                                                )
                                            )
                                        }
                                        append(webhookName ?: authorName(message))
                                        if (showUsernameDiscriminator) {
                                            pop()
                                            append(" ${author.username ?: "<?>"}#${author.discriminator ?: "0000"}")
                                        }
                                    },
                                    style = LocalTextStyle.current.copy(
                                        fontWeight = FontWeight.Bold,
                                        brush = authorColour(message)
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.then(
                                        if (onNameClick != null && imported == null)
                                            Modifier.combinedClickable(
                                                onClick = onNameClick,
                                                onLongClick = {
                                                    onMessageContextMenu()
                                                }
                                            )
                                        else Modifier
                                    )
                                )

                                val roleIcon = authorRoleIcon(message)
                                if (roleIcon != null) {
                                    Spacer(modifier = Modifier.width(4.dp))

                                    RemoteImage(
                                        url = "$STOAT_FILES/icons/${roleIcon.id}",
                                        contentScale = ContentScale.Fit,
                                        description = null,
                                        modifier = Modifier
                                            .size(16.dp)
                                    )
                                }

                                InlineBadges(
                                    bot = author.bot != null && message.masquerade == null,
                                    bridge = message.masquerade != null && author.bot != null,
                                    platformModeration = author.id == SpecialUsers.PLATFORM_MODERATION_USER,
                                    teamMember = author.id in TeamMemberFlairs.TEAM_MEMBER_FLAIRS.keys,
                                    webhook = fromWebhook && imported == null,
                                    colour = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp),
                                    precedingIfAny = {
                                        Spacer(modifier = Modifier.width(5.dp))
                                    }
                                )

                                // durio: an archived message carries a small «Archive» badge instead
                                if (imported != null) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    ArchiveBadge(
                                        imported = imported,
                                        colour = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    )
                                }

                                Spacer(modifier = Modifier.width(5.dp))

                                Text(
                                    text = messageTimestampText(
                                        pronouns = authorPronouns(message, author),
                                        timestamp = formatLongAsTime(ULID.asTimestamp(message.id!!))
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.width(2.dp))

                                if (message.edited != null) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_edit_24dp),
                                        contentDescription = stringResource(Res.string.edited),
                                        tint = MaterialTheme.colorScheme.onBackground.copy(
                                            alpha = 0.5f
                                        ),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // durio: an encrypted message never shows the server's placeholder (plan AN-25, AN-46)
                        val e2eeHidden = EncryptedMessageNotice(message)

                        key(message.content, message.embeds) {
                            message.content?.takeIf { voiceMessageMetadata == null && !e2eeHidden }?.let {
                                val stripped = it.stripPUAChars()
                                // durio: a link standing alone is not shown when its preview is
                                val content = LinkPreviews.visibleContent(stripped, message.embeds)
                                if (content.isBlank()) return@let // if only an attachment is sent
                                val linkHidden = content != stripped

                                val gigamoji = Gigamoji.useGigamojiForMessage(content)
                                val fontSizeMultiplier = when (gigamoji) {
                                    GigamojiState.Single -> 5f
                                    GigamojiState.Multiple -> 2f
                                    GigamojiState.None -> 1f
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                if (mdAst != null && !linkHidden) {
                                    ChatMarkdown(
                                        mdAst,
                                        serverId = StoatAPI.channelCache[message.channel]?.server,
                                        fontSizeMultiplier = fontSizeMultiplier,
                                    )
                                } else {
                                    ChatMarkdown(
                                        content = content,
                                        serverId = StoatAPI.channelCache[message.channel]?.server,
                                        fontSizeMultiplier = fontSizeMultiplier,
                                    )
                                }
                            }
                        }

                        // durio: the poll of the message (dur.io servers)
                        message.poll?.let { poll ->
                            PollCard(message = message, poll = poll, onLongPress = onMessageContextMenu)
                        }

                        // durio: several pictures of the author in a row, drawn as one grid
                        pictures?.takeIf { !e2eeHidden }?.let {
                            PictureGridPanels(
                                panels = it.panels,
                                onOpen = openGridPicture,
                                onLongPress = { picture -> onPictureLongPress(picture.messageId) }
                            )
                        }

                        message.attachments?.takeIf { !e2eeHidden }?.let {
                            it.forEach { attachment ->
                                if (griddedPictures.any { picture -> picture.attachment == attachment }) return@forEach
                                Spacer(modifier = Modifier.height(2.dp))
                                MessageAttachment(
                                    attachment = attachment,
                                    waveform = voiceMessageMetadata?.waveform,
                                    waveformDurationMillis =
                                        voiceMessageMetadata?.durationMillis,
                                ) {
                                    when (attachment.metadata?.type) {
                                        "Image" -> openImageAttachment(attachment)

                                        "Video" -> {
                                            attachmentView.launch(
                                                Intent(
                                                    context,
                                                    VideoViewActivity::class.java
                                                ).apply {
                                                    putExtra("autumnResource", StoatJson.encodeToString(AutumnResource.serializer(), attachment))
                                                }
                                            )
                                        }

                                        "Audio" -> {
                                            /* no-op */
                                        }

                                        else -> {
                                            viewAttachmentInBrowser(context, attachment)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                        }

                        message.embeds?.takeIf { !e2eeHidden }?.let {
                            it.forEach { embed ->
                                if (griddedPictures.any { picture -> picture.embed == embed }) return@forEach
                                when (embed.type) {
                                    "Website", "Text" -> {
                                        val embedIsEmpty =
                                            embed.title == null && embed.description == null && embed.iconURL == null && embed.image == null

                                        if (embedIsEmpty) {
                                            // if we do not emit anything, compose will cause an internal error.
                                            // FIXME if you are doing fixme's anyways then check if this is still an issue
                                            Box {}
                                            return@forEach
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Embed(
                                            embed = embed,
                                            serverId = StoatAPI.channelCache[message.channel]?.server,
                                            onLinkClick = {
                                                openMessageLinkOrBrowser(it)
                                            })
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    "Image" -> {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        BoxWithConstraints(
                                            modifier = Modifier
                                                .clip(MaterialTheme.shapes.medium)
                                                .clickable {
                                                    embed.url?.let {
                                                        openMessageLinkOrBrowser(it)
                                                    }
                                                }
                                        ) {
                                            embed.url?.let { url ->
                                                RemoteImage(
                                                    url = asJanuaryProxyUrl(url),
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier
                                                        .width(
                                                            embed.width?.toInt()?.dp
                                                                ?: maxWidth
                                                        )
                                                        .aspectRatio(embedAspectRatio(embed, 1f)),
                                                    description = null
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }

                                    // durio: a video preview (e.g. a GIF link) as its first frame;
                                    // a tap opens it, as the link it stands for
                                    "Video" -> {
                                        val url = embed.url ?: return@forEach
                                        Spacer(modifier = Modifier.height(2.dp))
                                        BoxWithConstraints(
                                            modifier = Modifier
                                                .clip(MaterialTheme.shapes.medium)
                                                .clickable { openMessageLinkOrBrowser(url) }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .width(embed.width?.toInt()?.dp?.coerceAtMost(maxWidth) ?: maxWidth)
                                                    .aspectRatio(embedAspectRatio(embed, 16f / 9f))
                                            ) {
                                                RemoteImage(
                                                    url = asJanuaryProxyUrl(url),
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize(),
                                                    description = null
                                                )
                                                VideoPlayButton()
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }

                                    else -> {
                                        // no-op
                                    }
                                }
                            }
                        }

                        val reactionsAndInteractions = remember(message.reactions) {
                            message.reactions.orEmpty().toMutableMap().also {
                                message.interactions?.reactions?.forEach { reaction ->
                                    if (!it.containsKey(reaction)) {
                                        it[reaction] = listOf()
                                    }
                                }
                            }
                        }

                        if (reactionsAndInteractions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                reactionsAndInteractions.forEach { reaction ->
                                    Reaction(
                                        reaction.key, reaction.value,
                                        onClick = { hasOwn ->
                                            scope.launch {
                                                if (hasOwn) {
                                                    unreact(
                                                        message.channel!!,
                                                        message.id!!,
                                                        reaction.key
                                                    )
                                                } else {
                                                    RecentEmoji.use(reaction.key)
                                                    react(
                                                        message.channel!!,
                                                        message.id!!,
                                                        reaction.key
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        scope.launch {
                                            ActionChannel.send(
                                                Action.MessageReactionInfo(
                                                    message.id!!,
                                                    reaction.key
                                                )
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                        .clickable(onClick = onAddReaction)
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add_reaction_24dp),
                                        contentDescription = stringResource(Res.string.message_context_sheet_actions_react),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

// durio: the width/height of an embed's media; [fallback] when the server did not measure it
private fun embedAspectRatio(embed: chat.stoat.core.model.schemas.Embed, fallback: Float): Float {
    val width = embed.width?.toFloat() ?: return fallback
    val height = embed.height?.toFloat() ?: return fallback
    return if (width > 0f && height > 0f) width / height else fallback
}

private fun AutumnResource.isValidVoiceMessageAttachment(): Boolean {
    if (
        id.isNullOrBlank() ||
        deleted == true
    ) {
        return false
    }

    val filename = filename ?: return false
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val mimeType = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()

    return when (mimeType) {
        "audio/mp4" -> extension == "m4a"
        "audio/ogg", "audio/opus", "application/ogg" -> extension == "ogg"
        else -> false
    }
}
