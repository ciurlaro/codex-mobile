package io.github.ciurlaro.codexmobile.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.ciurlaro.codexmobile.core.SessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MainUiState(
    val status: String = "Step 01 not implemented",
    val streamedText: String = "",
    val sessionId: SessionId? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CodexMobileApplication).graph
    private val mutableState = MutableStateFlow(MainUiState())

    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    fun authenticate() {
        TODO("Step 01: launch authentication in viewModelScope and reduce AgentEvent values into state")
    }

    fun submit(prompt: String) {
        TODO("Step 01: reject blank input and submit one prompt to the active session")
    }

    fun cancel() {
        TODO("Step 01: cancel the active turn")
    }

    // TODO Step 01: close graph.agentClient when the real application owner is defined.
}
