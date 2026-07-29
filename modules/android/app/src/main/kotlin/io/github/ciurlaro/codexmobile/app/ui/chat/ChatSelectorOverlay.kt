package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocationKind
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions

@Composable
internal fun ChatSelectorOverlay(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
    aboveComposer: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.activeSelector) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onEvent(AppUiEvent.DismissSelector) },
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
                .then(if (aboveComposer) Modifier.imePadding() else Modifier)
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
            when (state.activeSelector) {
                ChatSelector.EFFORT -> EffortSelector(state, onEvent)
                ChatSelector.MODEL -> ModelSelector(state, onEvent)
                ChatSelector.TAGS -> TagSelector(state, onEvent)
                ChatSelector.SKILLS -> PromptInvocationSelector(state, PromptInvocationKind.SKILL, onEvent)
                ChatSelector.PLUGINS -> PromptInvocationSelector(state, PromptInvocationKind.PLUGIN, onEvent)
                ChatSelector.SPEED -> SpeedSelector(state, onEvent)
                ChatSelector.APPROVAL -> ApprovalSelector(state, onEvent)
                null -> Unit
            }
        }
    }
}
