package io.github.ciurlaro.codexmobile.app.session.background

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder

internal class CodexServiceConnection(
    private val context: Context,
    private val onConnected: (CodexForegroundService.LocalBinder) -> Unit,
    private val onEnded: () -> Unit,
) : ServiceConnection {
    var isBound: Boolean = false
        private set

    fun bind(flags: Int): Boolean {
        if (isBound) return true
        isBound = runCatching {
            context.bindService(CodexForegroundService.bindIntent(context), this, flags)
        }.getOrDefault(false)
        return isBound
    }

    fun unbind() {
        if (isBound) runCatching { context.unbindService(this) }
        isBound = false
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val binder = service as? CodexForegroundService.LocalBinder ?: return
        isBound = true
        onConnected(binder)
    }

    override fun onServiceDisconnected(name: ComponentName) = ended()
    override fun onBindingDied(name: ComponentName) = ended()
    override fun onNullBinding(name: ComponentName) = ended()

    private fun ended() {
        isBound = false
        onEnded()
    }
}
