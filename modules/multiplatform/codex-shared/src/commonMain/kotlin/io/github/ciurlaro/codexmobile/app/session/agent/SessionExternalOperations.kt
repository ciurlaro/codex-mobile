package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.agent.AgentClient
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentElicitation
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.SessionId
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


internal suspend fun <T> CodexSessionController.runExternalOperationAction(label: String, block: suspend () -> T): T {
    return externalOperationMutex.withLock {
        mutableState.update { it.copy(externalOperation = label) }
        try {
            block()
        } finally {
            mutableState.update { it.copy(externalOperation = null) }
        }
    }
}

internal suspend fun <T> CodexSessionController.runPluginOperationAction(label: String, block: suspend () -> T): T =
    runExternalOperation(label) {
        check(!state.value.isTurnActive) { "Wait for the current reply before changing plugins" }
        block()
    }
