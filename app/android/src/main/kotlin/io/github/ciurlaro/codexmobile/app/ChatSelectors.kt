package io.github.ciurlaro.codexmobile.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentModel

@Composable
internal fun SelectorOverlay(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
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
                onClick = { onEvent(ChatUiEvent.DismissSelector) },
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
            when (state.activeSelector) {
                ChatSelector.EFFORT -> EffortSelector(state, onEvent)
                ChatSelector.MODEL -> ModelSelector(state, onEvent)
                ChatSelector.TAGS -> TagSelector(state, onEvent)
                ChatSelector.SPEED -> SpeedSelector(state, onEvent)
                ChatSelector.APPROVAL -> ApprovalSelector(state, onEvent)
                null -> Unit
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
            onClick = { onEvent(ChatUiEvent.OpenSelector(ChatSelector.MODEL)) },
        )
        HorizontalDivider(color = ChatColors.Border, modifier = Modifier.padding(horizontal = 20.dp))
        SelectorRow(
            title = "Speed",
            subtitle = model?.serviceTiers?.firstOrNull { it.id == state.selectedSpeedTier }?.name
                ?: "Default",
            selected = false,
            trailing = IconGlyph.CHEVRON_RIGHT,
            onClick = { onEvent(ChatUiEvent.OpenSelector(ChatSelector.SPEED)) },
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
    val model = state.selectedModelOrNull()
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
            selected = state.selectedSpeedTier == null,
            onClick = { onEvent(ChatUiEvent.SelectSpeed(null)) },
        )
        model?.serviceTiers.orEmpty().forEach { tier ->
            SelectorRow(
                title = tier.name,
                subtitle = tier.description.takeIf(String::isNotBlank),
                selected = tier.id == state.selectedSpeedTier,
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
