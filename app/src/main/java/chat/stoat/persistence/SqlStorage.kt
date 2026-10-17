package chat.stoat.persistence

import app.cash.sqldelight.db.SqlDriver
import chat.stoat.fork.account.AccountManager

object SqlStorage {
    // durio: cache_<key>.db of the active account instead of one revolt.db (plan AN-08)
    val driver: SqlDriver
        get() = AccountManager.sqlDriver
}
