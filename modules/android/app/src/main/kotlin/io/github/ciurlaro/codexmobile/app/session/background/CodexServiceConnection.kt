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
    private var keepsServiceAlive = false

    fun bind(flags: Int): Boolean {
        val requestedAutoCreate = flags and Context.BIND_AUTO_CREATE != 0
        if (isBound && (!requestedAutoCreate || keepsServiceAlive)) return true
        if (isBound) unbind()
        isBound = runCatching {
            context.bindService(CodexForegroundService.bindIntent(context), this, flags)
        }.getOrDefault(false)
        keepsServiceAlive = isBound && requestedAutoCreate
        return isBound
    }

    fun unbind() {
        if (isBound) runCatching { context.unbindService(this) }
        isBound = false
        keepsServiceAlive = false
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
        keepsServiceAlive = false
        onEnded()
    }
}
