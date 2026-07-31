package io.github.ciurlaro.codexmobile.app.session.background

import android.content.Context
import io.github.ciurlaro.codexmobile.app.presentation.viewmodel.AppSessionHandle
import io.github.ciurlaro.codexmobile.app.presentation.viewmodel.AppSessionHost
import kotlinx.coroutines.flow.StateFlow

internal class AndroidSessionHost(
    context: Context,
    private val sessions: BackgroundSessionStore,
) : AppSessionHost {
    private val appContext = context.applicationContext
    private var connection: CodexServiceConnection? = null

    override val failure: StateFlow<String?> = sessions.failure

    override fun attach(onConnected: (AppSessionHandle) -> Unit, onEnded: () -> Unit) {
        check(connection == null) { "Session host is already attached" }
        connection = CodexServiceConnection(
            context = appContext,
            onConnected = { binder ->
                onConnected(
                    AppSessionHandle(
                        controller = binder.controller,
                        serviceInstanceId = binder.serviceInstanceId,
                        notificationsEnabled = binder::notificationsEnabled,
                        signOut = binder::signOut,
                    ),
                )
            },
            onEnded = onEnded,
        )
    }

    override fun startAndBind(authenticate: Boolean): Boolean {
        val authorization = sessions.authorizeStart()
        return runCatching {
            appContext.startForegroundService(
                CodexForegroundService.startIntent(appContext, authorization, authenticate),
            )
            check(bind(create = true)) { "Codex service binding failed" }
        }.fold(
            onSuccess = { true },
            onFailure = {
                sessions.revokeStart(authorization)
                false
            },
        )
    }

    override fun bind(create: Boolean): Boolean =
        connection?.bind(if (create) Context.BIND_AUTO_CREATE else 0) == true

    override fun unbind() = connection?.unbind() ?: Unit

    override fun wasActive(): Boolean = sessions.wasActive()

    override fun markInactive() = sessions.markActive(false)
}
