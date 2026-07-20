package io.github.ciurlaro.codexmobile.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.core.ApprovalPreview
import io.github.ciurlaro.codexmobile.core.MutationState

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            var prompt by rememberSaveable { mutableStateOf("") }
            val scopePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri == null) viewModel.scopeSelectionCancelled() else viewModel.selectScope(uri)
            }
            val mutationScopePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri == null) viewModel.scopeSelectionCancelled() else viewModel.selectMutationScope(uri)
            }
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                viewModel.authenticate()
            }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Codex Mobile", style = MaterialTheme.typography.headlineMedium)
                        Text(state.status)
                        if (state.backgroundActive && !state.backgroundNotificationVisible) {
                            Text(
                                "Background work is active, but notifications are disabled. " +
                                    "Android still shows it under Active apps.",
                            )
                        }
                        if (state.backgroundActive) {
                            Button(onClick = viewModel::stopBackgroundWork) { Text("Stop background work") }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { scopePicker.launch(null) }) {
                                Text(if (state.scopeSelected) "Change document folder" else "Select document folder")
                            }
                            if (state.scopeSelected) {
                                Button(onClick = viewModel::revokeScope) { Text("Revoke access") }
                            }
                        }
                        Button(onClick = { mutationScopePicker.launch(null) }) {
                            Text(
                                if (state.mutationScopeSelected) "Change disposable mutation folder"
                                else "Select disposable mutation folder",
                            )
                        }
                        Text("Use mutation access only with a dedicated disposable test folder.")
                        if (state.mutationScopeSelected) {
                            Text("Disposable mutation access enabled")
                        } else if (state.scopeSelected) {
                            Text("Read-only document access enabled")
                        }
                        state.recoveryNotices.forEach { notice ->
                            Text(notice.state.recoveryDisplayText())
                            Button(onClick = { viewModel.acknowledgeMutation(notice.recordId) }) {
                                Text("Acknowledge recovery")
                            }
                        }

                        if (state.sessionId == null && state.verificationUrl == null) {
                            Button(
                                onClick = {
                                    if (
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        viewModel.authenticate()
                                    }
                                },
                            ) { Text("Sign in with ChatGPT") }
                        }

                        state.userCode?.let { code ->
                            Text("One-time code")
                            Text(code, fontFamily = FontFamily.Monospace)
                        }
                        state.verificationUrl?.let { url ->
                            Text("Visit this address in a browser; Codex Mobile will continue in the background:")
                            Text(url)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = viewModel::cancelAuthentication) { Text("Cancel sign-in") }
                            }
                        }

                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.sessionId != null && !state.turnActive,
                            label = { Text("Prompt") },
                            minLines = 3,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    viewModel.submit(prompt)
                                    if (prompt.isNotBlank()) prompt = ""
                                },
                                enabled = state.sessionId != null && !state.turnActive,
                            ) {
                                Text("Send")
                            }
                            Button(onClick = viewModel::cancel, enabled = state.turnActive) {
                                Text("Cancel")
                            }
                        }

                        if (state.streamedText.isNotEmpty()) {
                            Text("Response", style = MaterialTheme.typography.titleMedium)
                            Text(state.streamedText)
                        }
                    }
                }
                state.approvalPreview?.let { preview ->
                    MutationApprovalDialog(
                        preview = preview,
                        onApprove = viewModel::approveMutation,
                        onDeny = viewModel::denyMutation,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshScope()
    }

}

@androidx.compose.runtime.Composable
internal fun MutationApprovalDialog(
    preview: ApprovalPreview,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    var decided by remember(preview) { mutableStateOf(false) }
    fun decide(action: () -> Unit) {
        if (decided) return
        decided = true
        action()
    }
    AlertDialog(
        onDismissRequest = { decide(onDeny) },
        title = { Text("Approve Android change?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ApprovalField("Operation", preview.operation)
                ApprovalField("Source", preview.source)
                ApprovalField("Destination", preview.destination)
                ApprovalField("Scope", preview.scope)
                ApprovalField("Conflict behavior", preview.conflictBehavior)
            }
        },
        confirmButton = {
            Button(onClick = { decide(onApprove) }, enabled = !decided) { Text("Approve once") }
        },
        dismissButton = {
            Button(onClick = { decide(onDeny) }, enabled = !decided) { Text("Deny") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun ApprovalField(label: String, value: String) {
    Text("$label: ${value.toApprovalDisplayText()}")
}

internal fun String.toApprovalDisplayText(): String = buildString {
    var offset = 0
    while (offset < this@toApprovalDisplayText.length) {
        val codePoint = this@toApprovalDisplayText.codePointAt(offset)
        val type = Character.getType(codePoint)
        if (
            Character.isISOControl(codePoint) || type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
        ) {
            append("\\u{").append(codePoint.toString(16).uppercase()).append('}')
        } else {
            appendCodePoint(codePoint)
        }
        offset += Character.charCount(codePoint)
    }
}

internal fun MutationState.recoveryDisplayText(): String = when (this) {
    MutationState.PREPARED, MutationState.EXECUTING -> "Android mutation recovery is pending"
    MutationState.UNKNOWN -> "Android mutation outcome is unknown"
    MutationState.SUCCEEDED -> "Android mutation was recovered as succeeded"
    MutationState.FAILED -> "Android mutation was recovered as not completed"
}
