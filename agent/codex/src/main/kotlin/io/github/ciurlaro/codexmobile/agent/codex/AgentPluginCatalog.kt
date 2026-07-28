package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentFormValue
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentHook
import io.github.ciurlaro.codexmobile.core.AgentHookActivity
import io.github.ciurlaro.codexmobile.core.AgentHookCatalog
import io.github.ciurlaro.codexmobile.core.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.core.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentPlanProgress
import io.github.ciurlaro.codexmobile.core.AgentPlanStep
import io.github.ciurlaro.codexmobile.core.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentServiceTier
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkillChunk
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalState
import java.io.File
import java.net.URI
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.KSerializer


internal suspend fun CodexAgentClient.listInstalledPluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean,
): AgentPluginCatalog {
    validateWorkingDirectory(workingDirectory)
    val cache = pluginCacheFile(workingDirectory, "installed")
    val cached = readPluginCache(
        cache,
        PluginInstalledResponse.serializer(),
        PluginInstalledResponse::marketplaces,
        PluginInstalledResponse::marketplaceLoadErrors,
    )
    if (!forceRefresh && cached != null) {
        reconcileProvidersInBackground(cached)
        return cached
    }
    val catalog = runCatching {
        listPlugins(
            workingDirectory,
            AppServerClientMethods.PluginInstalled,
            PluginInstalledParams(cwds = workingDirectory?.let(::listOf)),
            timeoutMillis = pluginRequestTimeoutMillis,
            marketplaces = PluginInstalledResponse::marketplaces,
            loadErrors = PluginInstalledResponse::marketplaceLoadErrors,
        ) { writePluginCache(cache, PluginInstalledResponse.serializer(), it) }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        return cached?.asStale(error.visibleMessage()) ?: throw error
    }.let { live ->
        if ((live.plugins.isEmpty() || live.errors.isNotEmpty()) && cached?.plugins?.isNotEmpty() == true) {
            live.withCachedFallback(
                cached,
                "Installed plugins could not be fully verified; showing saved plugins.",
            )
        } else {
            live
        }
    }
    reconcileProvidersInBackground(catalog)
    return catalog
}

