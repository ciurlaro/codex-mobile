package io.github.ciurlaro.codexmobile.app.ui.authentication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import io.github.ciurlaro.codexmobile.app.security.navigation.isSafeConnectorNavigation

class ConnectorAuthActivity : ComponentActivity() {
    private val browser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finishWith(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(::isSafeConnectorNavigation) ?: run {
            finishWith(false)
            return
        }
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(Color.BLACK)
            .setNavigationBarColor(Color.BLACK)
            .build()
        val browserIntent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(true)
            .setInitialActivityHeightPx(
                (resources.displayMetrics.heightPixels * PARTIAL_HEIGHT_RATIO).toInt(),
                CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE,
            )
            .build()
            .intent
            .apply { data = Uri.parse(url) }
        runCatching { browser.launch(browserIntent) }
            .onFailure { finishWith(false) }
    }

    private fun finishWith(opened: Boolean) {
        if (isFinishing) return
        setResult(if (opened) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val EXTRA_URL = "authorization-url"
        private const val PARTIAL_HEIGHT_RATIO = 0.88f

        fun intent(context: Context, url: String): Intent =
            Intent(context, ConnectorAuthActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
