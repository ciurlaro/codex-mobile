package io.github.ciurlaro.codexmobile.app

import android.app.Application

class CodexMobileApplication : Application() {
    internal val graph by lazy { AppGraph(this) }
}
