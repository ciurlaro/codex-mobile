package io.github.ciurlaro.codexmobile.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import kotlinx.coroutines.flow.drop

private object ChatColors {
    val Background = Color.Black
    val Drawer = Color(0xFF101010)
    val Elevated = Color(0xFF202020)
    val ElevatedStrong = Color(0xFF2A2A2A)
    val UserBubble = Color(0xFF3D3D3D)
    val Border = Color(0xFF3A3A3A)
    val Primary = Color(0xFFF5F5F5)
    val Secondary = Color(0xFFA5A5A5)
    val Accent = Color(0xFF3F83F8)
    val Danger = Color(0xFFFF7A83)
    val Scrim = Color(0x99000000)
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
        LaunchedEffect(state.drawerOpen) {
            if (state.drawerOpen) drawerState.open() else drawerState.close()
        }
        LaunchedEffect(drawerState) {
            snapshotFlow { drawerState.currentValue }
                .drop(1)
                .collect { value ->
                    if (value == DrawerValue.Closed && shouldBeOpen) {
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
    val query = state.historySearch.trim()
    val visibleConversations = remember(state.conversations, query) {
        if (query.isEmpty()) state.conversations
        else state.conversations.filter { it.title.contains(query, ignoreCase = true) }
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(
                items = visibleConversations,
                key = { it.sessionId.value },
            ) { conversation ->
                val selected = conversation.sessionId == state.sessionId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) ChatColors.ElevatedStrong else Color.Transparent)
                        .clickable { onEvent(ChatUiEvent.SelectConversation(conversation.sessionId)) }
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            if (selected) stateDescription = "Current conversation"
                        }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = conversation.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) ChatColors.Primary else ChatColors.Secondary,
                        style = MaterialTheme.typography.bodyLarge,
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
            .statusBarsPadding(),
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
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(ChatDimensions.ControlCorner))
                .clickable { onEvent(ChatUiEvent.ShowEffort) }
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Chat",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.width(6.dp))
            AppIcon(IconGlyph.CHEVRON_DOWN, Modifier.size(17.dp), ChatColors.Secondary)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
            message.text.isNotEmpty() -> Text(
                text = message.text,
                color = ChatColors.Primary,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
            )

            message.streaming -> ThinkingMessage()
        }
    }
}

@Composable
private fun ThinkingMessage() {
    Text(
        text = "Thinking",
        color = ChatColors.Secondary,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { contentDescription = "Codex is thinking" },
    )
}

