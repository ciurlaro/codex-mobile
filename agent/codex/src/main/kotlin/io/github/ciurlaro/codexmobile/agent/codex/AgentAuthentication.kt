package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentFormValue
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentHook
import io.github.ciurlaro.codexmobile.core.AgentHookActivity
import io.github.ciurlaro.codexmobile.core.AgentHookCatalog
import io.github.ciurlaro.codexmobile.core.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.core.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentPlanProgress
import io.github.ciurlaro.codexmobile.core.AgentPlanStep
import io.github.ciurlaro.codexmobile.core.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentServiceTier
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkillChunk
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalState
import java.io.File
import java.net.URI
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.KSerializer


internal suspend fun CodexAgentClient.authenticateAction() = authMutex.withLock {
    connection.ensureStarted()
    if (authenticated.get()) {
        emitAuthenticated()
        return@withLock
    }
    if (loginId != null) return@withLock

    val account = connection.request(
        AppServerClientMethods.AccountRead,
        GetAccountParams(refreshToken = false),
    ).account
    if (account is AccountChatgptAccount) {
        emitAuthenticated()
        return@withLock
    }

    synchronized(loginStateLock) {
        loginStarting = true
        loginCompletedDuringStart = null
    }
    try {
        val result = connection.request(
            AppServerClientMethods.AccountLoginStart,
            LoginAccountParamsChatgpt(
                appBrand = LoginAppBrand.CODEX,
                useHostedLoginSuccessPage = true,
            ),
        ) as? LoginAccountResponseChatgpt
            ?: error("App-server returned an unexpected login method")
        val startedLoginId = result.loginId
        val earlyCompletion = synchronized(loginStateLock) {
            loginStarting = false
            loginCompletedDuringStart
                ?.takeIf { it.loginId == startedLoginId }
                .also { loginCompletedDuringStart = null }
                .also {
                    loginId = if (it == null && !authenticated.get()) startedLoginId else null
                }
        }
        when {
            earlyCompletion != null -> applyLoginCompletion(earlyCompletion)
            authenticated.get() -> Unit
            else -> eventsChannel.send(
                AgentEvent.AuthenticationRequired(
                    signInUrl = result.authUrl,
                ),
            )
        }
    } catch (error: Exception) {
        synchronized(loginStateLock) {
            loginStarting = false
            loginCompletedDuringStart = null
        }
        throw error
    }
}

internal suspend fun CodexAgentClient.cancelAuthenticationAction() = authMutex.withLock {
    connection.ensureStarted()
    val activeLoginId = synchronized(loginStateLock) {
        loginId?.also(cancelledLoginIds::add)
    } ?: return@withLock
    try {
        val status = connection.request(
            AppServerClientMethods.AccountLoginCancel,
            CancelLoginAccountParams(activeLoginId),
        ).status
        check(status == CancelLoginAccountStatus.CANCELED || status == CancelLoginAccountStatus.NOT_FOUND) {
            "Unexpected login cancellation status: $status"
        }
        synchronized(loginStateLock) {
            if (loginId == activeLoginId) loginId = null
            if (status == CancelLoginAccountStatus.NOT_FOUND) cancelledLoginIds -= activeLoginId
        }
    } catch (error: Exception) {
        synchronized(loginStateLock) { cancelledLoginIds -= activeLoginId }
        throw error
    }
}

internal suspend fun CodexAgentClient.signOutAction() {
    authMutex.withLock {
        connection.ensureStarted()
        connection.request(AppServerClientMethods.AccountLogout, Unit)
        authenticated.set(false)
        synchronized(loginStateLock) {
            loginId = null
            loginStarting = false
            loginCompletedDuringStart = null
            cancelledLoginIds.clear()
        }
    }
    clearPluginCache()
    synchronized(turnStateLock) {
        activeTurns.clear()
        startingTurns.clear()
        terminalDuringStart.clear()
        cancellingTurns.clear()
    }
    userShellItems.clear()
    knownSkillPaths.clear()
    openedSessions.clear()
    sessionRuntimeSettings.clear()
}

internal suspend fun CodexAgentClient.emitAuthenticatedAction() {
    if (authenticated.compareAndSet(false, true)) {
        eventsChannel.send(AgentEvent.Authenticated)
        reconcileProvidersInBackground()
    }
}

internal suspend fun CodexAgentClient.applyLoginCompletionAction(completion: LoginCompletion) {
    if (completion.success) {
        emitAuthenticated()
    } else {
        eventsChannel.send(
            AgentEvent.Failure(
                null,
                "authentication_failed",
                completion.error ?: "Authentication failed",
                recoverable = true,
            ),
        )
    }
}

internal suspend fun CodexAgentClient.handleConnectionFailureAction(code: String, message: String) {
    authenticated.set(false)
    builtInEnablementLoaded.set(false)
    synchronized(loginStateLock) {
        loginId = null
        loginStarting = false
        loginCompletedDuringStart = null
        cancelledLoginIds.clear()
    }
    synchronized(turnStateLock) {
        activeTurns.clear()
        startingTurns.clear()
        terminalDuringStart.clear()
        cancellingTurns.clear()
        cancelledTurns.clear()
    }
    pendingApprovalRequests.clear()
    pendingBuiltInApprovals.clear()
    pendingElicitationRequests.clear()
    workItems.clear()
    userShellItems.clear()
    openedSessions.clear()
    sessionRuntimeSettings.clear()
    pendingAvailabilityNotices.clear()
    threadProviderStates.clear()
    eventsChannel.send(AgentEvent.Failure(null, code, message, recoverable = true))
}
