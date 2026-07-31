package io.github.ciurlaro.codexmobile.app.ui.chat

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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import kotlinx.coroutines.flow.drop

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

internal val planCommandVisualTransformation = VisualTransformation { source ->
    val match = Regex("^/plan(?:\\s+|${'$'})", RegexOption.IGNORE_CASE).find(source.text)
    if (match == null) {
        TransformedText(source, OffsetMapping.Identity)
    } else {
        val prefixLength = match.value.length
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = ChatColors.PlanAccent, fontWeight = FontWeight.Bold)) {
                append(source.subSequence(0, prefixLength))
            }
            append(source.subSequence(prefixLength, source.length))
        }
        TransformedText(text, OffsetMapping.Identity)
    }
}

@Composable
internal fun ChatScreen(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
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
                        onEvent(AppUiEvent.CloseHistory)
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
            ChatContent(state, onEvent)
        }
    }
}

@Composable
private fun ChatContent(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
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
        ChatComposer(state, onEvent)
    }
}

@Composable
private fun ChatTopBar(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
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
        ) { onEvent(AppUiEvent.OpenHistory) }
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
            ) { onEvent(AppUiEvent.StartNewChat) }
        }
    }
}
