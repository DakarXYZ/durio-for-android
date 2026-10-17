package chat.stoat.fork.links

import android.content.Intent
import chat.stoat.fork.account.Account
import chat.stoat.fork.account.AccountManager
import chat.stoat.fork.push.NotificationKeys

/**
 * Notifications and links open in the account they belong to (plan AN-12, AN-13). The manifest
 * only knows the build's own host; links of servers added at runtime arrive from inside the app.
 */
object AccountLinks {
    /**
     * The account a web link belongs to: its server's web app or invite host. Several accounts
     * on that server: the active one if it is among them, else the oldest.
     */
    fun accountForHost(host: String?, accounts: List<Account>, activeKey: String?): Account? {
        if (host.isNullOrEmpty()) return null
        val matching = accounts.filter { account ->
            listOfNotNull(account.endpoints.webApp, account.endpoints.invites)
                .any { hostOf(it).equals(host, ignoreCase = true) }
        }
        return matching.firstOrNull { it.key == activeKey } ?: matching.minByOrNull { it.addedAt }
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':')

    /**
     * If [intent] belongs to an account that is not active, switches to it and opens the intent
     * there (the task restarts, AN-09); true means the caller should stop handling it.
     */
    fun openInOwnAccount(intent: Intent): Boolean {
        val state = AccountManager.state.value
        val key = intent.getStringExtra(NotificationKeys.EXTRA_ACCOUNT_KEY)
            ?.takeIf { key -> state.accounts.any { it.key == key } }
            ?: accountForHost(intent.data?.host, state.accounts, state.activeKey)?.key
            ?: return false
        if (key == state.activeKey) return false

        val forward = Intent(intent).apply {
            flags = 0
            putExtra(NotificationKeys.EXTRA_ACCOUNT_KEY, key)
        }
        AccountManager.switchTo(key, forward)
        return true
    }
}
