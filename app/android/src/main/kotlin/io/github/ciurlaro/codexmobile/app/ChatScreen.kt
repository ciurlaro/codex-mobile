package io.github.ciurlaro.codexmobile.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop

private object ChatColors {
    val Background = Color.Black
    val Drawer = Color(0xFF101010)
    val Elevated = Color(0xFF202020)
    val ElevatedStrong = Color(0xFF2A2A2A)
    val UserBubble = Color(0xFF3D3D3D)
    val ShellBubble = Color(0xFF17345C)
    val CodeSurface = Color(0xFF151515)
    val Border = Color(0xFF3A3A3A)
    val Primary = Color(0xFFF5F5F5)
    val Secondary = Color(0xFFA5A5A5)
    val Accent = Color(0xFF3F83F8)
    val CodeAccent = Color(0xFF58C77B)
    val Danger = Color(0xFFFF7A83)
    val Scrim = Color(0x99000000)
}

internal val shellCommandVisualTransformation = VisualTransformation { source ->
    if (!source.text.startsWith('!')) {
        TransformedText(source, OffsetMapping.Identity)
    } else {
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = ChatColors.Accent, fontWeight = FontWeight.Bold)) {
                append('!')
            }
            append("  ")
            append(source.subSequence(1, source.length))
        }
        TransformedText(
            text,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    if (offset == 0) 0 else offset + 2

                override fun transformedToOriginal(offset: Int): Int =
                    if (offset == 0) 0 else if (offset <= 3) 1 else offset - 2
            },
        )
    }
}

private object ChatDimensions {
    val ScreenPadding = 16.dp
    val TopBarHeight = 64.dp
    val TouchTarget = 48.dp
    val ControlCorner = 24.dp
    val CardCorner = 28.dp
    val ComposerCorner = 30.dp
    val MessageCorner = 24.dp
    val SelectorWidth = 340.dp
    val SelectorBottomOffset = 112.dp
}

private val CodexDarkScheme = darkColorScheme(
    primary = ChatColors.Accent,
    onPrimary = Color.White,
    background = ChatColors.Background,
    onBackground = ChatColors.Primary,
    surface = ChatColors.Elevated,
    onSurface = ChatColors.Primary,
    surfaceVariant = ChatColors.ElevatedStrong,
    onSurfaceVariant = ChatColors.Secondary,
    outline = ChatColors.Border,
    error = ChatColors.Danger,
)

@Composable
internal fun CodexMobileTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = CodexDarkScheme) {
        content()
    }
}

@Composable
internal fun CodexMobileApp(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    CodexMobileTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = ChatColors.Background,
        ) {
            Box {
                when (state.destination) {
                    AppDestination.CHAT -> ChatDrawer(state, onEvent)
                    AppDestination.SETTINGS -> SettingsScreen(state, onEvent)
                }
                if (state.popup != ChatPopup.NONE) {
                    SelectorOverlay(
                        state = state,
                        onEvent = onEvent,
                        aboveComposer = state.destination == AppDestination.CHAT,
                    )
                }
            }
        }
        BackHandler(
            enabled = state.popup != ChatPopup.NONE || state.drawerOpen ||
                state.destination == AppDestination.SETTINGS,
        ) {
            when {
                state.popup != ChatPopup.NONE -> onEvent(ChatUiEvent.DismissPopup)
                state.drawerOpen -> onEvent(ChatUiEvent.CloseHistory)
                state.destination == AppDestination.SETTINGS -> onEvent(ChatUiEvent.CloseSettings)
            }
        }
    }
}

@Composable
private fun ChatDrawer(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val shouldBeOpen by rememberUpdatedState(state.drawerOpen)
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(state.drawerOpen) {
            if (state.drawerOpen) {
                focusManager.clearFocus()
                keyboard?.hide()
                drawerState.open()
            } else {
                drawerState.close()
            }
        }
        LaunchedEffect(drawerState) {
            snapshotFlow { drawerState.currentValue to drawerState.targetValue }
                .drop(1)
                .collect { (current, target) ->
                    if (target == DrawerValue.Open) {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                    if (current == DrawerValue.Closed && target == DrawerValue.Closed && shouldBeOpen) {
                        onEvent(ChatUiEvent.CloseHistory)
                    }
                }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = state.destination == AppDestination.CHAT,
            scrimColor = ChatColors.Scrim,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .width(maxWidth * 0.84f)
                        .fillMaxHeight(),
                    drawerContainerColor = ChatColors.Drawer,
                    drawerContentColor = ChatColors.Primary,
                ) {
                    HistoryDrawer(state, onEvent)
                }
            },
        ) {
            ChatScreen(state, onEvent)
        }
    }
}

