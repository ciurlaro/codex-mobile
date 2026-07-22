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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

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
            LaunchedEffect(state.hasStorageAccess, pendingWorkspaceSelection) {
                if (state.hasStorageAccess && pendingWorkspaceSelection) {
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
                        ChatUiEvent.StartNewChat -> viewModel.startNewChat()
                        ChatUiEvent.OpenSettings -> viewModel.openSettings()
                        ChatUiEvent.CloseSettings -> viewModel.closeSettings()
                        is ChatUiEvent.OpenSelector -> viewModel.openSelector(event.selector)
                        ChatUiEvent.DismissSelector -> viewModel.dismissSelector()
                        ChatUiEvent.Send -> viewModel.sendMessage()
                        ChatUiEvent.Stop -> viewModel.cancelTurn()
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
                        ChatUiEvent.SelectScope -> if (state.hasStorageAccess) {
                            workspaceBrowserPath = state.workspacePath ?: viewModel.workspaceRoots().firstOrNull()
                            showWorkspaceBrowser = workspaceBrowserPath != null
                        } else {
                            openStorageSettings(selectAfter = true)
                        }
                        ChatUiEvent.ManageStorage -> openStorageSettings(selectAfter = false)
                        ChatUiEvent.ClearWorkspace -> viewModel.clearWorkspace()
                        is ChatUiEvent.SearchHistory -> viewModel.updateHistorySearch(event.query)
                        is ChatUiEvent.OpenConversation -> viewModel.openConversation(event.id)
                        is ChatUiEvent.TogglePinConversation -> viewModel.togglePinConversation(event.id)
                        is ChatUiEvent.RenameConversation -> viewModel.renameConversation(event.id, event.title)
                        is ChatUiEvent.DeleteConversation -> viewModel.deleteConversation(event.id)
                        is ChatUiEvent.UpdateDraft -> viewModel.updateDraft(event.text)
                        is ChatUiEvent.SelectModel -> viewModel.selectModel(event.id)
                        is ChatUiEvent.SelectEffort -> viewModel.selectEffort(event.effort)
                        is ChatUiEvent.SelectSpeed -> viewModel.selectSpeed(event.tier)
                        is ChatUiEvent.SelectApproval -> viewModel.selectApproval(event.preset)
                        is ChatUiEvent.ConnectTelegram -> viewModel.connectTelegram(event.phoneNumber)
                        is ChatUiEvent.SubmitTelegramAuthentication ->
                            viewModel.submitTelegramAuthentication(event.value)
                        is ChatUiEvent.ResolveCodexApproval -> viewModel.resolveCodexApproval(
                            event.requestId,
                            event.decision,
                        )
                        is ChatUiEvent.AddCapability -> viewModel.addCapability(event.capability)
                        is ChatUiEvent.RemoveCapability -> viewModel.removeCapability(event.capability)
                    }
                }
                val codexApproval = state.codexApproval
                when {
                    codexApproval != null -> CodexApprovalDialog(codexApproval) { decision ->
                        viewModel.resolveCodexApproval(codexApproval.requestId, decision)
                    }
                    showWorkspaceBrowser -> WorkspacePickerDialog(
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
                    showIntegrations -> IntegrationsDialog(
                        state = state,
                        onDismiss = { showIntegrations = false },
                        onConnect = viewModel::connectTelegram,
                        onSubmitAuthentication = viewModel::submitTelegramAuthentication,
                        onDisconnect = viewModel::disconnectTelegram,
                        onCancelAuthentication = viewModel::cancelTelegramAuthentication,
                    )
                    showEraseConfirmation -> EraseDataDialog(
                        onConfirm = {
                            showEraseConfirmation = false
                            viewModel.eraseAppData()
                        },
                        onDismiss = { showEraseConfirmation = false },
                    )
                    showPrivacyDisclosure -> PrivacyDialog(
                        onDismiss = { showPrivacyDisclosure = false },
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
