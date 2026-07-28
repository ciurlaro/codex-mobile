package io.github.ciurlaro.codexmobile.app.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ciurlaro.codexmobile.app.presentation.validation.isValidElicitationAnswer
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentFormValue

@Composable
internal fun ElicitationDialog(
    elicitation: AgentElicitation,
    onResponse: (AgentElicitationResponse) -> Unit,
) {
    val form = elicitation.form
    val isPlan = elicitation.serverName.equals("Plan", ignoreCase = true)
    val accent = if (isPlan) ChatColors.PlanAccent else ChatColors.Accent
    val answers = remember(elicitation.requestId) {
        mutableStateMapOf<String, AgentFormValue>().apply {
            form.orEmpty().forEach { field -> field.defaultValue?.let { put(field.name, it) } }
        }
    }
    val fields = form.orEmpty()
    var page by remember(elicitation.requestId) { mutableIntStateOf(0) }
    val currentField = fields.getOrNull(page)
    val nextPage = nextElicitationPage(page, fields.size)
    val canAdvance = currentField?.let { isValidElicitationAnswer(it, answers[it.name]) }
        ?: fields.all { field -> isValidElicitationAnswer(field, answers[field.name]) }

    Dialog(
        onDismissRequest = { onResponse(AgentElicitationResponse(AgentElicitationAction.CANCEL)) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp).heightIn(max = maxHeight),
                color = if (isPlan) lerp(ChatColors.CodeSurface, accent, 0.06f) else ChatColors.CodeSurface,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, if (isPlan) accent.copy(alpha = 0.28f) else ChatColors.Border),
                shadowElevation = 12.dp,
            ) {
                Column {
                    ElicitationHeader(elicitation.serverName, isPlan, accent)
                    key(page) {
                        Column(
                            Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = 24.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(elicitation.message, color = ChatColors.Secondary)
                            if (elicitation.url != null) {
                                Text(
                                    "Complete the secure authorization window, or cancel here.",
                                    color = ChatColors.Secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (fields.size > 1) ElicitationProgress(page, fields.size, accent)
                            if (isPlan) HorizontalDivider(color = accent.copy(alpha = 0.25f))
                            currentField?.let { ElicitationField(it, answers, accent) }
                        }
                    }
                    HorizontalDivider(color = ChatColors.Border.copy(alpha = 0.7f))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onResponse(AgentElicitationResponse(AgentElicitationAction.CANCEL)) },
                        ) { Text("Cancel", color = ChatColors.Secondary) }
                        if (page > 0) TextButton(onClick = { page -= 1 }) { Text("Back", color = accent) }
                        if (form != null || elicitation.url != null) {
                            Button(
                                enabled = canAdvance,
                                onClick = {
                                    if (nextPage == null) {
                                        onResponse(
                                            AgentElicitationResponse(
                                                AgentElicitationAction.ACCEPT,
                                                answers.toMap(),
                                            ),
                                        )
                                    } else {
                                        page = nextPage
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accent,
                                    contentColor = Color.Black,
                                ),
                            ) { Text(if (nextPage == null) "Continue" else "Next") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElicitationHeader(serverName: String, isPlan: Boolean, accent: Color) {
    Row(
        Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPlan) AppIcon(IconGlyph.CHECKLIST, Modifier.size(24.dp), accent)
        Text(
            serverName,
            color = if (isPlan) accent else ChatColors.Primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun ElicitationProgress(page: Int, pageCount: Int, accent: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Question ${page + 1} of $pageCount",
            color = accent,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(pageCount) { index ->
                Box(
                    Modifier
                        .size(if (index == page) 9.dp else 7.dp)
                        .background(if (index <= page) accent else ChatColors.Border, CircleShape),
                )
            }
        }
    }
}

internal fun nextElicitationPage(page: Int, pageCount: Int): Int? =
    (page + 1).takeIf { it < pageCount }
