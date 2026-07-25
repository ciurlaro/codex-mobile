package io.github.ciurlaro.codexmobile.appserver.client

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerMethod
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerNotificationDescriptor
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerProtocolDescriptors
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerRequestDescriptor
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerClientMethods
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ClientNotification
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ClientNotificationInitializedNotification
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeResponse
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCError
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCErrorError
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCRequest
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCResponse
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ServerNotification
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ServerRequest
import io.github.ciurlaro.codexmobile.appserver.transport.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntimeEvent
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntimeFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

public sealed interface AppServerConnectionState {
    public data object Stopped : AppServerConnectionState
    public data object Starting : AppServerConnectionState
    public data class Ready(public val initializeResponse: InitializeResponse) : AppServerConnectionState
    public data class Failed(public val code: String, public val message: String) : AppServerConnectionState
    public data object Closed : AppServerConnectionState
}

public sealed interface AppServerEvent {
    public data class Request(
        public val value: ServerRequest,
        public val descriptor: AppServerRequestDescriptor,
    ) : AppServerEvent

    public data class Notification(
        public val value: ServerNotification,
        public val descriptor: AppServerNotificationDescriptor,
    ) : AppServerEvent

    public data class Failure(public val code: String, public val message: String) : AppServerEvent
}

public class AppServerRpcException(
    public val code: Long,
    public val detail: String,
    public val data: JsonElement? = null,
) : IllegalStateException("App-server error $code: $detail")

public class AppServerRuntimeException(message: String) : IllegalStateException(message)

public class AppServerProtocolException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

public class AppServerDeliveryException(message: String) : IllegalStateException(message)

public class AppServerTimeoutException(message: String) : IllegalStateException(message)