internal suspend fun CodexAgentClient.listAvailablePluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean,
): AgentPluginCatalog {
    validateWorkingDirectory(workingDirectory)
    val cache = pluginCacheFile(workingDirectory, "available")
    val cached = readPluginCache(
        cache,
        PluginListResponse.serializer(),
        PluginListResponse::marketplaces,
        PluginListResponse::marketplaceLoadErrors,
    )
    if (!forceRefresh && cached != null) return cached
    return runCatching {
        var catalog = requestAvailablePlugins(workingDirectory, cache)
        for (retryDelay in emptyPluginCatalogRetryDelaysMillis) {
            if (catalog.plugins.isNotEmpty() || catalog.errors.isNotEmpty()) break
            delay(retryDelay)
            catalog = requestAvailablePlugins(workingDirectory, cache)
        }
        when {
            catalog.errors.isNotEmpty() && cached?.plugins?.isNotEmpty() == true -> {
                catalog.withCachedFallback(cached, "Some marketplaces could not be refreshed; showing saved plugins.")
            }
            catalog.plugins.isNotEmpty() || catalog.errors.isNotEmpty() -> catalog
            cached?.plugins?.isNotEmpty() == true -> cached.asStale(
                "The plugin marketplace is not ready; showing saved plugins.",
            )
            else -> catalog.copy(errors = listOf("The plugin marketplace is not ready yet. Retry in a moment."))
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        cached?.asStale(error.visibleMessage()) ?: throw error
    }
}

internal suspend fun CodexAgentClient.requestAvailablePluginsAction(workingDirectory: String?, cache: File?): AgentPluginCatalog =
    listPlugins(
        workingDirectory,
        AppServerClientMethods.PluginList,
        PluginListParams(cwds = workingDirectory?.let(::listOf)),
        pluginRequestTimeoutMillis,
        marketplaces = PluginListResponse::marketplaces,
        loadErrors = PluginListResponse::marketplaceLoadErrors,
    ) { writePluginCache(cache, PluginListResponse.serializer(), it) }

internal suspend fun <P, R> CodexAgentClient.listPluginsAction(
    workingDirectory: String?,
    method: AppServerMethod<P, R>,
    params: P,
    timeoutMillis: Long? = null,
    marketplaces: (R) -> List<PluginMarketplaceEntry>,
    loadErrors: (R) -> List<MarketplaceLoadErrorInfo>?,
    onResponse: (R) -> Unit = {},
): AgentPluginCatalog {
    validateWorkingDirectory(workingDirectory)
    val result = pluginRequest(method, params, timeoutMillis ?: pluginRequestTimeoutMillis)
    val errors = loadErrors(result).orEmpty().map { it.message }.distinct()
    val catalog = AgentPluginCatalog(parsePluginMarketplaces(marketplaces(result)), errors)
    if (builtInToolDispatcher != null) {
        builtInToolGate.withLock {
            applyBuiltInPluginEnablement(catalog)
            builtInEnablementLoaded.set(true)
        }
    }
    if (catalog.plugins.isNotEmpty() && catalog.errors.isEmpty()) runCatching { onResponse(result) }
    return catalog
}

internal suspend fun <P, R> CodexAgentClient.pluginRequestAction(
    method: AppServerMethod<P, R>,
    params: P,
    timeoutMillis: Long = pluginRequestTimeoutMillis,
    retryOnTimeout: Boolean = false,
): R = pluginRequestMutex.withLock {
    try {
        connection.request(method, params, timeoutMillis)
    } catch (error: AppServerTimeoutException) {
        if (!retryOnTimeout) throw error
        connection.request(method, params, timeoutMillis)
    }
}

internal fun CodexAgentClient.pluginCacheFileAction(workingDirectory: String?, kind: String): File? {
    val directory = pluginCacheDirectory ?: return null
    val key = MessageDigest.getInstance("SHA-256")
        .digest("$clientVersion\u0000${workingDirectory.orEmpty()}\u0000$kind".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return File(directory, "$key.json")
}

internal fun <T> CodexAgentClient.readPluginCacheAction(
    file: File?,
    serializer: KSerializer<T>,
    marketplaces: (T) -> List<PluginMarketplaceEntry>,
    loadErrors: (T) -> List<MarketplaceLoadErrorInfo>?,
): AgentPluginCatalog? {
    if (file?.isFile != true) return null
    return runCatching {
        val result = PROTOCOL_JSON.decodeFromString(serializer, file.readText())
        val freshness = if (System.currentTimeMillis() - file.lastModified() <= CATALOG_CACHE_TTL_MILLIS) {
            AgentCatalogFreshness.FRESH_CACHE
        } else {
            AgentCatalogFreshness.STALE_CACHE
        }
        AgentPluginCatalog(
            plugins = parsePluginMarketplaces(marketplaces(result)),
            errors = loadErrors(result).orEmpty().map { it.message }.distinct(),
            freshness = freshness,
        )
    }.getOrNull()
}

internal fun <T> CodexAgentClient.writePluginCacheAction(file: File?, serializer: KSerializer<T>, response: T) {
    if (file == null) return
    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
        "Unable to prepare plugin catalog cache"
    }
    val next = File(file.parentFile, ".${file.name}.next")
    next.writeText(PROTOCOL_JSON.encodeToString(serializer, response))
    Files.move(
        next.toPath(),
        file.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

internal fun CodexAgentClient.validateWorkingDirectoryAction(workingDirectory: String?) {
    require(workingDirectory == null || workingDirectory.startsWith('/')) {
        "Working directory must be absolute"
    }
}

internal fun CodexAgentClient.clearPluginCacheAction() {
    pluginCacheDirectory?.listFiles().orEmpty().forEach(File::delete)
}