@Composable
private fun HistoryDrawer(
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
                        onSelect = { onEvent(ChatUiEvent.SelectConversation(conversation.sessionId)) },
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
                        onSelect = { onEvent(ChatUiEvent.SelectConversation(conversation.sessionId)) },
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
            onClick = { onEvent(ChatUiEvent.FreshChat) },
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

@Composable
private fun ChatScreen(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        ChatTopBar(state, onEvent)
        ConversationList(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onEvent = onEvent,
        )
        Composer(state, onEvent)
    }
}

@Composable
private fun ChatTopBar(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChatDimensions.TopBarHeight)
            .padding(horizontal = ChatDimensions.ScreenPadding),
    ) {
        CircleIconButton(
            label = "Open conversation history",
            glyph = IconGlyph.MENU,
            modifier = Modifier.align(Alignment.CenterStart),
        ) { onEvent(ChatUiEvent.OpenHistory) }
        Row(
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                "Codex Mobile",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (state.sessionId != null || state.messages.isNotEmpty()) {
            CircleIconButton(
                label = "Start a new chat",
                glyph = IconGlyph.NEW_CHAT,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) { onEvent(ChatUiEvent.FreshChat) }
        }
    }
}

@Composable
private fun ConversationList(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Box(modifier) {
        when {
            state.conversationLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(28.dp),
                color = ChatColors.Accent,
                strokeWidth = 2.dp,
            )

            !state.authenticated -> SignInState(state, onEvent, Modifier.align(Alignment.Center))

            else -> {
                val listState = rememberLazyListState()
                val extraThinking = state.turnActive && state.messages.none(ChatMessage::streaming)
                val itemCount = state.messages.size + if (extraThinking) 1 else 0
                val lastLength = state.messages.lastOrNull()?.text?.length ?: 0
                LaunchedEffect(itemCount, lastLength) {
                    if (itemCount == 0) return@LaunchedEffect
                    val lastIndex = itemCount - 1
                    val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    if (visibleLast == null || visibleLast >= lastIndex - 1) {
                        listState.scrollToItem(lastIndex)
                    }
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
                            AgentMessageRole.USER -> UserMessage(message)
                            AgentMessageRole.CODEX -> CodexMessage(message)
                        }
                    }
                    if (extraThinking) {
                        item("codex-thinking") { ThinkingMessage() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInState(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            state.status,
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        when {
            state.authenticationBusy -> CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = ChatColors.Accent,
                strokeWidth = 3.dp,
            )

            state.signInUrl != null -> {
                Button(
                    onClick = { onEvent(ChatUiEvent.OpenSignIn) },
                    modifier = Modifier.heightIn(min = ChatDimensions.TouchTarget),
                ) { Text("Open sign-in again") }
                Button(
                    onClick = { onEvent(ChatUiEvent.CancelAuthentication) },
                    modifier = Modifier.heightIn(min = ChatDimensions.TouchTarget),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatColors.ElevatedStrong),
                ) { Text("Cancel sign-in") }
            }

            else -> Button(
                onClick = { onEvent(ChatUiEvent.Authenticate) },
                modifier = Modifier.heightIn(min = ChatDimensions.TouchTarget),
            ) { Text("Sign in with ChatGPT") }
        }
    }
}

@Composable
private fun UserMessage(message: ChatMessage) {
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
private fun ShellHeader() {
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
private fun CodexMessage(message: ChatMessage) {
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

            message.streaming -> ThinkingMessage()
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
            message.streaming -> Text(
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
private fun ThinkingMessage() {
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

@Composable
private fun Composer(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shellMode = state.draft.startsWith('!')
    val expanded = focused || state.draft.contains('\n') || state.selectedCapabilities.isNotEmpty()
    val canSend = state.authenticated &&
        if (shellMode) state.draft.drop(1).isNotBlank()
        else state.draft.isNotBlank() || state.selectedCapabilities.isNotEmpty()
    val composerColor by animateColorAsState(
        if (shellMode) Color(0xFF182433) else ChatColors.Elevated,
        label = "composer-mode",
    )
    val composerBorder by animateColorAsState(
        if (shellMode) ChatColors.Accent else ChatColors.Border,
        label = "composer-border",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatDimensions.ScreenPadding, vertical = 10.dp)
            .background(composerColor, RoundedCornerShape(ChatDimensions.ComposerCorner))
            .border(
                BorderStroke(1.dp, composerBorder),
                RoundedCornerShape(ChatDimensions.ComposerCorner),
            )
            .semantics {
                if (shellMode) stateDescription = "Shell command mode"
            }
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (shellMode) {
            Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { ShellHeader() }
        }
        state.selectedCapabilities.forEach { capability ->
            CapabilityChip(capability) { onEvent(ChatUiEvent.RemoveCapability(capability)) }
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = state.draft,
            onValueChange = { onEvent(ChatUiEvent.UpdateDraft(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (expanded) 52.dp else 38.dp, max = 160.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = if (shellMode) "Shell command" else "Message"
                },
            enabled = state.authenticated,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = ChatColors.Primary,
                fontFamily = if (shellMode) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (shellMode) FontWeight.Medium else FontWeight.Normal,
            ),
            cursorBrush = SolidColor(ChatColors.Accent),
            visualTransformation = if (shellMode) {
                shellCommandVisualTransformation
            } else {
                VisualTransformation.None
            },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.TopStart) {
                    if (state.draft.isEmpty()) {
                        Text(
                            when {
                                shellMode -> "Run a shell command"
                                state.messages.isEmpty() -> "Ask Codex"
                                else -> "Reply to Codex"
                            },
                            color = ChatColors.Secondary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                label = "Add prompt tag",
                glyph = IconGlyph.PLUS,
                enabled = !shellMode,
                containerColor = Color.Transparent,
            ) { onEvent(ChatUiEvent.ShowTags) }
            SelectionPill(
                state = state,
                modifier = Modifier.weight(1f),
                onClick = { onEvent(ChatUiEvent.ShowEffort) },
            )
            CircleIconButton(
                label = when {
                    state.turnActive -> "Stop response"
                    shellMode -> "Run shell command"
                    else -> "Send message"
                },
                glyph = if (state.turnActive) IconGlyph.STOP else IconGlyph.SEND,
                enabled = state.turnActive || canSend,
                containerColor = if (state.turnActive || canSend) ChatColors.Accent else ChatColors.ElevatedStrong,
            ) {
                onEvent(if (state.turnActive) ChatUiEvent.Stop else ChatUiEvent.Send)
            }
        }
    }
}

@Composable
private fun CapabilityChip(
    capability: AgentCapability,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = ChatDimensions.TouchTarget)
            .background(ChatColors.ElevatedStrong, RoundedCornerShape(ChatDimensions.ControlCorner))
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOfNotNull(capability.icon, capability.displayLabel).joinToString(" "),
            color = ChatColors.Accent,
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(ChatDimensions.TouchTarget)
                .semantics { contentDescription = "Remove ${capability.displayLabel}" },
        ) {
            AppIcon(IconGlyph.CLOSE, Modifier.size(18.dp), ChatColors.Secondary)
        }
    }
}

@Composable
private fun SelectionPill(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = selectionLabel(state),
        modifier = modifier
            .heightIn(min = ChatDimensions.TouchTarget)
            .clip(RoundedCornerShape(ChatDimensions.ControlCorner))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 10.dp, vertical = 14.dp),
        color = ChatColors.Primary,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun selectionLabel(state: MainUiState): String {
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    val modelLabel = model?.displayName ?: state.selectedModel ?: "Model"
    return state.selectedEffort?.let { "$modelLabel · ${effortLabel(it)}" } ?: modelLabel
}

@Composable
private fun SelectorOverlay(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
    aboveComposer: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.popup) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onEvent(ChatUiEvent.DismissPopup) },
            )
            .semantics {
                contentDescription = "Dismiss selector"
                role = Role.Button
            },
    ) {
        Surface(
            modifier = Modifier
                .align(if (aboveComposer) Alignment.BottomEnd else Alignment.Center)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(
                    end = ChatDimensions.ScreenPadding,
                    start = ChatDimensions.ScreenPadding,
                    bottom = if (aboveComposer) ChatDimensions.SelectorBottomOffset else 0.dp,
                )
                .widthIn(max = ChatDimensions.SelectorWidth)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            shape = RoundedCornerShape(ChatDimensions.CardCorner),
            color = ChatColors.Elevated,
            contentColor = ChatColors.Primary,
            border = BorderStroke(1.dp, ChatColors.Border),
            tonalElevation = 8.dp,
        ) {
            when (state.popup) {
                ChatPopup.EFFORT -> EffortSelector(state, onEvent)
                ChatPopup.MODEL -> ModelSelector(state, onEvent)
                ChatPopup.TAGS -> TagSelector(state, onEvent)
                ChatPopup.SPEED -> SpeedSelector(state, onEvent)
                ChatPopup.APPROVAL -> ApprovalSelector(state, onEvent)
                ChatPopup.NONE -> Unit
            }
        }
    }
}

