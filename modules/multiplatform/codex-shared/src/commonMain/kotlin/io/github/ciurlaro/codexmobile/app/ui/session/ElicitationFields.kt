package io.github.ciurlaro.codexmobile.app.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.agent.AgentFormField
import io.github.ciurlaro.codexmobile.agent.AgentFormFieldType
import io.github.ciurlaro.codexmobile.agent.AgentFormValue

@Composable
internal fun ElicitationField(
    field: AgentFormField,
    answers: SnapshotStateMap<String, AgentFormValue>,
    accent: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            field.title.uppercase(),
            color = accent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            style = MaterialTheme.typography.labelSmall,
        )
        field.description?.let {
            Text(
                it,
                color = ChatColors.Primary,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    when (field.type) {
        AgentFormFieldType.STRING,
        AgentFormFieldType.NUMBER,
        AgentFormFieldType.INTEGER,
        -> ElicitationTextField(field, answers, accent)
        AgentFormFieldType.BOOLEAN -> {
            val checked = (answers[field.name] as? AgentFormValue.BooleanValue)?.value == true
            ChoiceCard(
                title = if (checked) "Yes" else "No",
                selected = checked,
                multiple = true,
                accent = accent,
                onClick = { answers[field.name] = AgentFormValue.BooleanValue(!checked) },
            )
        }
        AgentFormFieldType.SINGLE_SELECT -> SingleSelectField(field, answers, accent)
        AgentFormFieldType.MULTI_SELECT -> MultiSelectField(field, answers, accent)
    }
}

@Composable
private fun ElicitationTextField(
    field: AgentFormField,
    answers: SnapshotStateMap<String, AgentFormValue>,
    accent: Color,
) {
    OutlinedTextField(
        value = when (val value = answers[field.name]) {
            is AgentFormValue.Text -> value.value
            is AgentFormValue.Number -> value.value.toString()
            else -> ""
        },
        onValueChange = { value ->
            answers[field.name] = when (field.type) {
                AgentFormFieldType.STRING -> AgentFormValue.Text(value)
                else -> value.toDoubleOrNull()?.let(AgentFormValue::Number) ?: AgentFormValue.Text(value)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = elicitationFieldColors(accent),
        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun SingleSelectField(
    field: AgentFormField,
    answers: SnapshotStateMap<String, AgentFormValue>,
    accent: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        field.options.forEach { option ->
            ChoiceCard(
                title = option.title,
                description = option.description,
                selected = (answers[field.name] as? AgentFormValue.Text)?.value == option.value,
                accent = accent,
                onClick = { answers[field.name] = AgentFormValue.Text(option.value) },
            )
        }
    }
    if (field.allowOther) {
        val selected = (answers[field.name] as? AgentFormValue.Text)?.value.orEmpty()
        val optionValues = field.options.map { it.value }
        OutlinedTextField(
            value = selected.takeUnless { it in optionValues }.orEmpty(),
            onValueChange = { answers[field.name] = AgentFormValue.Text(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Other") },
            singleLine = true,
            colors = elicitationFieldColors(accent),
            visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
private fun MultiSelectField(
    field: AgentFormField,
    answers: SnapshotStateMap<String, AgentFormValue>,
    accent: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        field.options.forEach { option ->
            val selected = (answers[field.name] as? AgentFormValue.TextList)?.value.orEmpty()
            ChoiceCard(
                title = option.title,
                description = option.description,
                selected = option.value in selected,
                multiple = true,
                accent = accent,
                onClick = {
                    answers[field.name] = AgentFormValue.TextList(
                        if (option.value in selected) selected - option.value else selected + option.value,
                    )
                },
            )
        }
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    description: String? = null,
    multiple: Boolean = false,
) {
    val recommendation = " (Recommended)"
    val recommended = title.endsWith(recommendation)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics {
            this.selected = selected
            role = if (multiple) Role.Checkbox else Role.RadioButton
        },
        color = if (selected) lerp(ChatColors.Elevated, accent, 0.14f) else ChatColors.Elevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent else ChatColors.Border),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 10.dp, top = 13.dp, bottom = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    title.removeSuffix(recommendation),
                    color = ChatColors.Primary,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (recommended) {
                    Surface(color = accent.copy(alpha = 0.16f), shape = RoundedCornerShape(20.dp)) {
                        Text(
                            "Recommended",
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = accent,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                description?.let {
                    Text(it, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (multiple) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = Color.Black),
                )
            } else {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = accent),
                )
            }
        }
    }
}

@Composable
private fun elicitationFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    focusedLabelColor = accent,
    cursorColor = accent,
)
