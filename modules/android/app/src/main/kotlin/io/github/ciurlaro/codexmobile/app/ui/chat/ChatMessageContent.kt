package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.invocation.promptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode

@Composable
internal fun UserMessage(message: ChatMessage, state: AppUiState) {
    val shellCommand = message.text.shellCommandOrNull()
    val planMode = message.collaborationMode == AgentCollaborationMode.PLAN
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (shellCommand != null) {
            SentShellCommand(shellCommand)
            return@Row
        }
        Column(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .background(
                    if (planMode) Color(0xFF332719) else ChatColors.UserBubble,
                    RoundedCornerShape(ChatDimensions.MessageCorner),
                )
                .then(
                    if (planMode) {
                        Modifier.border(
                            1.dp,
                            ChatColors.PlanAccent,
                            RoundedCornerShape(ChatDimensions.MessageCorner),
                        )
                    } else {
                        Modifier
                    },
                )
                .semantics { contentDescription = if (planMode) "Plan request" else "User message" }
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (planMode) PlanHeader()
            if (message.text.isNotEmpty()) SelectionContainer {
                Text(message.text, color = ChatColors.Primary, style = MaterialTheme.typography.bodyLarge)
            }
            message.capabilities.forEach { capability ->
                Text(text = capabilityPrompt(capability), style = MaterialTheme.typography.bodyLarge)
            }
            message.invocations.forEach { invocation ->
                val item = state.promptInvocation(invocation)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(item.glyph(), Modifier.size(18.dp), item.accent())
                    Text(
                        text = "  Use ${item.title}",
                        color = ChatColors.Accent,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CodexMessage(
    message: ChatMessage,
    expandedByDefault: Boolean = true,
    onExpansionChanged: (Boolean) -> Unit = {},
    onProceedWithPlan: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Codex message" }
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (message.shellCommand != null) {
            ShellCommandOutput(message)
            return@Column
        }
        val hasThoughts = !message.reasoning.isNullOrBlank() ||
            message.planProgress?.steps?.isNotEmpty() == true ||
            message.hookActivities.isNotEmpty() ||
            (message.isStreaming && message.text.isEmpty())
        if (hasThoughts) {
            ThoughtsPanel(
                messageId = message.id,
                reasoning = message.reasoning,
                plan = message.planProgress,
                hooks = message.hookActivities,
                isStreaming = message.isStreaming && message.text.isEmpty(),
                expandedByDefault = expandedByDefault,
                onExpansionChanged = onExpansionChanged,
            )
        }
        message.plan?.takeIf(String::isNotBlank)?.let {
            PlanPanel(message.id, it, message.isStreaming, onProceedWithPlan)
        }
        if (message.text.isNotEmpty()) {
            SelectionContainer { MessageText(message.text) }
            if (!message.isStreaming) CopyAnswerButton(message.text)
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CopyAnswerButton(text: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .clickable { clipboard.setText(AnnotatedString(text)) }
            .semantics { contentDescription = "Copy full answer" }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(IconGlyph.COPY, Modifier.size(16.dp), ChatColors.Secondary)
        Text("Copy", color = ChatColors.Secondary, style = MaterialTheme.typography.labelMedium)
    }
}
