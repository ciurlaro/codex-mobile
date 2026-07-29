package io.github.ciurlaro.codexmobile.app.session.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.graphics.drawable.Icon
import io.github.ciurlaro.codexmobile.app.R
import io.github.ciurlaro.codexmobile.app.composition.AppContainer
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionState
import io.github.ciurlaro.codexmobile.app.ui.shell.MainActivity
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CodexForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stopping = AtomicBoolean(false)
    private val instanceId = UUID.randomUUID().toString()
    private lateinit var container: AppContainer
    private lateinit var controller: CodexSessionController
    private lateinit var notificationManager: NotificationManager
    private var foregroundStarted = false
    private var foregroundAuthorized = false
    private var lastNotificationText: String? = null
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        container = (application as CodexMobileApplication).container
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        controller = CodexSessionController(
            container.newAgentClient(),
            serviceScope,
            container.platform.skillPackages,
            container.platform.pluginMarketplaces,
        )
        serviceScope.launch {
            controller.state.collect { state ->
                val active = state.needsForeground()
                when {
                    active && foregroundStarted -> updateNotification(state.notificationText())
                    active && foregroundAuthorized -> promoteToForeground(state.notificationText())
                    !active && foregroundStarted -> leaveForeground()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { intent?.action == ACTION_BIND }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!container.backgroundSessions.consumeStart(intent.getStringExtra(EXTRA_AUTHORIZATION))) {
                    if (!foregroundStarted) rejectForegroundStart(startId)
                    return START_NOT_STICKY
                }
                foregroundAuthorized = true
                if (!foregroundStarted && !promoteToForeground("Starting Codex")) return START_NOT_STICKY
                if (intent.getBooleanExtra(EXTRA_AUTHENTICATE, false)) controller.authenticate()
            }

            ACTION_STOP -> stopOwner("Background work stopped")
            else -> if (!foregroundStarted) rejectForegroundStart(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopOwner("Background work reached Android's time limit")
    }

    override fun onDestroy() {
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        controller.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(text: String): Boolean {
        val notification = notification(text)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            lastNotificationText = text
            container.backgroundSessions.markActive(true)
            true
        } catch (error: Exception) {
            container.backgroundSessions.reportFailure(error::class.java.simpleName)
            container.backgroundSessions.markActive(false)
            stopSelf()
            false
        }
    }

    private fun leaveForeground() {
        foregroundStarted = false
        lastNotificationText = null
        container.backgroundSessions.markActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun rejectForegroundStart(startId: Int) {
        runCatching {
            val rejected = notification("Background start rejected")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    rejected,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, rejected)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { container.backgroundSessions.reportFailure(it::class.java.simpleName) }
        stopSelfResult(startId)
    }

    private fun stopOwner(reason: String, signOut: Boolean = false) {
        if (!stopping.compareAndSet(false, true)) return
        serviceScope.launch {
            foregroundStarted = false
            controller.stopAndClose(reason, signOut)
            container.backgroundSessions.markActive(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        notificationManager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CodexForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_codex_status)
            .setContentTitle("Codex Mobile")
            .setContentText(text)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_codex_status),
                    "Stop",
                    stop,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active Codex session",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows user-started Codex work that continues outside the app"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            },
        )
    }

    internal inner class LocalBinder : Binder() {
        val controller: CodexSessionController get() = this@CodexForegroundService.controller
        val serviceInstanceId: String get() = instanceId
        val isForegroundStarted: Boolean get() = foregroundStarted
        fun notificationsEnabled(): Boolean = notificationManager.areNotificationsEnabled()
        fun signOut() = stopOwner("Signed out", signOut = true)
    }

    internal companion object {
        const val ACTION_START = "io.github.ciurlaro.codexmobile.action.START_BACKGROUND_SESSION"
        const val ACTION_BIND = "io.github.ciurlaro.codexmobile.action.BIND_BACKGROUND_SESSION"
        const val ACTION_STOP = "io.github.ciurlaro.codexmobile.action.STOP_BACKGROUND_SESSION"
        const val EXTRA_AUTHORIZATION = "startAuthorization"
        const val EXTRA_AUTHENTICATE = "authenticate"
        const val CHANNEL_ID = "active-codex-session"
        const val NOTIFICATION_ID = 5001

        fun startIntent(
            context: Context,
            authorization: String,
            authenticate: Boolean,
        ): Intent = Intent(context, CodexForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_AUTHORIZATION, authorization)
            .putExtra(EXTRA_AUTHENTICATE, authenticate)

        fun bindIntent(context: Context): Intent =
            Intent(context, CodexForegroundService::class.java).setAction(ACTION_BIND)

        fun stopIntent(context: Context): Intent =
            Intent(context, CodexForegroundService::class.java).setAction(ACTION_STOP)
    }
}

private fun CodexSessionState.notificationText(): String = when {
    terminal -> "Background work stopped"
    pendingApproval != null -> "Waiting for your approval"
    pendingElicitation != null -> "Waiting for your input"
    externalOperation != null -> externalOperation
    attentionRequired -> "Open Codex Mobile to retry"
    workActivity != null -> when (workActivity) {
        io.github.ciurlaro.codexmobile.agent.AgentWorkActivity.RUNNING_COMMAND -> "Running a command"
        io.github.ciurlaro.codexmobile.agent.AgentWorkActivity.WRITING_FILES -> "Writing files"
    }
    isTurnActive -> "Codex is responding"
    signInUrl != null -> "Waiting for ChatGPT sign-in"
    else -> "Starting Codex"
}

private fun CodexSessionState.needsForeground(): Boolean =
    !terminal && (
        !isAuthenticated || signInUrl != null || isTurnActive ||
            pendingApproval != null || pendingElicitation != null || workActivity != null ||
            externalOperation != null
        )
