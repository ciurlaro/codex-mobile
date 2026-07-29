package io.github.ciurlaro.codexmobile.app.ui.session

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import io.github.ciurlaro.codexmobile.app.security.display.toApprovalDisplayText
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentEvent

@Composable
internal fun CodexApprovalDialog(
    approval: AgentEvent.ApprovalRequested,
    onDecision: (AgentApprovalDecision) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDecision(AgentApprovalDecision.DECLINE) },
        title = { Text(approval.title.toApprovalDisplayText()) },
        text = {
            Text(
                approval.details.toApprovalDisplayText(),
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = {
            Button(onClick = { onDecision(AgentApprovalDecision.ACCEPT) }) { Text("Allow") }
        },
        dismissButton = {
            Button(onClick = { onDecision(AgentApprovalDecision.DECLINE) }) { Text("Deny") }
        },
    )
}
