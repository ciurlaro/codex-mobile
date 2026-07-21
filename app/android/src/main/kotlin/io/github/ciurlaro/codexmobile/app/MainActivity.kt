package io.github.ciurlaro.codexmobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthPrompt
import java.io.File

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
            var showIntegrations by rememberSaveable { mutableStateOf(false) }
            var showWorkspaceBrowser by rememberSaveable { mutableStateOf(false) }
            var telegramPhone by rememberSaveable { mutableStateOf("") }
            var telegramAnswer by rememberSaveable { mutableStateOf("") }
            var workspaceBrowserPath by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingWorkspaceSelection by rememberSaveable { mutableStateOf(false) }
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
            val legacyStoragePermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { viewModel.refreshStorage() }
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
            LaunchedEffect(state.storagePermissionGranted, pendingWorkspaceSelection) {
                if (state.storagePermissionGranted && pendingWorkspaceSelection) {
                    workspaceBrowserPath = state.workspacePath ?: viewModel.workspaceRoots().firstOrNull()
                    showWorkspaceBrowser = workspaceBrowserPath != null
                    pendingWorkspaceSelection = false
                }
            }
            fun openStorageSettings(selectAfter: Boolean) {
                pendingWorkspaceSelection = selectAfter
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val appUri = Uri.parse("package:$packageName")
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri)
                    runCatching { startActivity(intent) }.onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    legacyStoragePermission.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ),
                    )
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
                        ChatUiEvent.ShowSpeed -> viewModel.showSpeedSelector()
                        ChatUiEvent.ShowApproval -> viewModel.showApprovalSelector()
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
                        ChatUiEvent.ShowIntegrations -> showIntegrations = true
                        ChatUiEvent.DisconnectTelegram -> viewModel.disconnectTelegram()
                        ChatUiEvent.CancelTelegramAuthentication -> viewModel.cancelTelegramAuthentication()
                        ChatUiEvent.ShowEraseConfirmation -> showEraseConfirmation = true
                        ChatUiEvent.SelectScope -> if (state.storagePermissionGranted) {
                            workspaceBrowserPath = state.workspacePath ?: viewModel.workspaceRoots().firstOrNull()
                            showWorkspaceBrowser = workspaceBrowserPath != null
                        } else {
                            openStorageSettings(selectAfter = true)
                        }
                        ChatUiEvent.ManageStorage -> openStorageSettings(selectAfter = false)
                        ChatUiEvent.ClearWorkspace -> viewModel.clearWorkspace()
                        is ChatUiEvent.SearchHistory -> viewModel.updateHistorySearch(event.query)
                        is ChatUiEvent.SelectConversation -> viewModel.selectConversation(event.id)
                        is ChatUiEvent.TogglePinConversation -> viewModel.togglePinConversation(event.id)
                        is ChatUiEvent.RenameConversation -> viewModel.renameConversation(event.id, event.title)
                        is ChatUiEvent.DeleteConversation -> viewModel.deleteConversation(event.id)
                        is ChatUiEvent.UpdateDraft -> viewModel.updateDraft(event.text)
                        is ChatUiEvent.SelectModel -> viewModel.selectModel(event.id)
                        is ChatUiEvent.SelectEffort -> viewModel.selectEffort(event.effort)
                        is ChatUiEvent.SelectSpeed -> viewModel.selectSpeed(event.tier)
                        is ChatUiEvent.SelectApproval -> viewModel.selectApproval(event.preset)
                        is ChatUiEvent.ConnectTelegram -> viewModel.connectTelegram(event.phoneNumber)
                        is ChatUiEvent.SubmitTelegramAuthentication -> {
                            viewModel.submitTelegramAuthentication(event.value)
                            telegramAnswer = ""
                        }
                        is ChatUiEvent.ResolveCodexApproval -> viewModel.resolveCodexApproval(
                            event.requestId,
                            event.accept,
                        )
                        is ChatUiEvent.AddCapability -> viewModel.addCapability(event.capability)
                        is ChatUiEvent.RemoveCapability -> viewModel.removeCapability(event.capability)
                    }
                }
                val codexApproval = state.codexApproval
                if (codexApproval != null) {
                    AlertDialog(
                        onDismissRequest = {
                            viewModel.resolveCodexApproval(codexApproval.requestId, false)
                        },
                        title = { Text(codexApproval.title.toApprovalDisplayText()) },
                        text = {
                            Text(
                                codexApproval.details.toApprovalDisplayText(),
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                viewModel.resolveCodexApproval(codexApproval.requestId, true)
                            }) { Text("Allow") }
                        },
                        dismissButton = {
                            Button(onClick = {
                                viewModel.resolveCodexApproval(codexApproval.requestId, false)
                            }) { Text("Deny") }
                        },
                    )
                } else if (showWorkspaceBrowser) {
                    WorkspacePickerDialog(
                        currentPath = workspaceBrowserPath,
                        directories = viewModel.workspaceDirectories(workspaceBrowserPath),
                        parent = workspaceBrowserPath?.let(viewModel::workspaceParent),
                        onOpen = { workspaceBrowserPath = it },
                        onSelect = {
                            workspaceBrowserPath?.let(viewModel::selectWorkspace)
                            showWorkspaceBrowser = false
                        },
                        onDismiss = { showWorkspaceBrowser = false },
                    )
                } else if (showIntegrations) {
                    AlertDialog(
                        onDismissRequest = {
                            if (!state.telegramConnected &&
                                (state.telegramBusy || state.telegramAuthPrompt != null)
                            ) {
                                viewModel.cancelTelegramAuthentication()
                            }
                            showIntegrations = false
                        },
                        title = { Text("Integrations") },
                        text = {
                            Column {
                                when {
                                    !state.telegramAvailable -> Text(
                                        "No integrations are available in this build.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    state.telegramConnected -> Text(
                                        buildString {
                                            append("Telegram is connected")
                                            state.telegramUsername?.let { append(" as @$it") }
                                            append(". Codex can use tgcli directly under your approval policy.")
                                        },
                                    )
                                    state.telegramAuthPrompt == TelegramAuthPrompt.CODE -> OutlinedTextField(
                                        value = telegramAnswer,
                                        onValueChange = { telegramAnswer = it },
                                        label = { Text("Code from Telegram") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                    )
                                    state.telegramAuthPrompt == TelegramAuthPrompt.PASSWORD -> OutlinedTextField(
                                        value = telegramAnswer,
                                        onValueChange = { telegramAnswer = it },
                                        label = { Text("Telegram 2FA password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                    )
                                    state.telegramBusy -> Text("Connecting to Telegram…")
                                    else -> {
                                        Text("Connect directly with Telegram. No browser or installed Telegram app is required.")
                                        Spacer(Modifier.height(12.dp))
                                        OutlinedTextField(
                                            value = telegramPhone,
                                            onValueChange = { telegramPhone = it },
                                            label = { Text("Phone number (+…)") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                            singleLine = true,
                                        )
                                    }
                                }
                                state.telegramError?.let {
                                    Spacer(Modifier.height(10.dp))
                                    Text(it, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        confirmButton = {
                            when {
                                !state.telegramAvailable -> Button(onClick = { showIntegrations = false }) {
                                    Text("Close")
                                }
                                state.telegramConnected -> Button(
                                    onClick = { viewModel.disconnectTelegram() },
                                    enabled = !state.telegramBusy,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Disconnect Telegram") }
                                state.telegramAuthPrompt != null -> Button(
                                    onClick = {
                                        viewModel.submitTelegramAuthentication(telegramAnswer)
                                        telegramAnswer = ""
                                    },
                                    enabled = !state.telegramBusy && telegramAnswer.isNotBlank(),
                                ) { Text("Continue") }
                                else -> Button(
                                    onClick = { viewModel.connectTelegram(telegramPhone) },
                                    enabled = !state.telegramBusy && telegramPhone.isNotBlank(),
                                ) {
                                    Text(if (state.telegramBusy) "Connecting…" else "Connect Telegram")
                                }
                            }
                        },
                        dismissButton = if (state.telegramAvailable) {
                            {
                                Button(onClick = {
                                    if (!state.telegramConnected &&
                                        (state.telegramBusy || state.telegramAuthPrompt != null)
                                    ) {
                                        viewModel.cancelTelegramAuthentication()
                                    }
                                    showIntegrations = false
                                }) { Text(if (state.telegramAuthPrompt != null) "Cancel" else "Done") }
                            }
                        } else null,
                    )
                } else if (showEraseConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showEraseConfirmation = false },
                        title = { Text("Erase all Codex Mobile data?") },
                        text = {
                            Text(
                                "This signs you out and permanently erases app credentials, conversation " +
                                    "history, settings, and integration data. Files in shared " +
                                    "storage are not deleted.",
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
                            PrivacyDisclosure()
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
        viewModel.refreshStorage()
    }

}

@androidx.compose.runtime.Composable
private fun WorkspacePickerDialog(
    currentPath: String?,
    directories: List<String>,
    parent: String?,
    onOpen: (String) -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose workspace") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(currentPath ?: "Shared storage", color = MaterialTheme.colorScheme.onSurfaceVariant)
                parent?.let { path ->
                    Text(
                        "↑ Parent folder",
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 12.dp),
                    )
                }
                directories.forEach { path ->
                    Text(
                        "📁 ${File(path).name.ifBlank { path }}",
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 12.dp),
                    )
                }
                if (directories.isEmpty()) {
                    Text("No subfolders", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = onSelect, enabled = currentPath != null) { Text("Use this folder") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@androidx.compose.runtime.Composable
private fun PrivacyDisclosure() {
    Column(
        Modifier
            .heightIn(max = 440.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrivacySection(
            "OpenAI",
            "Prompts, responses, shell output, file text or bytes requested by Codex, rendered pages, " +
                "images, and tool results are sent to OpenAI as part of the Codex session.",
        )
        PrivacySection(
            "Storage access",
            "The selected workspace is Codex's starting folder, not a sandbox. With all-files access, " +
                "Codex can navigate to other accessible shared-storage locations. Manage the permission in Android Settings.",
        )
        PrivacySection(
            "On-device document processing",
            "PDF, image, and Office work runs locally through the bundled mutool, tesseract, and " +
                "officecli commands. Files or extracted content are sent to OpenAI only when Codex includes " +
                "them in the session.",
        )
        PrivacySection(
            "Local storage and logs",
            "ChatGPT credentials, conversation state, settings, and integration data stay in app-private " +
                "storage excluded from Android backup. Prompt and document contents are " +
                "not written to Codex Mobile logs.",
        )
        PrivacySection(
            "Integrations",
            "An integration receives only requests explicitly tagged for it. Connected services keep their " +
                "own authorization until you disconnect them or erase app data.",
        )
    }
}

@androidx.compose.runtime.Composable
private fun PrivacySection(title: String, body: String) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
    ) {
        Text(if (expanded) "− $title" else "+ $title")
        if (expanded) Text(body, modifier = Modifier.padding(top = 6.dp))
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