public class AppServerConnection(
    private val runtimeFactory: CodexRuntimeFactory,
    private val initializeParams: InitializeParams,
    private val requestTimeoutMillis: Long = 30.seconds.inWholeMilliseconds,
    commandCapacity: Int = DEFAULT_COMMAND_CAPACITY,
    private val eventCapacity: Int = DEFAULT_EVENT_CAPACITY,
) : AutoCloseable {
    init {
        require(requestTimeoutMillis > 0) { "Request timeout must be positive" }
        require(commandCapacity > 0) { "Command capacity must be positive" }
        require(eventCapacity > 0) { "Event capacity must be positive" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Command>(commandCapacity)
    private val eventChannel = Channel<AppServerEvent>(eventCapacity)
    private val mutableState = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Stopped)
    private val closed = CompletableDeferred<Unit>()

    public val state: StateFlow<AppServerConnectionState> = mutableState.asStateFlow()
    public val events: Flow<AppServerEvent> = eventChannel.receiveAsFlow()

    // The fields below are touched only by the command-loop coroutine.
    private var runtime: CodexRuntime? = null
    private var runtimeEvents: Job? = null
    private var nextRequestId = 1L
    private val pending = mutableMapOf<Long, Pending>()
    private val startWaiters = mutableSetOf<CompletableDeferred<InitializeResponse>>()
    private var terminalFailure: AppServerRuntimeException? = null

    init {
        scope.launch { commandLoop() }
    }

    public suspend fun ensureStarted(
        timeoutMillis: Long = requestTimeoutMillis,
    ): InitializeResponse {
        val response = CompletableDeferred<InitializeResponse>()
        try {
            return withAppServerTimeout(timeoutMillis, "App-server startup") {
                commands.send(Command.Start(response))
                response.await()
            }
        } finally {
            commands.trySend(Command.CancelStart(response))
        }
    }

    public suspend fun <P, R> request(
        method: AppServerMethod<P, R>,
        params: P,
        timeoutMillis: Long = requestTimeoutMillis,
    ): R {
        ensureStarted(timeoutMillis)
        val response = CompletableDeferred<JsonElement>()
        val encodedParams = JSON.encodeToJsonElement(method.paramsSerializer, params)
        val encodedResponse = try {
            withAppServerTimeout(timeoutMillis, "App-server request ${method.descriptor.method}") {
                commands.send(Command.Request(method.descriptor.method, encodedParams, response))
                response.await()
            }
        } finally {
            commands.trySend(Command.CancelRequest(response))
        }
        return decode(method.responseSerializer, encodedResponse, method.descriptor.responseType)
    }

    public suspend fun <P, R> respond(
        id: JsonElement,
        method: AppServerMethod<P, R>,
        result: R,
        timeoutMillis: Long = requestTimeoutMillis,
    ) {
        val encoded = JSON.encodeToJsonElement(method.responseSerializer, result)
        sendResponse(JSON.encodeToString(JSONRPCResponse(id, encoded)), timeoutMillis)
    }

    public suspend fun respondError(
        id: JsonElement,
        code: Long,
        message: String,
        data: JsonElement? = null,
        timeoutMillis: Long = requestTimeoutMillis,
    ) {
        sendResponse(
            JSON.encodeToString(JSONRPCError(JSONRPCErrorError(code, message, data), id)),
            timeoutMillis,
        )
    }

    public suspend fun shutdown() {
        close()
        closed.await()
    }

    override fun close() {
        if (commands.trySend(Command.Close).isFailure) scope.cancel()
    }

    private suspend fun sendResponse(encoded: String, timeoutMillis: Long) {
        val acknowledgement = CompletableDeferred<Unit>()
        withAppServerTimeout(timeoutMillis, "App-server response") {
            commands.send(Command.Response(encoded, acknowledgement))
            acknowledgement.await()
        }
    }

    private suspend fun <T> withAppServerTimeout(
        timeoutMillis: Long,
        operation: String,
        block: suspend () -> T,
    ): T = try {
        withTimeout(timeoutMillis) { block() }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw AppServerTimeoutException("$operation timed out after ${timeoutMillis}ms")
    }

    private suspend fun commandLoop() {
        var closeRequested = false
        try {
            loop@ for (command in commands) {
                when (command) {
                    is Command.Start -> start(command.response)
                    is Command.CancelStart -> cancelStart(command.response)
                    is Command.Request -> sendRequest(command)
                    is Command.CancelRequest -> cancelRequest(command.response)
                    is Command.Response -> sendServerResponse(command)
                    is Command.RuntimeEvent -> handleRuntimeEvent(command.source, command.event)
                    is Command.RuntimeFlowFailed -> failRuntime(
                        command.source,
                        "io_failure",
                        command.message,
                    )
                    Command.Close -> {
                        closeRequested = true
                        break@loop
                    }
                }
            }
        } finally {
            val error = AppServerRuntimeException("Codex connection is closed")
            pending.values.forEach { it.fail(error) }
            pending.clear()
            startWaiters.forEach { it.completeExceptionally(error) }
            startWaiters.clear()
            stopRuntime()
            if (closeRequested || mutableState.value !is AppServerConnectionState.Failed) {
                mutableState.value = AppServerConnectionState.Closed
            }
            commands.close()
            eventChannel.close()
            closed.complete(Unit)
            scope.cancel()
        }
    }

    private suspend fun start(waiter: CompletableDeferred<InitializeResponse>) {
        terminalFailure?.let {
            waiter.completeExceptionally(it)
            return
        }
        when (val current = mutableState.value) {
            is AppServerConnectionState.Ready -> {
                waiter.complete(current.initializeResponse)
                return
            }
            AppServerConnectionState.Starting -> {
                startWaiters += waiter
                return
            }
            AppServerConnectionState.Closed -> {
                waiter.completeExceptionally(AppServerRuntimeException("Codex connection is closed"))
                return
            }
            is AppServerConnectionState.Failed,
            AppServerConnectionState.Stopped,
            -> Unit
        }

        startWaiters += waiter
        mutableState.value = AppServerConnectionState.Starting
        val started = try {
            runtimeFactory.create()
        } catch (error: Throwable) {
            failRuntime(null, "process_start", error.visibleMessage())
            return
        }
        runtime = started
        runtimeEvents = scope.launch { collectRuntime(started) }
        try {
            started.start()
            val id = nextRequestId++
            pending[id] = Pending.Initialize
            write(
                JSON.encodeToString(
                    JSONRPCRequest(
                        id = JsonPrimitive(id),
                        method = AppServerClientMethods.Initialize.descriptor.method,
                        params = JSON.encodeToJsonElement(
                            AppServerClientMethods.Initialize.paramsSerializer,
                            initializeParams,
                        ),
                    ),
                ),
            )
        } catch (error: Throwable) {
            failRuntime(started, "initialize_failed", error.visibleMessage())
        }
    }

    private fun cancelStart(waiter: CompletableDeferred<InitializeResponse>) {
        startWaiters -= waiter
        if (startWaiters.isNotEmpty() || mutableState.value != AppServerConnectionState.Starting) return
        pending.entries.removeAll { (_, value) -> value === Pending.Initialize }
        stopRuntime()
        mutableState.value = AppServerConnectionState.Stopped
    }

    private suspend fun sendRequest(command: Command.Request) {
        if (mutableState.value !is AppServerConnectionState.Ready) {
            command.response.completeExceptionally(AppServerRuntimeException("Codex app-server is not ready"))
            return
        }
        val id = nextRequestId++
        pending[id] = Pending.Request(command.response)
        try {
            write(
                JSON.encodeToString(
                    JSONRPCRequest(JsonPrimitive(id), command.method, command.params),
                ),
            )
        } catch (error: Throwable) {
            pending.remove(id)
            command.response.completeExceptionally(error)
            failRuntime(runtime, "io_failure", error.visibleMessage())
        }
    }

    private fun cancelRequest(response: CompletableDeferred<JsonElement>) {
        val entry = pending.entries.firstOrNull {
            (it.value as? Pending.Request)?.response === response
        } ?: return
        pending.remove(entry.key)
        response.cancel()
    }

    private suspend fun sendServerResponse(command: Command.Response) {
        try {
            write(command.encoded)
            command.acknowledgement.complete(Unit)
        } catch (error: Throwable) {
            command.acknowledgement.completeExceptionally(error)
            failRuntime(runtime, "io_failure", error.visibleMessage())
        }
    }

    private suspend fun collectRuntime(source: CodexRuntime) {
        try {
            source.events.collect { event ->
                commands.send(Command.RuntimeEvent(source, event))
            }
            commands.send(Command.RuntimeEvent(source, CodexRuntimeEvent.EndOfFile))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            commands.send(Command.RuntimeFlowFailed(source, error.visibleMessage()))
        }
    }

    private suspend fun handleRuntimeEvent(source: CodexRuntime, event: CodexRuntimeEvent) {
        if (runtime !== source) return
        when (event) {
            is CodexRuntimeEvent.Received -> try {
                handleMessage(event.line.value)
            } catch (error: AppServerDeliveryException) {
                failRuntime(source, "event_delivery_overflow", error.visibleMessage(), terminal = true)
            } catch (error: Throwable) {
                failRuntime(source, "protocol_failure", error.visibleMessage())
            }
            is CodexRuntimeEvent.StartFailure -> failRuntime(source, "process_start", event.message)
            is CodexRuntimeEvent.IoFailure -> failRuntime(source, "io_failure", event.message)
            CodexRuntimeEvent.EndOfFile ->
                failRuntime(source, "unexpected_eof", "Codex app-server closed its output")
            is CodexRuntimeEvent.Exited ->
                failRuntime(source, "process_exit", "Codex app-server exited with code ${event.code}")
        }
    }

    private suspend fun handleMessage(line: String) {
        val message = JSON.parseToJsonElement(line) as? JsonObject
            ?: throw AppServerProtocolException("App-server message must be a JSON object")
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val id = message["id"]
        when {
            method != null && id != null -> {
                val descriptor = AppServerProtocolDescriptors.serverRequests[method]
                if (descriptor == null) {
                    write(
                        JSON.encodeToString(
                            JSONRPCError(
                                JSONRPCErrorError(-32601, "Unknown server request: $method"),
                                id,
                            ),
                        ),
                    )
                    return
                }
                val value = decode(ServerRequest.serializer(), message, "ServerRequest")
                emit(AppServerEvent.Request(value, descriptor))
            }
            method != null -> {
                val descriptor = AppServerProtocolDescriptors.serverNotifications[method] ?: return
                val value = decode(ServerNotification.serializer(), message, "ServerNotification")
                emit(AppServerEvent.Notification(value, descriptor))
            }
            id != null -> handleResponse(message)
            else -> throw AppServerProtocolException("App-server message has neither method nor id")
        }
    }

    private suspend fun handleResponse(message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.longOrNull
            ?: throw AppServerProtocolException("App-server response id is not a client request id")
        val request = pending.remove(id) ?: return
        val error = message["error"]?.let {
            decode(JSONRPCError.serializer(), message, "JSONRPCError").error
        }
        if (error != null) {
            val exception = AppServerRpcException(error.code, error.message, error.data)
            when (request) {
                Pending.Initialize -> failRuntime(runtime, "initialize_failed", exception.message.orEmpty())
                is Pending.Request -> request.response.completeExceptionally(exception)
            }
            return
        }
        val response = decode(JSONRPCResponse.serializer(), message, "JSONRPCResponse").result
        when (request) {
            Pending.Initialize -> completeInitialization(response)
            is Pending.Request -> request.response.complete(response)
        }
    }

    private suspend fun completeInitialization(result: JsonElement) {
        val response = try {
            decode(AppServerClientMethods.Initialize.responseSerializer, result, "InitializeResponse")
        } catch (error: Throwable) {
            failRuntime(runtime, "initialize_failed", error.visibleMessage())
            return
        }
        try {
            write(
                JSON.encodeToString<ClientNotification>(ClientNotificationInitializedNotification()),
            )
        } catch (error: Throwable) {
            failRuntime(runtime, "initialize_failed", error.visibleMessage())
            return
        }
        mutableState.value = AppServerConnectionState.Ready(response)
        startWaiters.forEach { it.complete(response) }
        startWaiters.clear()
    }

    private suspend fun failRuntime(
        source: CodexRuntime?,
        code: String,
        message: String,
        terminal: Boolean = false,
    ) {
        if (source != null && runtime !== source) return
        val error = AppServerRuntimeException(message)
        pending.values.forEach { it.fail(error) }
        pending.clear()
        startWaiters.forEach { it.completeExceptionally(error) }
        startWaiters.clear()
        stopRuntime()
        if (terminal) terminalFailure = error
        mutableState.value = AppServerConnectionState.Failed(code, message)
        if (eventChannel.trySend(AppServerEvent.Failure(code, message)).isFailure) {
            val deliveryError = AppServerDeliveryException(
                "App Server event buffer exceeded $eventCapacity entries; connection stopped",
            )
            terminalFailure = AppServerRuntimeException(deliveryError.message.orEmpty())
            mutableState.value = AppServerConnectionState.Failed(
                "event_delivery_overflow",
                deliveryError.message.orEmpty(),
            )
            eventChannel.close(deliveryError)
        }
    }

    private fun stopRuntime() {
        runtimeEvents?.cancel()
        runtimeEvents = null
        val stopped = runtime
        runtime = null
        runCatching { stopped?.close() }
    }

    private suspend fun write(encoded: String) {
        check(encoded.encodeToByteArray().size <= MAX_MESSAGE_BYTES) {
            "JSON-RPC message exceeds the byte limit"
        }
        checkNotNull(runtime) { "Codex app-server is not running" }
            .send(CodexJsonLine(encoded))
    }

    private fun emit(event: AppServerEvent) {
        if (eventChannel.trySend(event).isFailure) {
            throw AppServerDeliveryException(
                "App Server event buffer exceeded $eventCapacity entries; connection stopped",
            )
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement, type: String): T =
        try {
            JSON.decodeFromJsonElement(serializer, element)
        } catch (error: Throwable) {
            throw AppServerProtocolException("Invalid $type", error)
        }

    private sealed interface Pending {
        data object Initialize : Pending
        data class Request(val response: CompletableDeferred<JsonElement>) : Pending

        fun fail(error: Throwable) {
            if (this is Request) response.completeExceptionally(error)
        }
    }

    private sealed interface Command {
        data class Start(val response: CompletableDeferred<InitializeResponse>) : Command
        data class CancelStart(val response: CompletableDeferred<InitializeResponse>) : Command
        data class Request(
            val method: String,
            val params: JsonElement,
            val response: CompletableDeferred<JsonElement>,
        ) : Command
        data class CancelRequest(val response: CompletableDeferred<JsonElement>) : Command
        data class Response(val encoded: String, val acknowledgement: CompletableDeferred<Unit>) : Command
        data class RuntimeEvent(val source: CodexRuntime, val event: CodexRuntimeEvent) : Command
        data class RuntimeFlowFailed(val source: CodexRuntime, val message: String) : Command
        data object Close : Command
    }

    private companion object {
        val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
        const val DEFAULT_COMMAND_CAPACITY = 256
        const val DEFAULT_EVENT_CAPACITY = 256
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
    }
}

private fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"
