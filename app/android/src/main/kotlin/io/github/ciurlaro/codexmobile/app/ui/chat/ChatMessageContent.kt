package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import io.github.ciurlaro.codexmobile.app.presentation.formatting.normalizeMarkdownTaskLists
import io.github.ciurlaro.codexmobile.app.presentation.formatting.normalizeMathMarkdown
import io.github.ciurlaro.codexmobile.app.presentation.formatting.distinctThoughts
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.invocation.promptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.AgentHookActivity
import io.github.ciurlaro.codexmobile.core.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.core.AgentPlanProgress
import io.github.ciurlaro.codexmobile.core.AgentPlanStepStatus
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import io.ratex.RaTeXView

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
                Text(
                    text = capabilityPrompt(capability),
                    style = MaterialTheme.typography.bodyLarge,
                )
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
private fun SentShellCommand(command: String) {
    Column(
        modifier = Modifier
            .widthIn(max = 330.dp)
            .background(ChatColors.ShellBubble, RoundedCornerShape(ChatDimensions.MessageCorner))
            .border(1.dp, ChatColors.Accent.copy(alpha = 0.65f), RoundedCornerShape(ChatDimensions.MessageCorner))
            .semantics { contentDescription = "User shell command" }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShellHeader()
        ShellPrompt(prefix = "!", command = command)
    }
}

@Composable
internal fun ShellHeader() {
    TerminalHeader("SHELL COMMAND", ChatColors.Accent)
}

@Composable
internal fun PlanHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(IconGlyph.CHECKLIST, Modifier.size(18.dp), ChatColors.PlanAccent)
        Text(
            "  PLAN",
            color = ChatColors.PlanAccent,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TerminalHeader(label: String, color: Color) {
    Text(
        text = ">_  $label",
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun ShellPrompt(prefix: String, command: String) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = ChatColors.Accent, fontWeight = FontWeight.Bold)) {
                append(prefix)
            }
            append("  ")
            append(command)
        },
        color = ChatColors.Primary,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun capabilityPrompt(capability: AgentCapability): AnnotatedString = buildAnnotatedString {
    val label = capability.promptLabel
    val prefixLength = label.indexOf(capability.displayLabel, ignoreCase = true).coerceAtLeast(0)
    append(label)
    addStyle(SpanStyle(color = ChatColors.Primary), 0, prefixLength)
    addStyle(SpanStyle(color = ChatColors.Accent), prefixLength, label.length)
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
        } else {
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
}

@Composable
private fun ThoughtsPanel(
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
                AgentPlanStepStatus.COMPLETED -> AppIcon(IconGlyph.CHECK, Modifier.size(11.dp), ChatColors.PluginAccent)
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
        AgentHookRunStatus.FAILED, AgentHookRunStatus.BLOCKED, AgentHookRunStatus.STOPPED -> ChatColors.Danger
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
private fun PlanPanel(
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

@Composable
private fun ShellCommandOutput(message: ChatMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.Elevated, RoundedCornerShape(16.dp))
            .border(1.dp, ChatColors.Border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShellPrompt(prefix = "\$", command = message.shellCommand.orEmpty())
        HorizontalDivider(color = ChatColors.Border)
        if (message.text.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.CodeSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, ChatColors.Border, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "OUTPUT",
                    color = ChatColors.Secondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = message.text,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    color = ChatColors.Primary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                    softWrap = false,
                )
            }
        }
        when {
            message.isStreaming -> Text(
                "Running…",
                color = ChatColors.Secondary,
                style = MaterialTheme.typography.labelMedium,
            )

            message.exitCode != null -> Text(
                "Exit ${message.exitCode}",
                color = if (message.exitCode == 0) ChatColors.Secondary else ChatColors.Danger,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MessageText(text: String) {
    val markdown = remember(text) { text.normalizeMarkdownTaskLists().normalizeMathMarkdown() }
    val delegate = LocalUriHandler.current
    val safeLinks = remember(delegate) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val scheme = android.net.Uri.parse(uri).scheme?.lowercase()
                if (scheme == "http" || scheme == "https") delegate.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides safeLinks) {
        Markdown(
            content = markdown,
            imageTransformer = MathImageTransformer,
            typography = markdownTypography(
                code = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                inlineCode = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            ),
            components = markdownComponents(
                checkbox = { model ->
                    MarkdownCheckBox(model.content, model.node, model.typography.text)
                },
                codeFence = { model ->
                    MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                        if (language.isMathLanguage()) MathBlock(code) else TerminalCodeBlock(code, language, style)
                    }
                },
                codeBlock = { model ->
                    MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
                        TerminalCodeBlock(code, language, style)
                    }
                },
            ),
        )
    }
}

private fun String?.isMathLanguage() = this?.trim()?.lowercase() in setOf("math", "latex", "tex")

@Composable
private fun MathBlock(formula: String) {
    val color = ChatColors.Primary.toArgb()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.MathAccent.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .border(1.dp, ChatColors.MathAccent.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                "Math",
                color = ChatColors.MathAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        HorizontalDivider(color = ChatColors.MathAccent.copy(alpha = 0.25f))
        AndroidView(
            factory = { context ->
                RaTeXView(context).apply {
                    fontSize = 22f
                    displayMode = true
                    this.color = color
                    setPadding(0, 0, 0, 0)
                }
            },
            update = {
                it.color = color
                it.latex = formula.trim()
            },
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(14.dp),
        )
    }
}

@Composable
private fun TerminalCodeBlock(code: String, language: String?, style: TextStyle) {
    val label = language.orEmpty().trim().ifEmpty { "CODE" }.take(24).uppercase()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.CodeSurface, RoundedCornerShape(16.dp))
            .border(1.dp, ChatColors.CodeAccent.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            TerminalHeader(label, ChatColors.CodeAccent)
        }
        HorizontalDivider(color = ChatColors.Border)
        Text(
            text = code.trimEnd(),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(14.dp),
            color = ChatColors.Primary,
            style = style.copy(
                color = ChatColors.Primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            softWrap = false,
        )
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
        Text(
            text = "Thinking…",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.bodyMedium,
        )
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
