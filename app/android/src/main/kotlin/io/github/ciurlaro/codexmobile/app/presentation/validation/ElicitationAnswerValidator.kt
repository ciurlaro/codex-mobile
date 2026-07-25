package io.github.ciurlaro.codexmobile.app.presentation.validation

import io.github.ciurlaro.codexmobile.core.AgentFormField
import io.github.ciurlaro.codexmobile.core.AgentFormFieldType
import io.github.ciurlaro.codexmobile.core.AgentFormValue

internal fun isValidElicitationAnswer(field: AgentFormField, value: AgentFormValue?): Boolean {
    if (value == null) return !field.required
    val minimum = field.minimum
    val maximum = field.maximum
    return when (field.type) {
        AgentFormFieldType.STRING -> value is AgentFormValue.Text && (!field.required || value.value.isNotBlank())
        AgentFormFieldType.NUMBER -> (value as? AgentFormValue.Number)?.value?.let {
            (minimum == null || it >= minimum) && (maximum == null || it <= maximum)
        } == true
        AgentFormFieldType.INTEGER -> (value as? AgentFormValue.Number)?.value?.let {
            it % 1.0 == 0.0 && (minimum == null || it >= minimum) &&
                (maximum == null || it <= maximum)
        } == true
        AgentFormFieldType.BOOLEAN -> value is AgentFormValue.BooleanValue
        AgentFormFieldType.SINGLE_SELECT -> value is AgentFormValue.Text &&
            field.options.any { it.value == value.value }
        AgentFormFieldType.MULTI_SELECT -> value is AgentFormValue.TextList &&
            value.value.all { selected -> field.options.any { it.value == selected } }
    }
}
