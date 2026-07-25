package io.github.ciurlaro.codexmobile.app.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.validation.isValidElicitationAnswer
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentFormFieldType
import io.github.ciurlaro.codexmobile.core.AgentFormValue

@Composable
internal fun ElicitationDialog(
    elicitation: AgentElicitation,
    onResponse: (AgentElicitationResponse) -> Unit,
) {
    val form = elicitation.form
    val answers = remember(elicitation.requestId) {
        mutableStateMapOf<String, AgentFormValue>().apply {
            form.orEmpty().forEach { field -> field.defaultValue?.let { put(field.name, it) } }
        }
    }
    AlertDialog(
        onDismissRequest = { onResponse(AgentElicitationResponse(AgentElicitationAction.CANCEL)) },
        title = { Text(elicitation.serverName) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(elicitation.message)
                if (elicitation.url != null) Text("Complete the secure authorization window, or cancel here.")
                form.orEmpty().forEach { field ->
                    Text(field.title)
                    field.description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    when (field.type) {
                        AgentFormFieldType.STRING,
                        AgentFormFieldType.NUMBER,
                        AgentFormFieldType.INTEGER,
                        -> OutlinedTextField(
                            value = when (val value = answers[field.name]) {
                                is AgentFormValue.Text -> value.value
                                is AgentFormValue.Number -> value.value.toString()
                                else -> ""
                            },
                            onValueChange = { value ->
                                answers[field.name] = when (field.type) {
                                    AgentFormFieldType.STRING -> AgentFormValue.Text(value)
                                    else -> value.toDoubleOrNull()?.let(AgentFormValue::Number)
                                        ?: AgentFormValue.Text(value)
                                }
                            },
                            singleLine = true,
                        )
                        AgentFormFieldType.BOOLEAN -> androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            val checked = (answers[field.name] as? AgentFormValue.BooleanValue)?.value == true
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { answers[field.name] = AgentFormValue.BooleanValue(it) },
                            )
                            Text(if (checked) "Yes" else "No")
                        }
                        AgentFormFieldType.SINGLE_SELECT -> field.options.forEach { option ->
                            val selected = (answers[field.name] as? AgentFormValue.Text)?.value == option.value
                            Text(
                                (if (selected) "✓ " else "") + option.title,
                                Modifier.fillMaxWidth().clickable {
                                    answers[field.name] = AgentFormValue.Text(option.value)
                                }.padding(vertical = 8.dp),
                            )
                        }
                        AgentFormFieldType.MULTI_SELECT -> field.options.forEach { option ->
                            val selected = (answers[field.name] as? AgentFormValue.TextList)?.value.orEmpty()
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = option.value in selected,
                                    onCheckedChange = { checked ->
                                        answers[field.name] = AgentFormValue.TextList(
                                            if (checked) selected + option.value else selected - option.value,
                                        )
                                    },
                                )
                                Text(option.title)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (form != null) {
                Button(
                    enabled = form.all { field -> isValidElicitationAnswer(field, answers[field.name]) },
                    onClick = {
                        onResponse(AgentElicitationResponse(AgentElicitationAction.ACCEPT, answers.toMap()))
                    },
                ) { Text("Continue") }
            }
        },
        dismissButton = {
            Button(onClick = {
                onResponse(AgentElicitationResponse(AgentElicitationAction.CANCEL))
            }) { Text("Cancel") }
        },
    )
}
