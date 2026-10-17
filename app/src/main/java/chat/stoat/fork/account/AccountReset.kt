package chat.stoat.fork.account

import chat.stoat.api.StoatAPI
import chat.stoat.api.settings.LoadedSettings
import chat.stoat.api.settings.ServerFolders
import chat.stoat.api.settings.SyncedSettings
import chat.stoat.c2dm.NotificationDeepLink
import chat.stoat.composables.media.AudioPlaybackCoordinator
import chat.stoat.voice.VoiceCallManager

/**
 * Global state of the active account and how each piece is reset on switch (plan AN-09; list
 * checked with CodeGraph over every `object` in app and core):
 *
 * - `VoiceCallManager`: room, channel, session job -> [VoiceCallManager.leave] (the UI asks first);
 * - `AudioPlaybackCoordinator`: playing voice message -> stopped;
 * - `ServerFolders`: background sidebar writes -> finished or cancelled *before* the token
 *   changes, else account A's folders reach server B;
 * - `SyncedSettings`: settings state -> [SyncedSettings.clearLocal]; never the `reset*()`
 *   functions, they write defaults to the server;
 * - `StoatAPI`: caches, members, unreads, token, socket -> [StoatAPI.softReset] (cache files stay);
 * - `RealtimeSocket`: closed with the socket job; its `database` follows the active account;
 * - `LoadedSettings`: theme, font etc. -> `reset()`;
 * - `NotificationDeepLink`: pending link -> cleared (it belongs to the old account).
 *
 * Kept: `Experiments`, `FeatureFlags`, `GeoStateProvider` (device-wide), `UiCallbacks` (no replay),
 * stateless helpers.
 */
internal object AccountReset {
    suspend fun resetGlobalState(
        leaveCall: () -> Unit = { VoiceCallManager.leave() },
        stopAudio: () -> Unit = { AudioPlaybackCoordinator.stop() },
    ) {
        leaveCall()
        stopAudio()
        ServerFolders.finishPendingSync()
        SyncedSettings.clearLocal()
        StoatAPI.softReset()
        LoadedSettings.reset()
        NotificationDeepLink.pendingNavigation.value = null
    }
}
