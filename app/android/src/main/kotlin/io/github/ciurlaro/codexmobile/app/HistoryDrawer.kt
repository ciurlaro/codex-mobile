package io.github.ciurlaro.codexmobile.app

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
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import kotlin.math.roundToInt

@Composable
internal fun HistoryDrawer(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
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
            onValueChange = { onEvent(ChatUiEvent.SearchHistory(it)) },
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
                        onSelect = { onEvent(ChatUiEvent.OpenConversation(conversation.sessionId)) },
                        menuPosition = menuPosition,
                        onOpenMenu = { position ->
                            menuPosition = position
                            menuConversation = conversation
                        },
                        onDismissMenu = { menuConversation = null },
                        onTogglePin = {
                            menuConversation = null
                            onEvent(ChatUiEvent.TogglePinConversation(conversation.sessionId))
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
                        onSelect = { onEvent(ChatUiEvent.OpenConversation(conversation.sessionId)) },
                        menuPosition = menuPosition,
                        onOpenMenu = { position ->
                            menuPosition = position
                            menuConversation = conversation
                        },
                        onDismissMenu = { menuConversation = null },
                        onTogglePin = {
                            menuConversation = null
                            onEvent(ChatUiEvent.TogglePinConversation(conversation.sessionId))
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
            onClick = { onEvent(ChatUiEvent.StartNewChat) },
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
                .clickable { onEvent(ChatUiEvent.OpenSettings) }
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
                Text("CM", color = ChatColors.Primary, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Codex Mobile", color = ChatColors.Primary)
                Text("Settings", color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
            AppIcon(IconGlyph.CHEVRON_RIGHT, Modifier.size(20.dp), ChatColors.Secondary)
        }
    }
    renameConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = { renameConversation = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(80) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            dismissButton = {
                TextButton(onClick = { renameConversation = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        onEvent(ChatUiEvent.RenameConversation(conversation.sessionId, renameText))
                        renameConversation = null
                    },
                ) { Text("Rename") }
            },
        )
    }
    deleteConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteConversation = null },
            title = { Text("Delete conversation?") },
            text = { Text("“${conversation.title}” will be permanently deleted.") },
            dismissButton = {
                TextButton(onClick = { deleteConversation = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEvent(ChatUiEvent.DeleteConversation(conversation.sessionId))
                        deleteConversation = null
                    },
                ) { Text("Delete", color = ChatColors.Danger) }
            },
        )
    }
}

@Composable
private fun ConversationSectionTitle(title: String) {
    Text(
        text = title,
        color = ChatColors.Primary,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun HistoryConversationRow(
    conversation: AgentConversationSummary,
    selected: Boolean,
    pinned: Boolean,
    menuExpanded: Boolean,
    menuPosition: Offset,
    onSelect: () -> Unit,
    onOpenMenu: (Offset) -> Unit,
    onDismissMenu: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = when {
            pressed -> ChatColors.Elevated
            selected -> ChatColors.ElevatedStrong
            else -> Color.Transparent
        },
        animationSpec = tween(90),
        label = "history-press",
    )
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatDimensions.TouchTarget)
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .indication(interactionSource, ripple(color = ChatColors.Primary.copy(alpha = 0.14f)))
                .pointerInput(conversation.sessionId) {
                    detectTapGestures(
                        onPress = { position ->
                            val press = PressInteraction.Press(position)
                            interactionSource.emit(press)
                            interactionSource.emit(
                                if (tryAwaitRelease()) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onSelect() },
                        onLongPress = onOpenMenu,
                    )
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    if (selected) stateDescription = "Current conversation"
                    onClick("Open conversation") {
                        onSelect()
                        true
                    }
                    onLongClick("Conversation actions") {
                        onOpenMenu(Offset.Zero)
                        true
                    }
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) ChatColors.Primary else ChatColors.Secondary,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                ),
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(7.dp)
                        .background(ChatColors.Accent, CircleShape),
                )
            }
        }
        Box(
            Modifier
                .offset {
                    IntOffset(menuPosition.x.roundToInt(), menuPosition.y.roundToInt())
                }
                .size(1.dp),
        ) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin" else "Pin") },
                    leadingIcon = {
                        AppIcon(IconGlyph.PIN, Modifier.size(22.dp), ChatColors.Primary)
                    },
                    onClick = onTogglePin,
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = {
                        AppIcon(IconGlyph.EDIT, Modifier.size(22.dp), ChatColors.Primary)
                    },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = ChatColors.Danger) },
                    leadingIcon = {
                        AppIcon(IconGlyph.TRASH, Modifier.size(22.dp), ChatColors.Danger)
                    },
                    onClick = onDelete,
                )
            }
        }
    }
}