@Composable
private fun EffortSelector(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        SelectorRow(
            title = "Model",
            subtitle = model?.displayName ?: state.selectedModel ?: "Unavailable",
            selected = false,
            trailing = IconGlyph.CHEVRON_RIGHT,
            onClick = { onEvent(ChatUiEvent.ShowModels) },
        )
        HorizontalDivider(color = ChatColors.Border, modifier = Modifier.padding(horizontal = 20.dp))
        SelectorRow(
            title = "Speed",
            subtitle = model?.serviceTiers?.firstOrNull { it.id == state.selectedServiceTier }?.name
                ?: "Default",
            selected = false,
            trailing = IconGlyph.CHEVRON_RIGHT,
            onClick = { onEvent(ChatUiEvent.ShowSpeed) },
        )
        HorizontalDivider(color = ChatColors.Border, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "Intelligence",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
        )
        if (model == null || model.supportedEfforts.isEmpty()) {
            Text(
                "Effort options load after sign-in",
                color = ChatColors.Secondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            model.supportedEfforts.forEach { effort ->
                SelectorRow(
                    title = effortLabel(effort),
                    selected = effort == state.selectedEffort,
                    onClick = { onEvent(ChatUiEvent.SelectEffort(effort)) },
                )
            }
        }
    }
}

@Composable
private fun ModelSelector(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    LazyColumn(Modifier.padding(vertical = 12.dp)) {
        item("model-heading") {
            Text(
                "Model",
                color = ChatColors.Secondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        items(state.models, key = AgentModel::id) { model ->
            SelectorRow(
                title = model.displayName,
                subtitle = model.description.takeIf(String::isNotBlank),
                selected = model.id == state.selectedModel,
                onClick = { onEvent(ChatUiEvent.SelectModel(model.id)) },
            )
        }
        if (state.models.isEmpty()) {
            item("models-unavailable") {
                Text(
                    "Models load after sign-in",
                    color = ChatColors.Secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeedSelector(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    val model = selectedModel(state)
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Text(
            "Speed",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        SelectorRow(
            title = "Default",
            subtitle = "Use the model's default service tier",
            selected = state.selectedServiceTier == null,
            onClick = { onEvent(ChatUiEvent.SelectSpeed(null)) },
        )
        model?.serviceTiers.orEmpty().forEach { tier ->
            SelectorRow(
                title = tier.name,
                subtitle = tier.description.takeIf(String::isNotBlank),
                selected = tier.id == state.selectedServiceTier,
                onClick = { onEvent(ChatUiEvent.SelectSpeed(tier.id)) },
            )
        }
    }
}

@Composable
private fun ApprovalSelector(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Text(
            "Approvals",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        AgentApprovalPreset.entries.forEach { preset ->
            SelectorRow(
                title = preset.displayName,
                subtitle = when (preset) {
                    AgentApprovalPreset.NEVER -> "Run without asking (default)"
                    AgentApprovalPreset.AUTO_REVIEW -> "Let the model review risky actions"
                    AgentApprovalPreset.ASK_ME -> "Ask when Codex requests permission"
                    AgentApprovalPreset.STRICT -> "Ask for commands outside the trusted set"
                },
                selected = preset == state.approvalPreset,
                onClick = { onEvent(ChatUiEvent.SelectApproval(preset)) },
            )
        }
    }
}

@Composable
private fun TagSelector(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    val tags = remember(state.selectedCapabilities) {
        AgentCapability.entries.filter { it !in state.selectedCapabilities }
    }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Text(
            "Prompt tags",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        tags.forEach { capability ->
            SelectorRow(
                title = listOfNotNull(capability.icon, capability.displayLabel).joinToString(" "),
                selected = false,
                onClick = { onEvent(ChatUiEvent.AddCapability(capability)) },
            )
        }
        if (tags.isEmpty()) {
            Text(
                "All available tags are already added",
                color = ChatColors.Secondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun SelectorRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    trailing: IconGlyph? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (selected) stateDescription = "Selected"
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) ChatColors.Primary else ChatColors.Secondary,
                style = MaterialTheme.typography.bodyLarge,
            )
            subtitle?.let {
                Text(
                    it,
                    color = ChatColors.Secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            selected -> AppIcon(IconGlyph.CHECK, Modifier.size(22.dp), ChatColors.Primary)
            trailing != null -> AppIcon(trailing, Modifier.size(20.dp), ChatColors.Primary)
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatColors.Background)
            .statusBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ChatDimensions.TopBarHeight)
                .padding(horizontal = ChatDimensions.ScreenPadding),
        ) {
            CircleIconButton(
                label = "Back to chat",
                glyph = IconGlyph.BACK,
                modifier = Modifier.align(Alignment.CenterStart),
            ) { onEvent(ChatUiEvent.CloseSettings) }
            Text(
                "Settings",
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { heading() },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ChatDimensions.ScreenPadding,
                end = ChatDimensions.ScreenPadding,
                top = 8.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item("model-settings") {
                SettingsGroup("Codex") {
                    SettingsRow(
                        glyph = IconGlyph.SETTINGS,
                        title = "Default model",
                        subtitle = selectedModel(state)?.displayName ?: state.selectedModel ?: "Unavailable",
                        onClick = { onEvent(ChatUiEvent.ShowModels) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.INTELLIGENCE,
                        title = "Default Intelligence",
                        subtitle = state.selectedEffort?.let(::effortLabel) ?: "Unavailable",
                        onClick = { onEvent(ChatUiEvent.ShowEffort) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.SPEED,
                        title = "Default speed",
                        subtitle = selectedModel(state)?.serviceTiers
                            ?.firstOrNull { it.id == state.selectedServiceTier }?.name ?: "Default",
                        onClick = { onEvent(ChatUiEvent.ShowSpeed) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.SHIELD,
                        title = "Approval policy",
                        subtitle = state.approvalPreset.displayName,
                        onClick = { onEvent(ChatUiEvent.ShowApproval) },
                    )
                }
            }
            item("access-settings") {
                SettingsGroup("Android access") {
                    SettingsRow(
                        glyph = IconGlyph.FOLDER,
                        title = if (state.workspacePath != null) "Change workspace" else "Select workspace",
                        subtitle = state.workspacePath ?: "No folder selected",
                        onClick = { onEvent(ChatUiEvent.SelectScope) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.STORAGE,
                        title = "Manage storage permission",
                        subtitle = if (state.storagePermissionGranted) {
                            "All-files access enabled; workspace is the shell starting folder"
                        } else {
                            "All-files access is required for shell file operations"
                        },
                        onClick = { onEvent(ChatUiEvent.ManageStorage) },
                    )
                    if (state.workspacePath != null) {
                        SettingsDivider()
                        SettingsRow(
                            glyph = IconGlyph.CLOSE,
                            title = "Clear workspace selection",
                            danger = true,
                            onClick = { onEvent(ChatUiEvent.ClearWorkspace) },
                        )
                    }
                }
            }
            item("integration-settings") {
                SettingsGroup("Integrations") {
                    SettingsRow(
                        glyph = IconGlyph.LINK,
                        title = "Integrations",
                        subtitle = when {
                            state.telegramConnected -> "Telegram connected"
                            state.telegramAvailable -> "Telegram available"
                            else -> "No integrations available"
                        },
                        onClick = { onEvent(ChatUiEvent.ShowIntegrations) },
                    )
                }
            }
            item("privacy-settings") {
                SettingsGroup("Privacy and data") {
                    SettingsRow(
                        glyph = IconGlyph.LOCK,
                        title = "Privacy details",
                        subtitle = "How Codex Mobile handles local and OpenAI data",
                        onClick = { onEvent(ChatUiEvent.ShowPrivacy) },
                    )
                    if (state.backgroundActive) {
                        SettingsDivider()
                        SettingsRow(
                            glyph = IconGlyph.STOP,
                            title = "Stop background work",
                            subtitle = "Stop the active Codex runtime",
                            onClick = { onEvent(ChatUiEvent.StopBackground) },
                        )
                    }
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.TRASH,
                        title = "Erase Codex Mobile data",
                        subtitle = "Credentials, history, settings and access",
                        danger = true,
                        onClick = { onEvent(ChatUiEvent.ShowEraseConfirmation) },
                    )
                }
            }
            item("account-settings") {
                SettingsGroup("Account") {
                    when {
                        state.authenticated -> SettingsRow(
                            glyph = IconGlyph.LOGOUT,
                            title = "Sign out of ChatGPT",
                            danger = true,
                            onClick = { onEvent(ChatUiEvent.SignOut) },
                        )

                        state.signInUrl != null -> {
                            SettingsRow(
                                glyph = IconGlyph.USER,
                                title = "Open sign-in again",
                                subtitle = "Complete account sign-in in your browser",
                                onClick = { onEvent(ChatUiEvent.OpenSignIn) },
                            )
                            SettingsDivider()
                            SettingsRow(
                                glyph = IconGlyph.BACK,
                                title = "Cancel sign-in",
                                onClick = { onEvent(ChatUiEvent.CancelAuthentication) },
                            )
                        }

                        else -> SettingsRow(
                            glyph = IconGlyph.USER,
                            title = "Sign in with ChatGPT",
                            onClick = { onEvent(ChatUiEvent.Authenticate) },
                        )
                    }
                }
            }
            item("about-settings") {
                SettingsGroup("About") {
                    SettingsRow(
                        glyph = IconGlyph.INFO,
                        title = "Codex Mobile",
                        subtitle = "Native Android Codex client",
                    )
                }
            }
        }
    }
}

private fun selectedModel(state: MainUiState): AgentModel? =
    state.models.firstOrNull { it.id == state.selectedModel }

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ChatDimensions.CardCorner),
            color = ChatColors.ElevatedStrong,
            border = BorderStroke(1.dp, ChatColors.Border),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    glyph: IconGlyph,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val action = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(action)
            .semantics(mergeDescendants = true) { if (onClick != null) role = Role.Button }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            glyph,
            Modifier.size(25.dp),
            if (danger) ChatColors.Danger else ChatColors.Primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) ChatColors.Danger else ChatColors.Primary,
                style = MaterialTheme.typography.bodyLarge,
            )
            subtitle?.let {
                Text(
                    it,
                    color = ChatColors.Secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            AppIcon(IconGlyph.CHEVRON_RIGHT, Modifier.size(18.dp), ChatColors.Secondary)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = ChatColors.Background,
        thickness = 2.dp,
        modifier = Modifier.padding(start = 60.dp),
    )
}

@Composable
private fun CircleIconButton(
    label: String,
    glyph: IconGlyph,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = ChatColors.Elevated,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(ChatDimensions.TouchTarget)
            .background(containerColor, CircleShape)
            .semantics { contentDescription = label },
    ) {
        AppIcon(
            glyph,
            Modifier.size(24.dp),
            if (enabled) ChatColors.Primary else ChatColors.Secondary.copy(alpha = 0.45f),
        )
    }
}

private enum class IconGlyph {
    MENU,
    PLUS,
    NEW_CHAT,
    SEARCH,
    USER,
    BACK,
    CHEVRON_DOWN,
    CHEVRON_RIGHT,
    CHECK,
    SEND,
    STOP,
    CLOSE,
    SETTINGS,
    SPEED,
    INTELLIGENCE,
    FOLDER,
    STORAGE,
    SHIELD,
    LINK,
    LOCK,
    INFO,
    LOGOUT,
    PIN,
    EDIT,
    TRASH,
}

@Composable
private fun AppIcon(
    glyph: IconGlyph,
    modifier: Modifier = Modifier,
    tint: Color = ChatColors.Primary,
) {
    Canvas(modifier) {
        val stroke = (size.minDimension * 0.09f).coerceAtLeast(1.5f)
        val line: (Offset, Offset) -> Unit = { start, end ->
            drawLine(tint, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
        }
        when (glyph) {
            IconGlyph.MENU -> {
                line(Offset(size.width * .22f, size.height * .36f), Offset(size.width * .78f, size.height * .36f))
                line(Offset(size.width * .22f, size.height * .64f), Offset(size.width * .60f, size.height * .64f))
            }

            IconGlyph.PLUS -> {
                line(Offset(size.width * .5f, size.height * .18f), Offset(size.width * .5f, size.height * .82f))
                line(Offset(size.width * .18f, size.height * .5f), Offset(size.width * .82f, size.height * .5f))
            }

            IconGlyph.NEW_CHAT -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * .17f, size.height * .24f),
                    size = Size(size.width * .56f, size.height * .58f),
                    cornerRadius = CornerRadius(size.width * .08f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .48f, size.height * .17f), Offset(size.width * .83f, size.height * .17f))
                line(Offset(size.width * .83f, size.height * .17f), Offset(size.width * .83f, size.height * .52f))
                line(Offset(size.width * .48f, size.height * .52f), Offset(size.width * .83f, size.height * .17f))
            }

            IconGlyph.SEARCH -> {
                drawCircle(
                    color = tint,
                    radius = size.minDimension * .27f,
                    center = Offset(size.width * .43f, size.height * .42f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .62f, size.height * .62f), Offset(size.width * .82f, size.height * .82f))
            }

            IconGlyph.USER -> {
                drawCircle(
                    tint,
                    size.minDimension * .17f,
                    Offset(size.width * .5f, size.height * .34f),
                    style = Stroke(stroke),
                )
                drawArc(
                    color = tint,
                    startAngle = 195f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(size.width * .2f, size.height * .43f),
                    size = Size(size.width * .6f, size.height * .48f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            IconGlyph.BACK -> {
                line(Offset(size.width * .72f, size.height * .18f), Offset(size.width * .30f, size.height * .5f))
                line(Offset(size.width * .30f, size.height * .5f), Offset(size.width * .72f, size.height * .82f))
            }

            IconGlyph.CHEVRON_DOWN -> {
                line(Offset(size.width * .22f, size.height * .38f), Offset(size.width * .5f, size.height * .66f))
                line(Offset(size.width * .5f, size.height * .66f), Offset(size.width * .78f, size.height * .38f))
            }

            IconGlyph.CHEVRON_RIGHT -> {
                line(Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f))
                line(Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f))
            }

            IconGlyph.CHECK -> {
                line(Offset(size.width * .18f, size.height * .52f), Offset(size.width * .42f, size.height * .74f))
                line(Offset(size.width * .42f, size.height * .74f), Offset(size.width * .84f, size.height * .24f))
            }

            IconGlyph.SEND -> {
                line(Offset(center.x, size.height * .78f), Offset(center.x, size.height * .22f))
                line(Offset(size.width * .27f, size.height * .45f), Offset(center.x, size.height * .22f))
                line(Offset(center.x, size.height * .22f), Offset(size.width * .73f, size.height * .45f))
            }

            IconGlyph.STOP -> drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * .28f, size.height * .28f),
                size = Size(size.width * .44f, size.height * .44f),
                cornerRadius = CornerRadius(size.width * .06f),
            )

            IconGlyph.CLOSE -> {
                line(Offset(size.width * .24f, size.height * .24f), Offset(size.width * .76f, size.height * .76f))
                line(Offset(size.width * .76f, size.height * .24f), Offset(size.width * .24f, size.height * .76f))
            }

            IconGlyph.SETTINGS -> {
                drawCircle(tint, size.minDimension * .29f, center, style = Stroke(stroke))
                drawCircle(tint, size.minDimension * .08f, center, style = Stroke(stroke))
                repeat(4) { index ->
                    val horizontal = index % 2 == 0
                    val direction = if (index < 2) -1f else 1f
                    if (horizontal) {
                        line(
                            Offset(center.x + direction * size.width * .29f, center.y),
                            Offset(center.x + direction * size.width * .42f, center.y),
                        )
                    } else {
                        line(
                            Offset(center.x, center.y + direction * size.height * .29f),
                            Offset(center.x, center.y + direction * size.height * .42f),
                        )
                    }
                }
            }

            IconGlyph.SPEED -> {
                drawArc(
                    tint,
                    200f,
                    140f,
                    false,
                    Offset(size.width * .16f, size.height * .20f),
                    Size(size.width * .68f, size.height * .68f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                line(center, Offset(size.width * .70f, size.height * .36f))
            }

            IconGlyph.INTELLIGENCE -> {
                for (index in 0..4) {
                    val x = size.width * (.22f + index * .14f)
                    val half = size.height * if (index % 2 == 0) .25f else .15f
                    line(Offset(x, center.y - half), Offset(x, center.y + half))
                }
            }

            IconGlyph.FOLDER -> {
                val path = Path().apply {
                    moveTo(size.width * .12f, size.height * .30f)
                    lineTo(size.width * .40f, size.height * .30f)
                    lineTo(size.width * .49f, size.height * .40f)
                    lineTo(size.width * .88f, size.height * .40f)
                    lineTo(size.width * .82f, size.height * .78f)
                    lineTo(size.width * .18f, size.height * .78f)
                    close()
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }

            IconGlyph.STORAGE -> {
                drawOval(
                    color = tint,
                    topLeft = Offset(size.width * .18f, size.height * .15f),
                    size = Size(size.width * .64f, size.height * .25f),
                    style = Stroke(stroke),
                )
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(size.width * .18f, size.height * .49f),
                    size = Size(size.width * .64f, size.height * .25f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                line(Offset(size.width * .18f, size.height * .28f), Offset(size.width * .18f, size.height * .62f))
                line(Offset(size.width * .82f, size.height * .28f), Offset(size.width * .82f, size.height * .62f))
            }

            IconGlyph.SHIELD -> {
                val path = Path().apply {
                    moveTo(center.x, size.height * .12f)
                    lineTo(size.width * .82f, size.height * .28f)
                    lineTo(size.width * .75f, size.height * .68f)
                    quadraticTo(center.x, size.height * .9f, size.width * .25f, size.height * .68f)
                    lineTo(size.width * .18f, size.height * .28f)
                    close()
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }

            IconGlyph.LINK -> {
                drawArc(
                    color = tint,
                    startAngle = 120f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(size.width * .10f, size.height * .18f),
                    size = Size(size.width * .48f, size.height * .48f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = tint,
                    startAngle = -60f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(size.width * .42f, size.height * .34f),
                    size = Size(size.width * .48f, size.height * .48f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                line(Offset(size.width * .39f, size.height * .58f), Offset(size.width * .61f, size.height * .42f))
            }

            IconGlyph.LOCK -> {
                drawRoundRect(
                    tint,
                    Offset(size.width * .2f, size.height * .42f),
                    Size(size.width * .6f, size.height * .42f),
                    CornerRadius(size.width * .08f),
                    style = Stroke(stroke),
                )
                drawArc(
                    tint,
                    180f,
                    180f,
                    false,
                    Offset(size.width * .31f, size.height * .12f),
                    Size(size.width * .38f, size.height * .48f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            IconGlyph.INFO -> {
                drawCircle(tint, size.minDimension * .38f, center, style = Stroke(stroke))
                drawCircle(tint, stroke * .55f, Offset(center.x, size.height * .30f))
                line(Offset(center.x, size.height * .45f), Offset(center.x, size.height * .70f))
            }

            IconGlyph.LOGOUT -> {
                drawArc(
                    tint,
                    90f,
                    180f,
                    false,
                    Offset(size.width * .12f, size.height * .18f),
                    Size(size.width * .52f, size.height * .64f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .42f, center.y), Offset(size.width * .88f, center.y))
                line(Offset(size.width * .70f, size.height * .33f), Offset(size.width * .88f, center.y))
                line(Offset(size.width * .88f, center.y), Offset(size.width * .70f, size.height * .67f))
            }

            IconGlyph.PIN -> {
                line(Offset(size.width * .30f, size.height * .20f), Offset(size.width * .70f, size.height * .20f))
                line(Offset(size.width * .38f, size.height * .20f), Offset(size.width * .42f, size.height * .50f))
                line(Offset(size.width * .62f, size.height * .20f), Offset(size.width * .58f, size.height * .50f))
                line(Offset(size.width * .28f, size.height * .50f), Offset(size.width * .72f, size.height * .50f))
                line(Offset(center.x, size.height * .50f), Offset(center.x, size.height * .86f))
            }

            IconGlyph.EDIT -> {
                line(Offset(size.width * .20f, size.height * .72f), Offset(size.width * .68f, size.height * .24f))
                line(Offset(size.width * .31f, size.height * .83f), Offset(size.width * .79f, size.height * .35f))
                line(Offset(size.width * .68f, size.height * .24f), Offset(size.width * .79f, size.height * .35f))
                line(Offset(size.width * .20f, size.height * .72f), Offset(size.width * .17f, size.height * .86f))
                line(Offset(size.width * .17f, size.height * .86f), Offset(size.width * .31f, size.height * .83f))
            }

            IconGlyph.TRASH -> {
                drawRoundRect(
                    tint,
                    Offset(size.width * .25f, size.height * .30f),
                    Size(size.width * .5f, size.height * .55f),
                    CornerRadius(size.width * .04f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .18f, size.height * .25f), Offset(size.width * .82f, size.height * .25f))
                line(Offset(size.width * .40f, size.height * .14f), Offset(size.width * .60f, size.height * .14f))
            }

        }
    }
}
