package io.github.ciurlaro.codexmobile.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import kotlinx.coroutines.flow.drop

internal object ChatColors {
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

internal object ChatDimensions {
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
                when (state.screen) {
                    AppScreen.CHAT -> ChatDrawer(state, onEvent)
                    AppScreen.SETTINGS -> SettingsScreen(state, onEvent)
                    AppScreen.CAPABILITIES -> CapabilitiesScreen(state, onEvent)
                }
                if (state.activeSelector != null) {
                    SelectorOverlay(
                        state = state,
                        onEvent = onEvent,
                        aboveComposer = state.screen == AppScreen.CHAT,
                    )
                }
            }
        }
        BackHandler(
            enabled = state.activeSelector != null || state.isHistoryOpen ||
                state.screen != AppScreen.CHAT,
        ) {
            when {
                state.activeSelector != null -> onEvent(ChatUiEvent.DismissSelector)
                state.isHistoryOpen -> onEvent(ChatUiEvent.CloseHistory)
                state.screen == AppScreen.SETTINGS -> onEvent(ChatUiEvent.CloseSettings)
                state.screen == AppScreen.CAPABILITIES -> onEvent(ChatUiEvent.CloseCapabilities)
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
        val shouldBeOpen by rememberUpdatedState(state.isHistoryOpen)
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(state.isHistoryOpen) {
            if (state.isHistoryOpen) {
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
            gesturesEnabled = state.screen == AppScreen.CHAT,
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
            ) { onEvent(ChatUiEvent.StartNewChat) }
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
            state.isConversationLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(28.dp),
                color = ChatColors.Accent,
                strokeWidth = 2.dp,
            )

            !state.isAuthenticated -> SignInState(state, onEvent, Modifier.align(Alignment.Center))

            else -> {
                val listState = rememberLazyListState()
                val extraThinking = state.isTurnActive && state.messages.none(ChatMessage::isStreaming)
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
            state.statusMessage,
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        when {
            state.isAuthenticationInProgress -> CircularProgressIndicator(
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
