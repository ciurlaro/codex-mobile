package io.github.ciurlaro.codexmobile.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentHookTrustStatus

@Composable
internal fun HooksScreen(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    var hookToTrust by remember { mutableStateOf<AgentHook?>(null) }
    Column(
        Modifier.fillMaxSize().background(ChatColors.Background).statusBarsPadding(),
    ) {
        Box(
            Modifier.fillMaxWidth().height(ChatDimensions.TopBarHeight)
                .padding(horizontal = ChatDimensions.ScreenPadding),
        ) {
            CircleIconButton(
                label = "Back to settings",
                glyph = IconGlyph.BACK,
                modifier = Modifier.align(Alignment.CenterStart),
            ) { onEvent(AppUiEvent.CloseHooks) }
            Text(
                "Hooks",
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
            CircleIconButton(
                label = "Refresh hooks",
                glyph = IconGlyph.SEARCH,
                modifier = Modifier.align(Alignment.CenterEnd),
                enabled = !state.isHooksLoading,
            ) { onEvent(AppUiEvent.RefreshHooks) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ChatDimensions.ScreenPadding,
                end = ChatDimensions.ScreenPadding,
                top = 8.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("intro") {
                Text(
                    "Review lifecycle hooks before allowing them to run. Modified hooks must be trusted again.",
                    color = ChatColors.Secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.isHooksLoading && state.hooks.isEmpty()) {
                item("loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ChatColors.Accent)
                    }
                }
            }
            state.hooksError?.let { error ->
                item("error") { NoticeCard(error, ChatColors.Danger) }
            }
            state.hooksWarnings.forEachIndexed { index, warning ->
                item("warning-$index") { NoticeCard(warning, ChatColors.MathAccent) }
            }
            if (!state.isHooksLoading && state.hooks.isEmpty() && state.hooksError == null) {
                item("empty") {
                    Text(
                        "No hooks found for this workspace",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                        color = ChatColors.Secondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(state.hooks, key = AgentHook::key) { hook ->
                HookCard(
                    hook = hook,
                    busy = state.isHooksLoading,
                    onToggle = { onEvent(AppUiEvent.ToggleHook(hook, it)) },
                    onTrust = { hookToTrust = hook },
                )
            }
        }
    }
    hookToTrust?.let { hook ->
        AlertDialog(
            onDismissRequest = { hookToTrust = null },
            title = { Text("Trust this hook?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Trust only hooks whose source and command you recognize.")
                    Text(hook.sourcePath, fontFamily = FontFamily.Monospace)
                    hook.command?.let { Text(it, fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    hookToTrust = null
                    onEvent(AppUiEvent.TrustHook(hook))
                }) { Text("Trust current version") }
            },
            dismissButton = {
                TextButton(onClick = { hookToTrust = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HookCard(
    hook: AgentHook,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onTrust: () -> Unit,
) {
    val needsTrust = hook.trustStatus == AgentHookTrustStatus.UNTRUSTED ||
        hook.trustStatus == AgentHookTrustStatus.MODIFIED
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
        color = ChatColors.ElevatedStrong,
        border = BorderStroke(1.dp, if (needsTrust) ChatColors.MathAccent else ChatColors.Border),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(IconGlyph.LINK, Modifier.size(24.dp), ChatColors.Accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        hook.key,
                        color = ChatColors.Primary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${hook.eventName.toDisplayName()} · ${hook.handlerType.toDisplayName()}",
                        color = ChatColors.Secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = hook.enabled,
                    onCheckedChange = onToggle,
                    enabled = !busy && !hook.isManaged && !needsTrust,
                )
            }
            Text(
                hook.sourcePath,
                color = ChatColors.Secondary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${hook.source.toDisplayName()} · ${hook.timeoutSeconds}s timeout",
                color = ChatColors.Secondary,
                style = MaterialTheme.typography.bodySmall,
            )
            hook.matcher?.takeIf(String::isNotBlank)?.let {
                Text("Matches: $it", color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
            hook.command?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().background(
                        ChatColors.CodeSurface,
                        RoundedCornerShape(ChatDimensions.ControlCorner),
                    ).padding(10.dp),
                    color = ChatColors.Primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                when {
                    hook.isManaged -> "Managed · read only"
                    hook.trustStatus == AgentHookTrustStatus.TRUSTED -> "Trusted"
                    hook.trustStatus == AgentHookTrustStatus.MODIFIED -> "Modified since it was trusted"
                    else -> "Review required"
                },
                color = if (needsTrust) ChatColors.MathAccent else ChatColors.PluginAccent,
                style = MaterialTheme.typography.labelLarge,
            )
            if (needsTrust) {
                Button(onClick = onTrust, enabled = !busy) { Text("Review and trust") }
            }
            hook.statusMessage?.let {
                Text(it, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, accent: androidx.compose.ui.graphics.Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatColors.ElevatedStrong,
        border = BorderStroke(1.dp, accent),
        shape = RoundedCornerShape(ChatDimensions.ControlCorner),
    ) {
        Text(text, Modifier.padding(14.dp), color = accent, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun String.toDisplayName(): String = lowercase().split('_').joinToString(" ") {
    it.replaceFirstChar(Char::uppercase)
}
