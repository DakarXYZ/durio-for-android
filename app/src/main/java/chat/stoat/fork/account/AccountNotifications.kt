package chat.stoat.fork.account

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import coil3.SingletonImageLoader
import com.bumptech.glide.Glide
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/** What an account leaves outside its files: notifications, shortcuts, image caches (AN-11). */
internal object AccountNotifications {
    /**
     * Notifications and shortcuts of the account. AN-12 tags them `"<key>:<channel>"`; older ones
     * carry no key, so they go when the last account leaves ([everything]).
     */
    fun clear(context: Context, key: String?, everything: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (everything) {
            manager.cancelAll()
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        } else if (key != null) {
            manager.activeNotifications
                .filter { it.tag?.startsWith("$key:") == true }
                .forEach { manager.cancel(it.tag, it.id) }
            val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
                .map { it.id }
                .filter { ".$key." in it }
            if (shortcuts.isNotEmpty()) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, shortcuts)
            }
        }
        if (key != null) manager.deleteNotificationChannelGroup("acc.$key")
    }

    /** Image caches are shared (keys are URLs) and cannot be cleared per host. Background thread. */
    fun clearImageCache(context: Context) {
        try {
            Glide.get(context).clearDiskCache()
            SingletonImageLoader.get(context).diskCache?.clear()
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Could not clear image caches: ${e.asLog()}" }
        }
    }
}