@Composable
private fun Composer(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val expanded = focused || state.draft.contains('\n') || state.selectedCapabilities.isNotEmpty()
    val canSend = state.authenticated &&
        (state.draft.isNotBlank() || state.selectedCapabilities.isNotEmpty())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = ChatDimensions.ScreenPadding, vertical = 10.dp)
            .background(ChatColors.Elevated, RoundedCornerShape(ChatDimensions.ComposerCorner))
            .border(
                BorderStroke(1.dp, ChatColors.Border),
                RoundedCornerShape(ChatDimensions.ComposerCorner),
            )
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
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
                .semantics { contentDescription = "Message" },
            enabled = state.authenticated,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ChatColors.Primary),
            cursorBrush = SolidColor(ChatColors.Accent),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.TopStart) {
                    if (state.draft.isEmpty()) {
                        Text(
                            if (state.messages.isEmpty()) "Ask Codex" else "Reply to Codex",
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
                containerColor = Color.Transparent,
            ) { onEvent(ChatUiEvent.ShowTags) }
            SelectionPill(
                state = state,
                modifier = Modifier.weight(1f),
                onClick = { onEvent(ChatUiEvent.ShowEffort) },
            )
            CircleIconButton(
                label = if (state.turnActive) "Stop response" else "Send message",
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
                .then(if (aboveComposer) Modifier.imePadding().navigationBarsPadding() else Modifier)
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
    Column(Modifier.padding(vertical = 12.dp)) {
        SelectorRow(
            title = "Model",
            subtitle = model?.displayName ?: state.selectedModel ?: "Unavailable",
            selected = false,
            trailing = IconGlyph.CHEVRON_RIGHT,
            onClick = { onEvent(ChatUiEvent.ShowModels) },
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
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(
            "Model",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        LazyColumn(Modifier.heightIn(max = 430.dp)) {
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
}

@Composable
private fun TagSelector(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    val query = selectedTagQuery(state.draft).orEmpty()
    val tags = remember(query) {
        AgentCapability.entries.filter { it.displayLabel.contains(query, ignoreCase = true) }
    }
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(
            "Prompt tags",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        tags.forEach { capability ->
            val selected = capability in state.selectedCapabilities
            SelectorRow(
                title = listOfNotNull(capability.icon, capability.displayLabel).joinToString(" "),
                subtitle = if (selected) "Added" else null,
                selected = selected,
                enabled = !selected,
                onClick = { onEvent(ChatUiEvent.AddCapability(capability)) },
            )
        }
        if (tags.isEmpty()) {
            Text(
                "No tags found",
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
            item("profile") { SettingsProfile() }
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
                }
            }
            item("access-settings") {
                SettingsGroup("Android access") {
                    SettingsRow(
                        glyph = IconGlyph.FOLDER,
                        title = if (state.scopeSelected) "Change document folder" else "Select document folder",
                        subtitle = if (state.scopeSelected) "Read-only access enabled" else "No folder selected",
                        onClick = { onEvent(ChatUiEvent.SelectScope) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.SHIELD,
                        title = if (state.mutationScopeSelected) {
                            "Change disposable mutation folder"
                        } else {
                            "Select disposable mutation folder"
                        },
                        subtitle = "Use only a dedicated disposable test folder",
                        onClick = { onEvent(ChatUiEvent.SelectMutationScope) },
                    )
                    if (state.scopeSelected) {
                        SettingsDivider()
                        SettingsRow(
                            glyph = IconGlyph.CLOSE,
                            title = "Revoke document access",
                            danger = true,
                            onClick = { onEvent(ChatUiEvent.RevokeScope) },
                        )
                    }
                }
            }
            if (state.recoveryNotices.isNotEmpty()) {
                item("recovery-settings") {
                    SettingsGroup("Recovery") {
                        state.recoveryNotices.forEachIndexed { index, notice ->
                            if (index > 0) SettingsDivider()
                            SettingsRow(
                                glyph = IconGlyph.RECOVERY,
                                title = notice.state.recoveryDisplayText(),
                                subtitle = "Review and acknowledge this recorded outcome",
                                onClick = { onEvent(ChatUiEvent.AcknowledgeMutation(notice.recordId)) },
                            )
                        }
                    }
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
                                glyph = IconGlyph.CLOSE,
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
private fun SettingsProfile() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(ChatColors.ElevatedStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("CM", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("Codex Mobile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Android settings", color = ChatColors.Secondary)
    }
}

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
    INTELLIGENCE,
    FOLDER,
    SHIELD,
    LOCK,
    INFO,
    LOGOUT,
    TRASH,
    RECOVERY,
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
                val path = Path().apply {
                    moveTo(size.width * .16f, size.height * .20f)
                    lineTo(size.width * .86f, size.height * .50f)
                    lineTo(size.width * .16f, size.height * .80f)
                    close()
                }
                drawPath(path, tint)
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

            IconGlyph.RECOVERY -> {
                drawArc(
                    tint,
                    -70f,
                    285f,
                    false,
                    Offset(size.width * .16f, size.height * .16f),
                    Size(size.width * .68f, size.height * .68f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                line(Offset(size.width * .12f, size.height * .34f), Offset(size.width * .20f, size.height * .14f))
                line(Offset(size.width * .20f, size.height * .14f), Offset(size.width * .38f, size.height * .24f))
            }
        }
    }
}
