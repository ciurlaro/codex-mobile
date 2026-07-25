package io.github.ciurlaro.codexmobile.app.lifecycle

import android.app.Application
import io.github.ciurlaro.codexmobile.app.composition.AppContainer

class CodexMobileApplication : Application() {
    internal val container by lazy { AppContainer(this) }
}
