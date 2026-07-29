package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.enabledMarketplaceNames
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState

internal fun AppUiState.isPluginMarketplaceEnabled(marketplaceName: String): Boolean {
    val canonical = canonicalPluginSourceId(marketplaceName)
    return ExtensionSourceSelection(
        knownExtensionSourceIds,
        enabledExtensionSourceIds,
        customExtensionSources,
    ).enabledMarketplaceNames().any {
        it == marketplaceName || canonicalPluginSourceId(it) == canonical
    }
}
