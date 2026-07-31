package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole

@Composable
internal fun ConversationList(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onEvent: (AppUiEvent) -> Unit,
) {
    Box(modifier) {
        when {
            state.isConversationLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(28.dp),
                color = ChatColors.Accent,
                strokeWidth = 2.dp,
            )
            !state.isAuthenticated -> SignInState(state, onEvent, Modifier.align(Alignment.Center))
            else -> ConversationMessages(state, onEvent)
        }
    }
}

@Composable
private fun ConversationMessages(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    var reasoningExpandedByDefault by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val extraThinking = state.isTurnActive && state.messages.none(ChatMessage::isStreaming)
    val itemCount = state.messages.size + if (extraThinking) 1 else 0
    val lastLength = state.messages.lastOrNull()?.let { it.text.length + it.reasoning.orEmpty().length } ?: 0
    LaunchedEffect(itemCount, lastLength) {
        if (itemCount == 0) return@LaunchedEffect
        val lastIndex = itemCount - 1
        val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (visibleLast == null || visibleLast >= lastIndex - 1) listState.scrollToItem(lastIndex)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(
            start = ChatDimensions.ScreenPadding,
            end = ChatDimensions.ScreenPadding,
            top = 12.dp,
            bottom = 20.dp,
        ),
    ) {
        items(state.messages, key = ChatMessage::id) { message ->
            when (message.role) {
                AgentMessageRole.USER -> UserMessage(message, state)
                AgentMessageRole.CODEX -> CodexMessage(
                    message = message,
                    expandedByDefault = reasoningExpandedByDefault,
                    onExpansionChanged = { reasoningExpandedByDefault = it },
                    onProceedWithPlan = if (
                        message.id == state.messages.lastOrNull()?.id &&
                        state.collaborationMode == AgentCollaborationMode.PLAN
                    ) {
                        { onEvent(AppUiEvent.ProceedWithPlan) }
                    } else {
                        null
                    },
                )
            }
        }
        if (extraThinking) item("codex-thinking") { ThinkingMessage() }
    }
}

@Composable
private fun SignInState(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            state.statusMessage,
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        when {
            state.isAuthenticationInProgress -> CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = ChatColors.Accent,
                strokeWidth = 3.dp,
            )
            state.signInUrl != null -> {
                Button(
                    onClick = { onEvent(AppUiEvent.OpenSignIn) },
                    modifier = Modifier.heightIn(min = ChatDimensions.TouchTarget),
                ) { Text("Open sign-in again") }
                Button(
                    onClick = { onEvent(AppUiEvent.CancelAuthentication) },
                    modifier = Modifier.heightIn(min = ChatDimensions.TouchTarget),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatColors.ElevatedStrong),
                ) { Text("Cancel sign-in") }
            }
            else -> Button(
                onClick = { onEvent(AppUiEvent.Authenticate) },
                modifier = Modifier.heightIn(min = ChatDimensions.TouchTarget),
            ) { Text("Sign in with ChatGPT") }
        }
    }
}
