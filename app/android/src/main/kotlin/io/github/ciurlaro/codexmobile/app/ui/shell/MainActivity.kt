package io.github.ciurlaro.codexmobile.app.ui.shell

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.viewmodel.AppViewModel
import io.github.ciurlaro.codexmobile.app.presentation.viewmodel.SendMessageOutcome
import io.github.ciurlaro.codexmobile.app.security.navigation.toOfficialSignInUri
import io.github.ciurlaro.codexmobile.app.ui.authentication.ConnectorAuthActivity
import io.github.ciurlaro.codexmobile.app.ui.session.CodexApprovalDialog
import io.github.ciurlaro.codexmobile.app.ui.session.ElicitationDialog
import io.github.ciurlaro.codexmobile.app.ui.settings.EraseDataDialog
import io.github.ciurlaro.codexmobile.app.ui.settings.PrivacyDialog
import io.github.ciurlaro.codexmobile.app.ui.settings.PluginSettingsDialog
import io.github.ciurlaro.codexmobile.app.ui.settings.WorkspacePickerDialog
import io.github.ciurlaro.codexmobile.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()

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
            var showPluginSettings by rememberSaveable { mutableStateOf(false) }
            var showWorkspaceRequired by rememberSaveable { mutableStateOf(false) }
            var showWorkspaceBrowser by rememberSaveable { mutableStateOf(false) }
            var workspaceBrowserPath by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingWorkspaceSelection by rememberSaveable { mutableStateOf(false) }
            var openedSignInUrl by rememberSaveable { mutableStateOf<String?>(null) }
            var openedConnectorUrl by rememberSaveable { mutableStateOf<String?>(null) }
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
            val connectorAuthentication = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                openedConnectorUrl = null
                viewModel.connectorAuthenticationFinished(result.resultCode == RESULT_OK)
            }
            val elicitationAuthentication = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                state.pendingElicitation?.let { elicitation ->
                    viewModel.resolveElicitation(
                        elicitation.requestId,
                        io.github.ciurlaro.codexmobile.core.AgentElicitationResponse(
                            if (result.resultCode == RESULT_OK) {
                                io.github.ciurlaro.codexmobile.core.AgentElicitationAction.ACCEPT
                            } else {
                                io.github.ciurlaro.codexmobile.core.AgentElicitationAction.CANCEL
                            },
                        ),
                    )
                }
            }
            LaunchedEffect(state.signInUrl) {
                state.signInUrl?.takeIf { it != openedSignInUrl }?.let {
                    openedSignInUrl = it
                    openSignIn(it)
                }
            }
            LaunchedEffect(state.connectorAuthUrl) {
                val url = state.connectorAuthUrl
                if (url != null && url != openedConnectorUrl) {
                    openedConnectorUrl = url
                    connectorAuthentication.launch(
                        ConnectorAuthActivity.intent(
                            this@MainActivity,
                            url,
                            checkNotNull(state.connectorAuthName),
                            state.mcpServers.any { it.name == state.connectorAuthName },
                        ),
                    )
                }
            }
            LaunchedEffect(state.pendingElicitation?.requestId) {
                state.pendingElicitation?.url?.let { url ->
                    elicitationAuthentication.launch(
                        ConnectorAuthActivity.elicitationIntent(this@MainActivity, url),
                    )
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
            fun requestWorkspaceSelection() {
                if (state.hasStorageAccess) {
                    workspaceBrowserPath = state.workspacePath ?: viewModel.workspaceRoots().firstOrNull()
                    showWorkspaceBrowser = workspaceBrowserPath != null
                } else {
                    openStorageSettings(selectAfter = true)
                }
            }
            AppTheme {
                AppShell(state) { event ->
                    when (event) {
                        AppUiEvent.OpenHistory -> viewModel.openHistory()
                        AppUiEvent.CloseHistory -> viewModel.closeHistory()
                        AppUiEvent.StartNewChat -> viewModel.startNewChat()
                        AppUiEvent.OpenSettings -> viewModel.openSettings()
                        AppUiEvent.CloseSettings -> viewModel.closeSettings()
                        is AppUiEvent.OpenExtensions ->
                            viewModel.openExtensions(event.type, event.returnScreen)
                        AppUiEvent.CloseExtensions -> viewModel.closeExtensions()
                        AppUiEvent.RefreshExtensions -> viewModel.refreshExtensions()
                        AppUiEvent.OpenExtensionSources -> viewModel.openExtensionSources()
                        AppUiEvent.CloseExtensionSources -> viewModel.closeExtensionSources()
                        is AppUiEvent.OpenSelector -> viewModel.openSelector(event.selector)
                        AppUiEvent.DismissSelector -> viewModel.dismissSelector()
                        AppUiEvent.Send -> if (viewModel.sendMessage() == SendMessageOutcome.WORKSPACE_REQUIRED) {
                            showWorkspaceRequired = true
                        }
                        AppUiEvent.Stop -> viewModel.cancelTurn()
                        AppUiEvent.Authenticate -> {
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
                        AppUiEvent.CancelAuthentication -> viewModel.cancelAuthentication()
                        AppUiEvent.OpenSignIn -> openSignIn(state.signInUrl)
                        AppUiEvent.StopBackground -> viewModel.stopBackgroundWork()
                        AppUiEvent.SignOut -> viewModel.signOut()
                        AppUiEvent.ShowPrivacy -> showPrivacyDisclosure = true
                        AppUiEvent.ShowPluginSettings -> {
                            state.providerSettings.singleOrNull()?.let {
                                viewModel.openProviderSettings(it.pluginId)
                            } ?: run { showPluginSettings = state.providerSettings.isNotEmpty() }
                        }
                        AppUiEvent.ShowEraseConfirmation -> showEraseConfirmation = true
                        AppUiEvent.SelectScope -> requestWorkspaceSelection()
                        is AppUiEvent.SearchHistory -> viewModel.updateHistorySearch(event.query)
                        is AppUiEvent.OpenConversation -> viewModel.openConversation(event.id)
                        is AppUiEvent.TogglePinConversation -> viewModel.togglePinConversation(event.id)
                        is AppUiEvent.RenameConversation -> viewModel.renameConversation(event.id, event.title)
                        is AppUiEvent.DeleteConversation -> viewModel.deleteConversation(event.id)
                        is AppUiEvent.UpdateDraft -> viewModel.updateDraft(event.text)
                        is AppUiEvent.SelectModel -> viewModel.selectModel(event.id)
                        is AppUiEvent.SelectEffort -> viewModel.selectEffort(event.effort)
                        is AppUiEvent.SelectSpeed -> viewModel.selectSpeed(event.tier)
                        is AppUiEvent.SelectApproval -> viewModel.selectApproval(event.preset)
                        is AppUiEvent.SelectExtensionType -> viewModel.selectExtensionType(event.type)
                        is AppUiEvent.SelectExtensionStatus -> viewModel.selectExtensionStatus(event.status)
                        is AppUiEvent.SearchExtensions -> viewModel.searchExtensions(event.query)
                        is AppUiEvent.ToggleExtensionSource ->
                            viewModel.toggleExtensionSource(event.sourceId, event.enabled)
                        is AppUiEvent.AddExtensionSource -> viewModel.addExtensionSource(event.url)
                        AppUiEvent.DismissExtensionSource -> viewModel.dismissExtensionSource()
                        is AppUiEvent.InstallSkill -> viewModel.installSkill(event.skill)
                        is AppUiEvent.RequestUninstallSkill -> viewModel.requestUninstallSkill(event.skill)
                        is AppUiEvent.InstallPlugin -> viewModel.installPlugin(event.plugin)
                        is AppUiEvent.RequestUninstallPlugin ->
                            viewModel.requestUninstallPlugin(event.plugin, event.displayName)
                        AppUiEvent.ConfirmExtensionRemoval -> viewModel.confirmExtensionRemoval()
                        AppUiEvent.DismissExtensionRemoval -> viewModel.dismissExtensionRemoval()
                        is AppUiEvent.OpenProviderSettings -> viewModel.openProviderSettings(event.pluginId)
                        is AppUiEvent.ResolveElicitation ->
                            viewModel.resolveElicitation(event.requestId, event.response)
                        is AppUiEvent.ResolveCodexApproval -> viewModel.resolveCodexApproval(
                            event.requestId,
                            event.decision,
                        )
                        is AppUiEvent.AddCapability -> viewModel.addCapability(event.capability)
                        is AppUiEvent.RemoveCapability -> viewModel.removeCapability(event.capability)
                        is AppUiEvent.AddInvocation -> viewModel.addInvocation(event.invocation)
                        is AppUiEvent.RemoveInvocation -> viewModel.removeInvocation(event.key)
                    }
                }
                val codexApproval = state.codexApproval
                val elicitation = state.pendingElicitation
                when {
                    codexApproval != null -> CodexApprovalDialog(codexApproval) { decision ->
                        viewModel.resolveCodexApproval(codexApproval.requestId, decision)
                    }
                    showWorkspaceRequired -> AlertDialog(
                        onDismissRequest = { showWorkspaceRequired = false },
                        title = { Text("Choose a workspace") },
                        text = {
                            Text("Codex needs a workspace before it can send this message. Your draft will stay here.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showWorkspaceRequired = false
                                requestWorkspaceSelection()
                            }) { Text("Choose workspace") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWorkspaceRequired = false }) { Text("Not now") }
                        },
                    )
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
                    showPluginSettings -> PluginSettingsDialog(
                        providers = state.providerSettings,
                        onSelect = {
                            showPluginSettings = false
                            viewModel.openProviderSettings(it)
                        },
                        onDismiss = { showPluginSettings = false },
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
                    elicitation != null -> ElicitationDialog(
                        elicitation = elicitation,
                        onResponse = { response ->
                            viewModel.resolveElicitation(elicitation.requestId, response)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeAfterProviderPackageUpdate()
        viewModel.refreshStorage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.resumeAfterProviderPackageUpdate()
    }

}
