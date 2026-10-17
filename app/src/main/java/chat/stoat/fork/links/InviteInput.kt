package chat.stoat.fork.links

/**
 * What the user typed into "Join a server" as an invite URL (plan AN-13): a full link stays as
 * it is, `host/invite/code` gets `https://`, a bare code goes under [invitesBase] (the active
 * server's `STOAT_INVITES`, which already has its scheme). Null when there is nothing to open.
 */
fun inviteUrlFromInput(input: String, invitesBase: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)) {
        return text
    }
    if ('/' in text) return "https://$text"
    if (invitesBase.isEmpty()) return null
    return "${invitesBase.trimEnd('/')}/$text"
}
