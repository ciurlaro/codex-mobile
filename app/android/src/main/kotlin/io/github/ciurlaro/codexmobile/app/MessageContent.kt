package io.github.ciurlaro.codexmobile.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ciurlaro.codexmobile.core.AgentCapability
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography

@Composable
internal fun UserMessage(message: ChatMessage) {
    val shellCommand = message.text.shellCommandOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (shellCommand != null) {
            SentShellCommand(shellCommand)
            return@Row
        }
        Column(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .background(ChatColors.UserBubble, RoundedCornerShape(ChatDimensions.MessageCorner))
                .semantics { contentDescription = "User message" }
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (message.text.isNotEmpty()) {
                Text(message.text, color = ChatColors.Primary, style = MaterialTheme.typography.bodyLarge)
            }
            message.capabilities.forEach { capability ->
                Text(
                    text = capabilityPrompt(capability),
                    style = MaterialTheme.typography.bodyLarge,
                )
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
internal fun CodexMessage(message: ChatMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Codex message" }
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            message.shellCommand != null -> ShellCommandOutput(message)

            message.text.isNotEmpty() -> MessageText(message.text)

            message.isStreaming -> ThinkingMessage()
        }
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
    val markdown = remember(text) { text.normalizeMarkdownTaskLists() }
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
                        TerminalCodeBlock(code, language, style)
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
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "thinking-alpha",
    )
    Text(
        text = "Thinking",
        color = ChatColors.Secondary,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.alpha(alpha).semantics {
            contentDescription = "Codex is thinking"
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    )
}
