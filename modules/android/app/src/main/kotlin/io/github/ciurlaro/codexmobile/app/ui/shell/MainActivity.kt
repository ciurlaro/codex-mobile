package io.github.ciurlaro.codexmobile.app.ui.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.presentation.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel> {
        val container = (application as CodexMobileApplication).container
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == AppViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return container.newViewModel() as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        setContent { MainContent(this, viewModel) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
