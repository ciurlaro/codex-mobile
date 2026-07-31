package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.formatting.distinctThoughts
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress
import io.github.ciurlaro.codexmobile.agent.AgentPlanStepStatus

@Composable
internal fun ThoughtsPanel(
    messageId: String,
    reasoning: String?,
    plan: AgentPlanProgress?,
    hooks: List<AgentHookActivity>,
    isStreaming: Boolean,
    expandedByDefault: Boolean,
    onExpansionChanged: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(expandedByDefault) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
                onExpansionChanged(expanded)
            }
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        color = ChatColors.ThoughtAccent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ChatColors.ThoughtAccent.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(IconGlyph.CONNECTED_STEPS, Modifier.size(19.dp), ChatColors.ThoughtAccent)
                Text(
                    text = "  Thoughts",
                    modifier = Modifier.weight(1f),
                    color = ChatColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                )
                AppIcon(
                    glyph = if (expanded) IconGlyph.CHEVRON_DOWN else IconGlyph.CHEVRON_RIGHT,
                    modifier = Modifier.size(18.dp),
                    tint = ChatColors.Secondary,
                )
            }
            if (expanded) {
                HorizontalDivider(color = ChatColors.ThoughtAccent.copy(alpha = 0.25f))
                reasoning?.distinctThoughts().orEmpty().forEach { ThoughtText(it) }
                plan?.explanation?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = ChatColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                }
                plan?.steps.orEmpty().forEachIndexed { index, step ->
                    ThoughtStep(index + 1, step.text, step.status)
                }
                hooks.forEach { HookThought(it) }
                if (isStreaming && reasoning.isNullOrBlank() && plan?.steps.isNullOrEmpty() && hooks.isEmpty()) {
                    ThinkingPulse()
                }
            }
        }
    }
}

@Composable
private fun ThoughtText(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(8.dp)
                .background(ChatColors.ThoughtAccent, RoundedCornerShape(4.dp)),
        )
        Box(Modifier.weight(1f)) {
            SelectionContainer { MessageText(text) }
        }
    }
}

@Composable
private fun ThoughtStep(number: Int, text: String, status: AgentPlanStepStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(18.dp)
                .border(
                    1.dp,
                    if (status == AgentPlanStepStatus.COMPLETED) ChatColors.PluginAccent else ChatColors.ThoughtAccent,
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                AgentPlanStepStatus.COMPLETED -> AppIcon(
                    IconGlyph.CHECK,
                    Modifier.size(11.dp),
                    ChatColors.PluginAccent,
                )
                AgentPlanStepStatus.IN_PROGRESS -> ThinkingDot()
                AgentPlanStepStatus.PENDING -> Unit
            }
        }
        Text("$number. $text", color = ChatColors.Primary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HookThought(activity: AgentHookActivity) {
    val color = when (activity.status) {
        AgentHookRunStatus.COMPLETED -> ChatColors.PluginAccent
        AgentHookRunStatus.FAILED,
        AgentHookRunStatus.BLOCKED,
        AgentHookRunStatus.STOPPED,
        -> ChatColors.Danger
        AgentHookRunStatus.RUNNING -> ChatColors.ThoughtAccent
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            if (activity.status == AgentHookRunStatus.COMPLETED) IconGlyph.CHECK else IconGlyph.SPARKLES,
            Modifier.size(18.dp),
            color,
        )
        Column {
            Text(activity.eventName, color = ChatColors.Primary, style = MaterialTheme.typography.bodyMedium)
            activity.statusMessage?.takeIf(String::isNotBlank)?.let {
                Text(it, color = color, style = MaterialTheme.typography.labelMedium)
            }
            activity.details.take(3).forEach {
                Text(it, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun PlanPanel(
    messageId: String,
    plan: String,
    isStreaming: Boolean,
    onProceed: (() -> Unit)?,
) {
    var showActions by rememberSaveable(messageId) { mutableStateOf(true) }
    Surface(
        color = ChatColors.PlanAccent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ChatColors.PlanAccent.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(IconGlyph.CHECKLIST, Modifier.size(18.dp), ChatColors.PlanAccent)
                Text("  Plan", color = ChatColors.PlanAccent, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = ChatColors.PlanAccent.copy(alpha = 0.25f))
            SelectionContainer { MessageText(plan) }
            if (!isStreaming && showActions && onProceed != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onProceed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ChatColors.PlanAccent,
                            contentColor = Color.Black,
                        ),
                    ) { Text("Proceed") }
                    TextButton(onClick = { showActions = false }) {
                        Text("Stay in Plan", color = ChatColors.PlanAccent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThinkingMessage() {
    ThoughtsPanel(
        messageId = "active-thinking",
        reasoning = null,
        plan = null,
        hooks = emptyList(),
        isStreaming = true,
        expandedByDefault = true,
        onExpansionChanged = {},
    )
}

@Composable
private fun ThinkingPulse() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "thinking-alpha",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics {
            contentDescription = "Codex is thinking"
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    ) {
        Box(
            Modifier
                .alpha(alpha)
                .size(8.dp)
                .background(ChatColors.ThoughtAccent, RoundedCornerShape(4.dp)),
        )
        Text("Thinking…", color = ChatColors.Secondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ThinkingDot() {
    val transition = rememberInfiniteTransition(label = "step-thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "step-thinking-alpha",
    )
    Box(
        Modifier
            .alpha(alpha)
            .size(7.dp)
            .background(ChatColors.ThoughtAccent, RoundedCornerShape(4.dp)),
    )
}
