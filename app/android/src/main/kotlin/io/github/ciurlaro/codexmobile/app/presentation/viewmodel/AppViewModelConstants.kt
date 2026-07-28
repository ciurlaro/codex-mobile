package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

internal const val MAX_CONVERSATION_TITLE_LENGTH = 80
internal const val EXISTING_SERVICE_BIND_TIMEOUT_MILLIS = 1_000L
internal const val AUTHENTICATION_RECOVERY_DELAY_MILLIS = 150L
internal const val CONNECTOR_UPDATE_WAIT_MILLIS = 1_500L
internal const val EXTENSION_NOTICE_DURATION_MILLIS = 4_000L

internal enum class SendMessageOutcome { HANDLED, WORKSPACE_REQUIRED }
