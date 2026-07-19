package io.github.ciurlaro.codexmobile.app

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { scopePicker.launch(null) }) {
                                Text(if (state.scopeSelected) "Change document folder" else "Select document folder")
                            }
                            if (state.scopeSelected) {
                                Button(onClick = viewModel::revokeScope) { Text("Revoke access") }
                            }
                        }
                        if (state.scopeSelected) Text("Read-only document access enabled")

                        if (state.sessionId == null && state.verificationUrl == null) {
                            Button(onClick = viewModel::authenticate) { Text("Sign in with ChatGPT") }
                        }

                        state.userCode?.let { code ->
                            Text("One-time code")
                            Text(code, fontFamily = FontFamily.Monospace)
                        }
                        state.verificationUrl?.let { url ->
                            Text("Keep Codex Mobile open. On another device, visit:")
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshScope()
    }

}
