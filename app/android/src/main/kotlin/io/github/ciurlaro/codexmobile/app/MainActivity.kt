package io.github.ciurlaro.codexmobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        setContent {
            val state by viewModel.state.collectAsState()
            var showEraseConfirmation by rememberSaveable { mutableStateOf(false) }
            var showPrivacyDisclosure by rememberSaveable { mutableStateOf(false) }
            var openedSignInUrl by rememberSaveable { mutableStateOf<String?>(null) }
            fun openSignIn(rawUrl: String?) {
                val signInUri = rawUrl?.toOfficialSignInUri()
                if (signInUri == null) {
                    viewModel.browserUnavailable()
                } else {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, signInUri))
                    }.onFailure { viewModel.browserUnavailable() }
                }
            }
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
            val exportScopePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri == null) viewModel.scopeSelectionCancelled() else viewModel.selectExportScope(uri)
            }
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                viewModel.authenticate()
            }
            LaunchedEffect(state.signInUrl) {
                state.signInUrl?.takeIf { it != openedSignInUrl }?.let {
                    openedSignInUrl = it
                    openSignIn(it)
                }
            }
            CodexMobileTheme {
                CodexMobileApp(state) { event ->
                    when (event) {
                        ChatUiEvent.OpenHistory -> viewModel.openHistory()
                        ChatUiEvent.CloseHistory -> viewModel.closeHistory()
                        ChatUiEvent.FreshChat -> viewModel.freshChat()
                        ChatUiEvent.OpenSettings -> viewModel.openSettings()
                        ChatUiEvent.CloseSettings -> viewModel.closeSettings()
                        ChatUiEvent.ShowEffort -> viewModel.showEffortSelector()
                        ChatUiEvent.ShowModels -> viewModel.showModelSelector()
                        ChatUiEvent.ShowTags -> viewModel.showTagPicker()
                        ChatUiEvent.DismissPopup -> viewModel.dismissPopup()
                        ChatUiEvent.Send -> viewModel.sendMessage()
                        ChatUiEvent.Stop -> viewModel.cancel()
                        ChatUiEvent.Authenticate -> {
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.authenticate()
                            }
                        }
                        ChatUiEvent.CancelAuthentication -> viewModel.cancelAuthentication()
                        ChatUiEvent.OpenSignIn -> openSignIn(state.signInUrl)
                        ChatUiEvent.StopBackground -> viewModel.stopBackgroundWork()
                        ChatUiEvent.SignOut -> viewModel.signOut()
                        ChatUiEvent.ShowPrivacy -> showPrivacyDisclosure = true
                        ChatUiEvent.ShowEraseConfirmation -> showEraseConfirmation = true
                        ChatUiEvent.SelectScope -> scopePicker.launch(null)
                        ChatUiEvent.SelectMutationScope -> mutationScopePicker.launch(null)
                        ChatUiEvent.SelectExportScope -> exportScopePicker.launch(null)
                        ChatUiEvent.RevokeScope -> viewModel.revokeScope()
                        ChatUiEvent.RevokeExportScope -> viewModel.revokeExportScope()
                        is ChatUiEvent.SearchHistory -> viewModel.updateHistorySearch(event.query)
                        is ChatUiEvent.SelectConversation -> viewModel.selectConversation(event.id)
                        is ChatUiEvent.UpdateDraft -> viewModel.updateDraft(event.text)
                        is ChatUiEvent.SelectModel -> viewModel.selectModel(event.id)
                        is ChatUiEvent.SelectEffort -> viewModel.selectEffort(event.effort)
                        is ChatUiEvent.AddCapability -> viewModel.addCapability(event.capability)
                        is ChatUiEvent.RemoveCapability -> viewModel.removeCapability(event.capability)
                        is ChatUiEvent.AcknowledgeMutation -> viewModel.acknowledgeMutation(event.id)
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
                                    "conversation history, private workspace files, settings, document access, " +
                                    "and recovery records. Files exported to selected folders are not deleted.",
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
                                "Prompts, Codex responses, ChatGPT credentials, conversation history, private " +
                                    "workspace files, selected-folder access, and mutation recovery records stay " +
                                    "in app-private storage and are excluded from backup. Prompts, requested Web " +
                                    "Searches, extracted document text, OCR text, rendered PDF pages, images, and " +
                                    "other Android tool results are sent to OpenAI. Original files are not uploaded " +
                                    "as files. Bundled Google ML Kit OCR processes images on-device; Google states " +
                                    "that ML Kit may send app, device, performance, and usage metrics—but not input " +
                                    "images or recognized text—to Google. Codex Mobile does not put prompt or " +
                                    "document content in its logs. Erasing app data removes private workspace files " +
                                    "and access without deleting documents already exported to Android folders.",
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
                preview.diff?.let { diff ->
                    Text("Diff:\n${diff.toApprovalDiffDisplayText()}", fontFamily = FontFamily.Monospace)
                }
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

internal fun String.toApprovalDiffDisplayText(): String = buildString {
    var offset = 0
    while (offset < this@toApprovalDiffDisplayText.length) {
        val codePoint = this@toApprovalDiffDisplayText.codePointAt(offset)
        val type = Character.getType(codePoint)
        when {
            codePoint == '\n'.code -> append('\n')
            codePoint == '\t'.code -> append("    ")
            Character.isISOControl(codePoint) || type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() ->
                append("\\u{").append(codePoint.toString(16).uppercase()).append('}')
            else -> appendCodePoint(codePoint)
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
