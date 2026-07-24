package io.github.ciurlaro.codexmobile.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ConnectorAuthActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var webView: WebView? = null
    private var bound = false
    private var monitor: Job? = null
    private val target by lazy { checkNotNull(intent.getStringExtra(EXTRA_TARGET)) }
    private val isMcp by lazy { intent.getBooleanExtra(EXTRA_MCP, false) }
    private val isElicitation by lazy { intent.getBooleanExtra(EXTRA_ELICITATION, false) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val controller = (service as? CodexForegroundService.LocalBinder)?.controller ?: return
            monitor = scope.launch {
                if (isMcp) {
                    controller.state.collect { state ->
                        state.oauthCompletion?.takeIf { it.serverName == target }?.let {
                            finishWith(it.success)
                        }
                    }
                } else {
                    while (true) {
                        val connected = runCatching {
                            controller.listConnectors(forceReload = true)
                                .any { it.id == target && it.isAccessible }
                        }.getOrDefault(false)
                        if (connected) finishWith(true)
                        delay(POLL_MILLIS)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(::isSafeNavigation) ?: run {
            finishWith(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(this, null)
        }
        val browser = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                safeBrowsingEnabled = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isSafeNavigation(request.url.toString())

            }
            loadUrl(url)
        }
        webView = browser
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            addView(Button(context).apply {
                text = "Open in browser"
                setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Done"
                setOnClickListener { finishWith(isElicitation) }
            })
            addView(Button(context).apply {
                text = "Cancel"
                setOnClickListener { finishWith(false) }
            })
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        if (!isElicitation) {
            bound = bindService(
                CodexForegroundService.bindIntent(this),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    override fun onDestroy() {
        monitor?.cancel()
        if (bound) runCatching { unbindService(connection) }
        webView?.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        scope.cancel()
        super.onDestroy()
    }

    private fun finishWith(success: Boolean) {
        if (isFinishing) return
        setResult(if (success) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val EXTRA_URL = "authorization-url"
        private const val EXTRA_TARGET = "authorization-target"
        private const val EXTRA_MCP = "authorization-mcp"
        private const val EXTRA_ELICITATION = "authorization-elicitation"
        private const val POLL_MILLIS = 1_250L

        fun intent(context: Context, url: String, target: String, isMcp: Boolean): Intent =
            Intent(context, ConnectorAuthActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_MCP, isMcp)

        fun elicitationIntent(context: Context, url: String): Intent =
            Intent(context, ConnectorAuthActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TARGET, "elicitation")
                .putExtra(EXTRA_ELICITATION, true)
    }
}

internal fun isSafeNavigation(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() ||
        uri.scheme.equals("http", true) && (
            uri.host.equals("localhost", true) || uri.host == "127.0.0.1" || uri.host == "::1"
            )
}.getOrDefault(false)
