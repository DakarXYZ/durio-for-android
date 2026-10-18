package chat.stoat.fork.e2ee

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Tells [E2eeManager] whether the app is in the foreground (block 5E): the safety mailbox pass
 * of the active account runs only then. Process lifecycle: started with the first visible
 * activity, stopped shortly after the last one.
 */
object E2eeForeground : DefaultLifecycleObserver {
    /** From `StoatApplication.onCreate`, on the main thread. */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        E2eeManager.setForeground(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        E2eeManager.setForeground(false)
    }
}
