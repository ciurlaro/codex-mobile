package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.AndroidSkillPackageManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class ForegroundSessionState(
    val statusMessage: String = "Starting background session…",
    val streamedText: String = "",
    val shellExitCode: Int? = null,
    val sessionId: SessionId? = null,
    val isAuthenticated: Boolean = false,
    val activeModel: String? = null,
    val activeEffort: String? = null,
    val activeServiceTier: String? = null,
    val signInUrl: String? = null,
    val isTurnActive: Boolean = false,
    val pendingApproval: AgentEvent.ApprovalRequested? = null,
    val pendingElicitation: AgentElicitation? = null,
    val skillsRevision: Int = 0,
    val connectorsRevision: Int = 0,
    val oauthCompletion: AgentEvent.McpOauthCompleted? = null,
    val externalOperation: String? = null,
    val workActivity: AgentWorkActivity? = null,
    val attentionRequired: Boolean = false,
    val diagnosticCode: String? = null,
    val terminal: Boolean = false,
)

internal class ForegroundSessionController(
    private val agentClient: AgentClient,
    private val scope: CoroutineScope,
    private val skillPackages: AndroidSkillPackageManager? = null,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(ForegroundSessionState())
    private val turnClaimed = AtomicBoolean(false)
    private val turnStartCompleted = AtomicBoolean(false)
    private val cancellationStarted = AtomicBoolean(false)
    private val cancellationDispatched = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val externalOperationMutex = Mutex()
    private var authenticationStarted = false
    private var eventJob: Job = scope.launch { agentClient.events.collect(::reduce) }

    val state: StateFlow<ForegroundSessionState> = mutableState.asStateFlow()

    fun authenticate() {
        val shouldStart = synchronized(lock) {
            if (closed.get() || authenticationStarted) false else true.also { authenticationStarted = true }
        }
        if (!shouldStart) return
        mutableState.update {
            it.copy(
                statusMessage = "Checking sign-in…",
                signInUrl = null,
                attentionRequired = false,
                diagnosticCode = null,
            )
        }
        launchVisibleFailure(resetAuthentication = true) { agentClient.authenticate() }
    }

    fun cancelAuthentication() {
        if (closed.get()) return
        mutableState.update { it.copy(statusMessage = "Cancelling sign-in…") }
        launchVisibleFailure(resetAuthentication = true) {
            agentClient.cancelAuthentication()
            synchronized(lock) { authenticationStarted = false }
            mutableState.update {
                it.copy(statusMessage = "Ready to sign in", signInUrl = null)
            }
        }
    }

    fun submit(request: AgentTurnRequest): Boolean {
        if (request.prompt.isBlank() && request.capabilities.isEmpty() && request.invocations.isEmpty()) {
            mutableState.update { it.copy(statusMessage = "Enter a prompt first") }
            return false
        }
        if (!beginTurn("Codex is responding…")) return false
        launchVisibleFailure(resetTurn = true) {
            val sessionId = state.value.sessionId ?: agentClient.openSession(
                settings = AgentRuntimeSettings(
                    approvalPreset = request.approvalPreset,
                    serviceTier = request.serviceTier,
                    workingDirectory = request.workingDirectory,
                ),
            )
            agentClient.sendTurn(sessionId, request)
            turnStartCompleted.set(true)
            if (cancellationStarted.get()) dispatchCancellation(sessionId)
        }
        return true
    }

    fun submitShell(command: String, settings: AgentRuntimeSettings): Boolean {
        if (command.isBlank()) {
            mutableState.update { it.copy(statusMessage = "Enter a shell command after !") }
            return false
        }
        if (!beginTurn("Running command…")) return false
        launchVisibleFailure(resetTurn = true) {
            val sessionId = agentClient.openSession(state.value.sessionId, settings)
            agentClient.runShellCommand(sessionId, command)
            turnStartCompleted.set(true)
            if (cancellationStarted.get()) dispatchCancellation(sessionId)
        }
        return true
    }

    private fun beginTurn(statusMessage: String): Boolean {
        if (!state.value.isAuthenticated) {
            mutableState.update { it.copy(statusMessage = "Sign in before sending a message") }
            return false
        }
        if (closed.get() || !turnClaimed.compareAndSet(false, true)) return false
        cancellationStarted.set(false)
        cancellationDispatched.set(false)
        turnStartCompleted.set(false)
        mutableState.update {
            it.copy(
                statusMessage = statusMessage,
                streamedText = "",
                shellExitCode = null,
                isTurnActive = true,
                attentionRequired = false,
                diagnosticCode = null,
            )
        }
        return true
    }

    fun resolveApproval(requestId: String, decision: AgentApprovalDecision) {
        val pending = state.value.pendingApproval ?: return
        if (pending.requestId != requestId || closed.get()) return
        mutableState.update {
            it.copy(
                statusMessage = if (decision == AgentApprovalDecision.ACCEPT) "Continuing…" else "Declining…",
                pendingApproval = null,
                attentionRequired = false,
            )
        }
        launchVisibleFailure { agentClient.resolveApproval(requestId, decision) }
    }

    fun resolveElicitation(requestId: String, response: AgentElicitationResponse) {
        val pending = state.value.pendingElicitation ?: return
        if (pending.requestId != requestId || closed.get()) return
        mutableState.update { it.copy(pendingElicitation = null, attentionRequired = false) }
        launchVisibleFailure { agentClient.resolveElicitation(requestId, response) }
    }

    fun startNewChat(): Boolean {
        if (!state.value.isAuthenticated || state.value.isTurnActive || closed.get()) return false
        mutableState.update {
            it.copy(
                statusMessage = "Ready",
                streamedText = "",
                sessionId = null,
                diagnosticCode = null,
                attentionRequired = false,
            )
        }
        return true
    }

    fun openConversation(
        sessionId: SessionId,
        settings: AgentRuntimeSettings = AgentRuntimeSettings(),
    ): Boolean {
        if (!state.value.isAuthenticated || state.value.isTurnActive || closed.get()) return false
        mutableState.update {
            it.copy(statusMessage = "Loading conversation…", streamedText = "", diagnosticCode = null)
        }
        launchVisibleFailure { agentClient.openSession(sessionId, settings) }
        return true
    }

    suspend fun listModels(): List<AgentModel> = agentClient.listModels()

    suspend fun listSkills(workingDirectory: String, forceReload: Boolean = false): AgentSkillCatalog =
        agentClient.listSkills(workingDirectory, forceReload).let { catalog ->
            catalog.copy(skills = catalog.skills.map { skill ->
                skill.copy(canUninstall = skillPackages?.canUninstall(skill) == true)
            })
        }

    suspend fun readSkill(path: String, offset: Long = 0) = agentClient.readSkill(path, offset)

    suspend fun setSkillEnabled(path: String, enabled: Boolean) =
        runExternalOperation("Updating skill") { agentClient.setSkillEnabled(path, enabled) }

    suspend fun listAvailableSkills(
        installedNames: Set<String>,
        forceRefresh: Boolean = false,
    ): AgentSkillPackageCatalog = requireNotNull(skillPackages) {
        "Skill packages are unavailable"
    }.listAvailable(installedNames, forceRefresh)

    suspend fun discoverGitHubSkills(url: String): List<AgentSkillPackage> = requireNotNull(skillPackages) {
        "Skill packages are unavailable"
    }.discoverGitHubSkills(url)

    suspend fun readSkillPackage(packageInfo: AgentSkillPackage, offset: Long = 0) =
        requireNotNull(skillPackages) { "Skill packages are unavailable" }
            .readPackageSource(packageInfo, offset)

    suspend fun installSkill(packageInfo: AgentSkillPackage) =
        runExternalOperation("Installing ${packageInfo.displayName}") {
            requireNotNull(skillPackages) { "Skill packages are unavailable" }.install(packageInfo)
        }

    suspend fun uninstallSkill(skill: AgentSkill) =
        runExternalOperation("Removing ${skill.displayName}") {
            requireNotNull(skillPackages) { "Skill packages are unavailable" }.uninstall(skill)
        }

    suspend fun listInstalledPlugins(workingDirectory: String): AgentPluginCatalog =
        agentClient.listInstalledPlugins(workingDirectory)

    suspend fun listAvailablePlugins(
        workingDirectory: String,
        forceRefresh: Boolean = false,
    ): AgentPluginCatalog = agentClient.listAvailablePlugins(workingDirectory, forceRefresh)

    suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail =
        agentClient.readPlugin(plugin)

    suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult =
        runExternalOperation("Installing ${plugin.name}") { agentClient.installPlugin(plugin) }

    suspend fun uninstallPlugin(pluginId: String) =
        runExternalOperation("Removing plugin") { agentClient.uninstallPlugin(pluginId) }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) =
        runExternalOperation("Updating plugin") { agentClient.setPluginEnabled(pluginId, enabled) }

    suspend fun listConnectors(forceReload: Boolean = false): List<AgentConnector> =
        agentClient.listConnectors(state.value.sessionId, forceReload)

    suspend fun listMcpServers(): List<AgentMcpServer> = agentClient.listMcpServers()

    suspend fun startMcpOauth(serverName: String): String =
        runExternalOperation("Connecting to $serverName") {
            agentClient.startMcpOauth(serverName, state.value.sessionId)
        }

    suspend fun listConversations(): List<AgentConversationSummary> = agentClient.listSessions()

    suspend fun readConversation(sessionId: SessionId): AgentConversation =
        agentClient.readSession(sessionId)

    suspend fun renameConversation(sessionId: SessionId, title: String) =
        agentClient.renameSession(sessionId, title)

    suspend fun deleteConversation(sessionId: SessionId) =
        agentClient.deleteSession(sessionId)

    fun cancelTurn() {
        if (
            !state.value.isTurnActive || closed.get() ||
            !cancellationStarted.compareAndSet(false, true)
        ) {
            return
        }
        mutableState.update { it.copy(statusMessage = "Cancelling…") }
        if (turnStartCompleted.get()) state.value.sessionId?.let(::dispatchCancellation)
    }

    suspend fun stopAndClose(reason: String, signOut: Boolean = false): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        val before = state.value
        resetAuthenticationState()
        resetTurnState()
        mutableState.update {
            it.copy(
                statusMessage = reason,
                sessionId = null,
                isAuthenticated = false,
                signInUrl = null,
                isTurnActive = false,
                pendingApproval = null,
                pendingElicitation = null,
                workActivity = null,
                diagnosticCode = null,
            )
        }
        if (before.isTurnActive && before.sessionId != null) {
            withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
                runCatching { agentClient.cancelTurn(before.sessionId) }
            }
        }
        val signedOut = !signOut || withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
            runCatching { agentClient.signOut() }.isSuccess
        } == true
        agentClient.close()
        eventJob.cancel()
        mutableState.update {
            it.copy(
                statusMessage = if (signedOut) it.statusMessage else "ChatGPT sign-out failed; try again",
                terminal = true,
            )
        }
        return signedOut
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resetAuthenticationState()
        resetTurnState()
        mutableState.update {
            it.copy(
                statusMessage = "Background work ended",
                sessionId = null,
                isAuthenticated = false,
                signInUrl = null,
                isTurnActive = false,
                pendingApproval = null,
                pendingElicitation = null,
                workActivity = null,
                diagnosticCode = null,
                terminal = true,
            )
        }
        agentClient.close()
        eventJob.cancel()
    }

    private fun reduce(event: AgentEvent) {
        if (closed.get()) return
        when (event) {
            is AgentEvent.AuthenticationRequired -> mutableState.update {
                it.copy(
                    statusMessage = "Finish sign-in in your browser",
                    signInUrl = event.signInUrl,
                )
            }

            AgentEvent.Authenticated -> {
                synchronized(lock) { authenticationStarted = false }
                mutableState.update {
                    it.copy(
                        statusMessage = "Ready",
                        isAuthenticated = true,
                        signInUrl = null,
                        diagnosticCode = null,
                    )
                }
            }

            is AgentEvent.SessionOpened -> mutableState.update {
                it.copy(
                    statusMessage = "Ready",
                    sessionId = event.sessionId,
                    isAuthenticated = true,
                    activeModel = event.model ?: it.activeModel,
                    activeEffort = event.effort ?: it.activeEffort,
                    activeServiceTier = event.serviceTier ?: it.activeServiceTier,
                    diagnosticCode = null,
                )
            }

            is AgentEvent.TextDelta -> appendStreamedText(event.sessionId, event.text)

            is AgentEvent.ShellOutputDelta -> appendStreamedText(event.sessionId, event.text)

            is AgentEvent.ShellCommandCompleted -> mutableState.update {
                if (it.sessionId == event.sessionId) it.copy(shellExitCode = event.exitCode) else it
            }

            is AgentEvent.TurnCompleted -> {
                resetTurnState()
                mutableState.update {
                    if (it.sessionId == event.sessionId) {
                        it.copy(statusMessage = "Ready", isTurnActive = false, diagnosticCode = null)
                    } else {
                        it
                    }
                }
            }

            is AgentEvent.Failure -> {
                resetTurnState()
                resetAuthenticationState()
                mutableState.update {
                    it.copy(
                        statusMessage = event.message.take(MAX_VISIBLE_ERROR_CHARS),
                        sessionId = if (event.sessionId == null) null else it.sessionId,
                        signInUrl = null,
                        isTurnActive = false,
                        pendingApproval = null,
                        pendingElicitation = null,
                        workActivity = null,
                        attentionRequired = true,
                        diagnosticCode = event.code,
                    )
                }
            }

            is AgentEvent.ApprovalRequested -> {
                if (mutableState.value.pendingApproval == null) {
                    mutableState.update {
                        it.copy(
                            statusMessage = "Approval needed",
                            pendingApproval = event,
                            attentionRequired = true,
                        )
                    }
                } else {
                    launchVisibleFailure {
                        agentClient.resolveApproval(event.requestId, AgentApprovalDecision.DECLINE)
                    }
                }
            }

            is AgentEvent.WorkActivityChanged -> mutableState.update {
                if (it.sessionId == event.sessionId) it.copy(workActivity = event.activity) else it
            }

            AgentEvent.SkillsChanged -> mutableState.update {
                it.copy(skillsRevision = it.skillsRevision + 1)
            }

            AgentEvent.ConnectorsChanged -> mutableState.update {
                it.copy(connectorsRevision = it.connectorsRevision + 1)
            }

            is AgentEvent.McpOauthCompleted -> mutableState.update {
                it.copy(oauthCompletion = event, externalOperation = null)
            }

            is AgentEvent.ElicitationRequested -> mutableState.update {
                if (it.pendingElicitation == null) {
                    it.copy(
                        statusMessage = "Information needed",
                        pendingElicitation = event.elicitation,
                        attentionRequired = true,
                    )
                } else {
                    launchVisibleFailure {
                        agentClient.resolveElicitation(
                            event.elicitation.requestId,
                            AgentElicitationResponse(AgentElicitationAction.DECLINE),
                        )
                    }
                    it
                }
            }
        }
    }

    private fun appendStreamedText(sessionId: SessionId, text: String) {
        mutableState.update {
            if (it.sessionId != sessionId || it.streamedText.endsWith(TRUNCATION_MARKER)) {
                it
            } else {
                val remaining = MAX_STREAMED_TEXT_CHARS - it.streamedText.length
                if (text.length <= remaining) {
                    it.copy(streamedText = it.streamedText + text)
                } else {
                    it.copy(streamedText = it.streamedText + text.take(remaining) + TRUNCATION_MARKER)
                }
            }
        }
    }

    private suspend fun <T> runExternalOperation(label: String, block: suspend () -> T): T {
        return externalOperationMutex.withLock {
            mutableState.update { it.copy(externalOperation = label) }
            try {
                block()
            } finally {
                mutableState.update { it.copy(externalOperation = null) }
            }
        }
    }

    private fun dispatchCancellation(sessionId: SessionId) {
        if (!cancellationDispatched.compareAndSet(false, true)) return
        launchVisibleFailure(resetTurn = true, resetCancellation = true) {
            agentClient.cancelTurn(sessionId)
        }
    }

    private fun resetTurnState() {
        turnClaimed.set(false)
        turnStartCompleted.set(false)
        cancellationStarted.set(false)
        cancellationDispatched.set(false)
    }

    private fun resetAuthenticationState() {
        synchronized(lock) { authenticationStarted = false }
    }

    private fun launchVisibleFailure(
        resetAuthentication: Boolean = false,
        resetTurn: Boolean = false,
        resetCancellation: Boolean = false,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (resetAuthentication) synchronized(lock) { authenticationStarted = false }
                if (resetTurn) turnClaimed.set(false)
                if (resetCancellation) cancellationStarted.set(false)
                if (!closed.get()) {
                    mutableState.update {
                        it.copy(
                            statusMessage = error.message?.take(MAX_VISIBLE_ERROR_CHARS) ?: "Codex failed",
                            signInUrl = null,
                            isTurnActive = false,
                            attentionRequired = true,
                            diagnosticCode = "client_request",
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_STREAMED_TEXT_CHARS = 256 * 1024
        const val MAX_VISIBLE_ERROR_CHARS = 500
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TRUNCATION_MARKER = "\n[Response truncated]"
    }
}
