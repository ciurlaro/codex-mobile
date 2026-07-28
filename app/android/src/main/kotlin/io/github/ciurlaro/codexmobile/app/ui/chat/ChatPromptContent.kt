package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentCapability

@Composable
internal fun SentShellCommand(command: String) {
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
internal fun TerminalHeader(label: String, color: Color) {
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

internal fun capabilityPrompt(capability: AgentCapability): AnnotatedString = buildAnnotatedString {
    val label = capability.promptLabel
    val prefixLength = label.indexOf(capability.displayLabel, ignoreCase = true).coerceAtLeast(0)
    append(label)
    addStyle(SpanStyle(color = ChatColors.Primary), 0, prefixLength)
    addStyle(SpanStyle(color = ChatColors.Accent), prefixLength, label.length)
}

@Composable
internal fun ShellCommandOutput(message: ChatMessage) {
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
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
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
