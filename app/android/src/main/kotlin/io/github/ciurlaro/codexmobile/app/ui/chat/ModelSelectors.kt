package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.formatting.effortLabel
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentModel

@Composable
internal fun EffortSelector(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        SelectorRow(
            title = "Model",
            subtitle = model?.displayName ?: state.selectedModel ?: "Unavailable",
            selected = false,
            trailing = IconGlyph.CHEVRON_RIGHT,
            onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.MODEL)) },
        )
        HorizontalDivider(color = ChatColors.Border, modifier = Modifier.padding(horizontal = 20.dp))
        SelectorRow(
            title = "Speed",
            subtitle = model?.serviceTiers?.firstOrNull { it.id == state.selectedSpeedTier }?.name ?: "Default",
            selected = false,
            trailing = IconGlyph.CHEVRON_RIGHT,
            onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.SPEED)) },
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
                    onClick = { onEvent(AppUiEvent.SelectEffort(effort)) },
                )
            }
        }
    }
}

@Composable
internal fun ModelSelector(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
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
                onClick = { onEvent(AppUiEvent.SelectModel(model.id)) },
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
internal fun SpeedSelector(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    val model = state.selectedModelOrNull()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
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
            onClick = { onEvent(AppUiEvent.SelectSpeed(null)) },
        )
        model?.serviceTiers.orEmpty().forEach { tier ->
            SelectorRow(
                title = tier.name,
                subtitle = tier.description.takeIf(String::isNotBlank),
                selected = tier.id == state.selectedSpeedTier,
                onClick = { onEvent(AppUiEvent.SelectSpeed(tier.id)) },
            )
        }
    }
}

@Composable
internal fun ApprovalSelector(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
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
                    AgentApprovalPreset.NEVER -> "Run without asking"
                    AgentApprovalPreset.AUTO_REVIEW -> "Let the model review risky actions (default)"
                    AgentApprovalPreset.ASK_ME -> "Ask when Codex requests permission"
                    AgentApprovalPreset.STRICT -> "Ask for commands outside the trusted set"
                },
                selected = preset == state.approvalPreset,
                onClick = { onEvent(AppUiEvent.SelectApproval(preset)) },
            )
        }
    }
}
