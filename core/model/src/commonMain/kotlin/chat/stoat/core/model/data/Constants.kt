package chat.stoat.core.model.data

import chat.stoat.core.model.fork.ActiveServer
import chat.stoat.core.model.fork.UpstreamStoat

// durio: addresses of the active account's server instead of upstream's (plan AN-07). Getters, so
// string templates keep working. API and WebSocket fail loudly without a server; links fall back
// to "" so screens that show before login still render.
val STOAT_BASE: String get() = ActiveServer.current.api
val STOAT_SUPPORT: String get() = if (UpstreamStoat.isActive) UpstreamStoat.SUPPORT else STOAT_WEB_APP
val STOAT_MARKETING: String get() = if (UpstreamStoat.isActive) UpstreamStoat.MARKETING else STOAT_WEB_APP
val STOAT_FILES: String get() = ActiveServer.currentOrNull?.files.orEmpty()
val STOAT_PROXY: String get() = ActiveServer.currentOrNull?.proxy.orEmpty()
val STOAT_WEB_APP: String get() = ActiveServer.currentOrNull?.webApp.orEmpty()
val STOAT_BETA_WEB_APP: String get() = if (UpstreamStoat.isActive) UpstreamStoat.BETA_WEB_APP else STOAT_WEB_APP
val STOAT_INVITES: String get() = ActiveServer.currentOrNull?.let { it.invites ?: "${it.webApp}/invite" }.orEmpty()
val STOAT_WEBSOCKET: String get() = ActiveServer.current.ws
val STOAT_CHANGELOG: String get() = UpstreamStoat.CHANGELOG
