package io.github.ciurlaro.codexmobile.providers.telegram

import io.github.ciurlaro.codexmobile.extension.host.AndroidProviderSecretStore
import io.github.ciurlaro.codexmobile.provider.api.ProviderSecrets

class TelegramSettingsActivity : TelegramSettingsActivityBase() {
    override fun credentialStore(): TelegramCredentialStore {
        val store = AndroidProviderSecretStore(applicationContext, TELEGRAM_PLUGIN_ID)
        return object : TelegramCredentialStore {
            override fun snapshot(): ProviderSecrets = store.snapshot()
            override fun replace(values: Map<String, String>) = store.replace(values)
            override fun clear() = store.clear()
        }
    }
}
