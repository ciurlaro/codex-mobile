package io.github.ciurlaro.codexmobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
            var showEraseConfirmation by rememberSaveable { mutableStateOf(false) }
            var showPrivacyDisclosure by rememberSaveable { mutableStateOf(false) }
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
                        Text(
                            "Codex Mobile",
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            state.status,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        state.diagnosticCode?.let { Text("Diagnostic reference: $it") }
                        if (state.backgroundActive && !state.backgroundNotificationVisible) {
                            Text(
                                "Background work is active, but notifications are disabled. " +
                                    "Android still shows it under Active apps.",
                            )
                        }
                        if (state.backgroundActive) {
                            Button(
                                onClick = viewModel::stopBackgroundWork,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Stop background work") }
                        }

                        Text(
                            "Privacy and data",
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(
                            onClick = viewModel::signOut,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("Sign out of ChatGPT")
                        }
                        Button(
                            onClick = { showPrivacyDisclosure = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Privacy details") }
                        Button(
                            onClick = { showEraseConfirmation = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Erase Codex Mobile data") }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { scopePicker.launch(null) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(
                                    if (state.scopeSelected) "Change document folder"
                                    else "Select document folder",
                                )
                            }
                            if (state.scopeSelected) {
                                Button(
                                    onClick = viewModel::revokeScope,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text("Revoke access") }
                            }
                        }
                        Button(
                            onClick = { mutationScopePicker.launch(null) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
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
                            Button(
                                onClick = { viewModel.acknowledgeMutation(notice.recordId) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
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
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Sign in with ChatGPT") }
                        }

                        state.userCode?.let { code ->
                            Text("One-time code")
                            Text(code, fontFamily = FontFamily.Monospace)
                        }
                        state.verificationUrl?.let { url ->
                            val signInUri = remember(url) { url.toOfficialSignInUri() }
                            Text("Visit this address in a browser; Codex Mobile will continue in the background:")
                            Text(url)
                            Button(
                                onClick = {
                                    runCatching {
                                        startActivity(Intent(Intent.ACTION_VIEW, signInUri))
                                    }.onFailure { viewModel.browserUnavailable() }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                enabled = signInUri != null,
                            ) { Text("Open sign-in page") }
                            Button(
                                onClick = viewModel::cancelAuthentication,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Cancel sign-in") }
                        }

                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.sessionId != null && !state.turnActive,
                            label = { Text("Prompt") },
                            minLines = 3,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    viewModel.submit(prompt)
                                },
                                enabled = state.sessionId != null && !state.turnActive,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Send")
                            }
                            Button(
                                onClick = viewModel::cancel,
                                enabled = state.turnActive,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Cancel")
                            }
                        }

                        if (state.streamedText.isNotEmpty()) {
                            Text("Response", style = MaterialTheme.typography.titleMedium)
                            Text(state.streamedText)
                        }

                    }
                }
                val preview = state.approvalPreview
                if (preview != null) {
                    MutationApprovalDialog(preview, viewModel::approveMutation, viewModel::denyMutation)
                } else if (showEraseConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showEraseConfirmation = false },
                        title = { Text("Erase all Codex Mobile data?") },
                        text = {
                            Text(
                                "This signs you out and permanently erases app credentials, " +
                                    "conversation history, settings, document access, and recovery records. " +
                                    "Files in your selected folders are not deleted.",
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showEraseConfirmation = false
                                    viewModel.eraseAppData()
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Erase app data") }
                        },
                        dismissButton = {
                            Button(
                                onClick = { showEraseConfirmation = false },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Keep data") }
                        },
                    )
                } else if (showPrivacyDisclosure) {
                    AlertDialog(
                        onDismissRequest = { showPrivacyDisclosure = false },
                        title = { Text("Privacy details") },
                        text = {
                            Text(
                                "Prompts, Codex responses, ChatGPT credentials, conversation history, " +
                                    "selected-folder access, and mutation recovery records stay in app-private " +
                                    "storage and are excluded from backup. Prompts and Android tool results are " +
                                    "sent to OpenAI. Codex Mobile does not put prompt or document content in its " +
                                    "logs. Erasing app data removes this local data and access without deleting " +
                                    "your documents.",
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = { showPrivacyDisclosure = false },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("Close") }
                        },
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

internal fun String.toOfficialSignInUri(): Uri? = runCatching { Uri.parse(this) }
    .getOrNull()
    ?.takeIf { uri ->
        val host = uri.host?.lowercase()
        uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && host != null &&
            (host == "openai.com" || host.endsWith(".openai.com") ||
                host == "chatgpt.com" || host.endsWith(".chatgpt.com"))
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
            Button(
                onClick = { decide(onApprove) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !decided,
            ) { Text("Approve once") }
        },
        dismissButton = {
            Button(
                onClick = { decide(onDeny) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !decided,
            ) { Text("Deny") }
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
