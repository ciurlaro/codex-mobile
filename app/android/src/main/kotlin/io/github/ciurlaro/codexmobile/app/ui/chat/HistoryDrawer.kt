package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import io.github.ciurlaro.codexmobile.app.R
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.groupedByPins
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import kotlin.math.roundToInt

@Composable
internal fun HistoryDrawer(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    var menuConversation by remember { mutableStateOf<AgentConversationSummary?>(null) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    var renameConversation by remember { mutableStateOf<AgentConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteConversation by remember { mutableStateOf<AgentConversationSummary?>(null) }
    val query = state.historySearch.trim()
    val visibleConversations = remember(state.conversations, query) {
        if (query.isEmpty()) state.conversations
        else state.conversations.filter { it.title.contains(query, ignoreCase = true) }
    }
    val groups = remember(visibleConversations, state.pinnedConversationIds) {
        visibleConversations.groupedByPins(state.pinnedConversationIds)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Codex Mobile",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            CircleIconButton("Search conversations", IconGlyph.SEARCH) {
                searchFocusRequester.requestFocus()
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.historySearch,
            onValueChange = { onEvent(AppUiEvent.SearchHistory(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocusRequester),
            placeholder = { Text("Search", color = ChatColors.Secondary) },
            leadingIcon = { AppIcon(IconGlyph.SEARCH, Modifier.size(20.dp), ChatColors.Secondary) },
            singleLine = true,
            shape = RoundedCornerShape(ChatDimensions.ControlCorner),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ChatColors.Elevated,
                unfocusedContainerColor = ChatColors.Elevated,
                focusedBorderColor = ChatColors.Border,
                unfocusedBorderColor = ChatColors.Border,
                focusedTextColor = ChatColors.Primary,
                unfocusedTextColor = ChatColors.Primary,
                cursorColor = ChatColors.Accent,
            ),
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (groups.pinned.isNotEmpty()) {
                item("pinned-heading") { ConversationSectionTitle("Pinned") }
                items(groups.pinned, key = { "pinned-${it.sessionId.value}" }) { conversation ->
                    HistoryConversationRow(
                        conversation = conversation,
                        selected = conversation.sessionId == state.sessionId,
                        pinned = true,
                        menuExpanded = menuConversation?.sessionId == conversation.sessionId,
                        onSelect = { onEvent(AppUiEvent.OpenConversation(conversation.sessionId)) },
                        menuPosition = menuPosition,
                        onOpenMenu = { position ->
                            menuPosition = position
                            menuConversation = conversation
                        },
                        onDismissMenu = { menuConversation = null },
                        onTogglePin = {
                            menuConversation = null
                            onEvent(AppUiEvent.TogglePinConversation(conversation.sessionId))
                        },
                        onRename = {
                            menuConversation = null
                            renameConversation = conversation
                            renameText = conversation.title
                        },
                        onDelete = {
                            menuConversation = null
                            deleteConversation = conversation
                        },
                    )
                }
            }
            if (groups.recent.isNotEmpty()) {
                item("recent-heading") { ConversationSectionTitle("Recents") }
                items(groups.recent, key = { "recent-${it.sessionId.value}" }) { conversation ->
                    HistoryConversationRow(
                        conversation = conversation,
                        selected = conversation.sessionId == state.sessionId,
                        pinned = false,
                        menuExpanded = menuConversation?.sessionId == conversation.sessionId,
                        onSelect = { onEvent(AppUiEvent.OpenConversation(conversation.sessionId)) },
                        menuPosition = menuPosition,
                        onOpenMenu = { position ->
                            menuPosition = position
                            menuConversation = conversation
                        },
                        onDismissMenu = { menuConversation = null },
                        onTogglePin = {
                            menuConversation = null
                            onEvent(AppUiEvent.TogglePinConversation(conversation.sessionId))
                        },
                        onRename = {
                            menuConversation = null
                            renameConversation = conversation
                            renameText = conversation.title
                        },
                        onDelete = {
                            menuConversation = null
                            deleteConversation = conversation
                        },
                    )
                }
            }
            if (visibleConversations.isEmpty()) {
                item("empty-history") {
                    Text(
                        text = if (query.isEmpty()) "No conversations yet" else "No matching conversations",
                        color = ChatColors.Secondary,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
        Button(
            onClick = { onEvent(AppUiEvent.StartNewChat) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(ChatDimensions.ControlCorner),
            colors = ButtonDefaults.buttonColors(containerColor = ChatColors.Accent),
        ) {
            AppIcon(IconGlyph.NEW_CHAT, Modifier.size(22.dp), Color.White)
            Spacer(Modifier.width(10.dp))
            Text("New chat", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ChatDimensions.ControlCorner))
                .clickable { onEvent(AppUiEvent.OpenSettings) }
                .clearAndSetSemantics {
                    contentDescription = "Open Settings"
                    role = Role.Button
                }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ChatDimensions.TouchTarget)
                    .background(ChatColors.ElevatedStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_codex_status),
                    contentDescription = null,
                    tint = ChatColors.Primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Codex Mobile", color = ChatColors.Primary)
                Text("Settings", color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
            AppIcon(IconGlyph.CHEVRON_RIGHT, Modifier.size(20.dp), ChatColors.Secondary)
        }
    }
    HistoryDialogs(
        renameConversation = renameConversation,
        renameText = renameText,
        deleteConversation = deleteConversation,
        onRenameTextChanged = { renameText = it },
        onDismissRename = { renameConversation = null },
        onConfirmRename = { conversation ->
            onEvent(AppUiEvent.RenameConversation(conversation.sessionId, renameText))
            renameConversation = null
        },
        onDismissDelete = { deleteConversation = null },
        onConfirmDelete = { conversation ->
            onEvent(AppUiEvent.DeleteConversation(conversation.sessionId))
            deleteConversation = null
        },
    )
}
