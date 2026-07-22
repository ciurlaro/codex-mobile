package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.codex.BUILT_IN_MUTATION_TOOLS
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolContent
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolDispatcher
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class AndroidBuiltInToolDispatcher(
    context: Context,
    private val tools: PrivateBackendBundle,
    private val workspace: WorkspaceManager,
) : BuiltInToolDispatcher {
    private val appContext = context.applicationContext
    private val journal = BuiltInMutationJournal(appContext)

    override suspend fun execute(call: BuiltInToolCall): BuiltInToolResult = withContext(Dispatchers.IO) {
        when (call.tool) {
            "documents_read" -> documentsRead(call)
            "documents_view_pages" -> documentsViewPages(call)
            "documents_edit" -> documentEdit(call)
            "telegram_list_chats" -> telegramListChats(call)
            "telegram_list_messages" -> telegramListMessages(call)
            "telegram_search_messages" -> telegramSearchMessages(call)
            "telegram_search_contacts" -> telegramSearchContacts(call)
            "telegram_download_media" -> telegramDownloadMedia(call)
            "telegram_send_text" -> telegramSendText(call)
            "telegram_send_file" -> telegramSendFile(call)
            else -> error("Unknown built-in tool")
        }
    }

    override suspend fun replay(call: BuiltInToolCall): BuiltInToolResult? = withContext(Dispatchers.IO) {
        if (call.tool !in BUILT_IN_MUTATION_TOOLS) return@withContext null
        val existing = journal.find(call) ?: return@withContext null
        when (existing.state) {
            MutationState.PREPARED -> null
            MutationState.SUCCEEDED, MutationState.FAILED, MutationState.INDETERMINATE ->
                checkNotNull(existing.result) { "Mutation journal terminal result is missing" }
            MutationState.DISPATCHED -> execute(call)
        }
    }

    private fun documentsRead(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly(
            "path", "mode", "request", "selector", "pageStart", "pageCount", "maxChars", "cursor",
        )
        val maxChars = args.int("maxChars", 48_000, 1, MAX_TEXT_RESULT_CHARS)
        val source = workspace.resolveFile(call.workspace, args.string("path"), mustExist = true)
        require(source.length() in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val snapshot = immutableSnapshot(source)
        val mode = args.string("mode", "auto").also { require(it in MODES) }
        val request = args.string("request", "text").also { require(it in REQUESTS) }
        val selector = args.stringOrNull("selector")
        if (request == "element") require(!selector.isNullOrBlank()) { "selector is required for element reads" }
        val firstPage = args.int("pageStart", 1, 1, 100_000)
        val pageCount = args.int("pageCount", 10, 1, MAX_READ_PAGES)
        val cacheKey = sha256("${snapshot.name}\u0000$mode\u0000$request\u0000$selector\u0000$firstPage\u0000$pageCount")
        val cache = File(tools.snapshotsDirectory, "$cacheKey.txt")
        args.stringOrNull("cursor")?.let { return continueTextSnapshot(it, cache.name, maxChars) }
        if (!cache.isFile) {
            val extracted = extractDocument(snapshot, mode, request, selector, firstPage, pageCount)
            require(extracted.toByteArray(StandardCharsets.UTF_8).size <= MAX_EXTRACTED_BYTES) {
                "Extracted document content exceeds the private snapshot limit"
            }
            writeImmutable(cache, extracted.toByteArray(StandardCharsets.UTF_8))
        }
        return textSnapshotResult(cache, 0, maxChars)
    }

    private fun documentsViewPages(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("path", "pages", "dpi")
        val source = workspace.resolveFile(call.workspace, args.string("path"), mustExist = true)
        require(source.length() in 1..MAX_DOCUMENT_BYTES) { "Document is empty or too large" }
        val pages = args.array("pages").map { it.jsonPrimitive.intOrNull ?: error("Page must be an integer") }
        require(pages.isNotEmpty() && pages.size <= MAX_VIEW_PAGES && pages.all { it > 0 } && pages.distinct() == pages) {
            "pages must contain 1-$MAX_VIEW_PAGES unique positive integers"
        }
        val dpi = args.int("dpi", 120, 72, 160)
        val snapshot = immutableSnapshot(source)
        val extension = source.extension.lowercase()
        if (extension in IMAGE_EXTENSIONS) {
            require(pages == listOf(1)) { "Images only have page 1" }
            require(snapshot.length() <= MAX_INLINE_IMAGE_BYTES) { "Image is too large to return inline" }
            return BuiltInToolResult(
                listOf(BuiltInToolContent.Image(dataUrl(snapshot.extension, snapshot.readBytes()))),
                true,
            )
        }
        require(extension == "pdf") { "Page rendering is available for PDF and image files" }
        val content = pages.map { page ->
            val output = File(tools.snapshotsDirectory, "${snapshot.nameWithoutExtension}-p$page-$dpi.png")
            if (!output.isFile) {
                val result = runBackend(
                    PrivateBackend.MUTOOL,
                    listOf("draw", "-q", "-F", "png", "-r", dpi.toString(), "-o", output.absolutePath, snapshot.absolutePath, page.toString()),
                    timeoutSeconds = 45,
                    maxBytes = MAX_COMMAND_BYTES,
                )
                result.requireSuccess("PDF page rendering")
                require(output.length() in 1..MAX_INLINE_IMAGE_BYTES) { "Rendered page is too large" }
                output.setReadOnly()
            }
            BuiltInToolContent.Image(dataUrl("png", output.readBytes()))
        }
        return BuiltInToolResult(content, true)
    }

    private fun extractDocument(
        snapshot: File,
        mode: String,
        request: String,
        selector: String?,
        firstPage: Int,
        pageCount: Int,
    ): String {
        val extension = snapshot.extension.lowercase()
        return when {
            extension == "pdf" -> when {
                request == "stats" || request == "issues" -> runBackend(
                    PrivateBackend.MUTOOL,
                    listOf("info", snapshot.absolutePath),
                    30,
                    MAX_COMMAND_BYTES,
                ).requireSuccess("PDF inspection").output
                request == "element" -> error("PDF element selection is unavailable; use bounded page text")
                request == "outline" -> runBackend(
                    PrivateBackend.MUTOOL,
                    listOf("show", snapshot.absolutePath, "outline"),
                    30,
                    MAX_EXTRACTED_BYTES,
                ).requireSuccess("PDF outline extraction").output
                mode == "ocr" -> ocr(snapshot, firstPage, pageCount)
                else -> {
                    val pages = "$firstPage-${firstPage + pageCount - 1}"
                    val native = runBackend(
                        PrivateBackend.MUTOOL,
                        listOf("draw", "-q", "-F", "txt", "-o", "-", snapshot.absolutePath, pages),
                        45,
                        MAX_EXTRACTED_BYTES,
                    )
                    if (native.exitCode == 0 && (mode == "native" || native.output.isNotBlank())) {
                        native.output
                    } else if (mode == "auto") {
                        ocr(snapshot, firstPage, pageCount)
                    } else {
                        native.requireSuccess("PDF text extraction").output
                    }
                }
            }
            extension in OFFICE_EXTENSIONS -> {
                require(mode != "ocr") { "OCR mode is only available for PDF and image files" }
                val command = if (request == "element") {
                    val selected = checkNotNull(selector)
                    if (selected.startsWith('/')) {
                        listOf("get", snapshot.absolutePath, selected, "--depth", "2", "--json")
                    } else {
                        listOf("query", snapshot.absolutePath, selected, "--json")
                    }
                } else {
                    listOf("view", snapshot.absolutePath, request, "--json")
                }
                runBackend(PrivateBackend.OFFICE, command, 60, MAX_EXTRACTED_BYTES)
                    .requireSuccess("Office document extraction").output
            }
            extension in IMAGE_EXTENSIONS -> {
                require(request in setOf("text", "outline")) { "Images support text extraction only" }
                require(mode != "native") { "Images do not contain native document text" }
                ocr(snapshot, 1, 1)
            }
            else -> error("Unsupported document type")
        }
    }

    private fun ocr(snapshot: File, firstPage: Int, pageCount: Int): String {
        require(pageCount <= MAX_OCR_PAGES) { "OCR is limited to $MAX_OCR_PAGES pages" }
        if (snapshot.extension.lowercase() in IMAGE_EXTENSIONS) {
            require(firstPage == 1 && pageCount == 1) { "Images only have page 1" }
        }
        return buildString {
            repeat(pageCount) { offset ->
                val page = firstPage + offset
                val image = File(tools.snapshotsDirectory, "${snapshot.nameWithoutExtension}-ocr-$page.pnm")
                val render = runBackend(
                    PrivateBackend.MUTOOL,
                    listOf("draw", "-q", "-F", "pnm", "-r", "144", "-o", image.absolutePath, snapshot.absolutePath, page.toString()),
                    45,
                    MAX_COMMAND_BYTES,
                )
                render.requireSuccess("OCR page rendering")
                try {
                    val text = runBackend(
                        PrivateBackend.TESSERACT,
                        listOf(image.absolutePath, "stdout", "-l", "eng"),
                        60,
                        MAX_EXTRACTED_BYTES,
                    ).requireSuccess("English OCR").output
                    if (pageCount > 1) append("\n--- Page $page ---\n")
                    append(text)
                } finally {
                    image.delete()
                }
            }
        }
    }

    private fun documentEdit(call: BuiltInToolCall): BuiltInToolResult {
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        val args = call.arguments.requireOnly("path", "create", "overwrite", "expectedSha256", "operations")
        val destination = workspace.resolveFile(call.workspace, args.string("path"), mustExist = false)
        require(destination.extension.lowercase() in OFFICE_EXTENSIONS) { "Office edits require DOCX, XLSX, or PPTX" }
        val beforeHash = destination.takeIf(File::isFile)?.sha256()
        if (existing?.state == MutationState.DISPATCHED) {
            return reconcileDocumentDispatch(call, destination, existing, beforeHash)
        }
        val create = args.boolean("create", false)
        if (destination.exists()) {
            require(args.boolean("overwrite", false)) { "overwrite=true is required for an existing document" }
            require(args.stringOrNull("expectedSha256") == beforeHash) { "Document changed since it was read" }
        } else {
            require(create) { "create=true is required for a new document" }
            require(args["expectedSha256"] == null || args["expectedSha256"] is JsonNull) {
                "expectedSha256 is only valid for overwrite"
            }
        }
        destination.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Destination folder is unavailable" } }
        val stage = stagedSibling(destination, call.callId)
        stage.delete()
        try {
            if (destination.isFile) {
                Files.copy(destination.toPath(), stage.toPath())
            } else {
                runBackend(PrivateBackend.OFFICE, listOf("create", stage.absolutePath, "--json"), 60, MAX_COMMAND_BYTES)
                    .requireSuccess("Office document creation")
            }
            val operations = args.array("operations")
            verifyReplacementPreconditions(stage, operations)
            val commands = officeBatch(operations)
            val edit = runBackend(
                PrivateBackend.OFFICE,
                listOf("batch", stage.absolutePath, "--commands", commands.toString(), "--json"),
                90,
                MAX_COMMAND_BYTES,
            )
            if (edit.exitCode != 0) {
                val failed = BuiltInToolResult.text(edit.failure("Office edit"), false)
                journal.finish(call, MutationState.FAILED, failed, beforeHash)
                return failed
            }
            runBackend(PrivateBackend.OFFICE, listOf("validate", stage.absolutePath, "--json"), 60, MAX_COMMAND_BYTES)
                .requireSuccess("Office validation")
            val afterHash = stage.sha256()
            journal.dispatched(call, beforeHash, afterHash)
            atomicReplace(stage, destination)
            val success = BuiltInToolResult.text(
                buildJsonObject {
                    put("path", destination.absolutePath)
                    put("sha256", afterHash)
                    put("operations", args.array("operations").size)
                }.toString(),
            )
            journal.finish(call, MutationState.SUCCEEDED, success, beforeHash, afterHash)
            return success
        } catch (error: AtomicMoveNotSupportedException) {
            val failed = BuiltInToolResult.text("Atomic replacement is unavailable on this filesystem", false)
            journal.finish(call, MutationState.FAILED, failed, beforeHash)
            return failed
        } finally {
            stage.delete()
        }
    }

    private fun reconcileDocumentDispatch(
        call: BuiltInToolCall,
        destination: File,
        entry: JournalEntry,
        currentHash: String?,
    ): BuiltInToolResult {
        val (state, result) = when {
            entry.afterHash != null && currentHash == entry.afterHash -> MutationState.SUCCEEDED to
                BuiltInToolResult.text("Document mutation previously completed with SHA-256 ${entry.afterHash}.")
            currentHash == entry.beforeHash -> MutationState.FAILED to
                BuiltInToolResult.text("Document mutation was interrupted before replacement; the destination is unchanged.", false)
            else -> MutationState.INDETERMINATE to
                BuiltInToolResult.text("Document mutation outcome is indeterminate; inspect the destination before continuing.", false)
        }
        journal.finish(call, state, result, entry.beforeHash, entry.afterHash)
        return result
    }

    private fun officeBatch(operations: JsonArray): JsonArray = buildJsonArray {
        require(operations.isNotEmpty() && operations.size <= 50) { "operations must contain 1-50 entries" }
        operations.forEach { raw ->
            val operation = raw.jsonObject
            when (operation.string("type")) {
                "replace_text" -> {
                    operation.requireOnly("type", "elementPath", "oldText", "newText")
                    require(operation.string("oldText").isNotEmpty()) { "oldText must not be empty" }
                    add(batchCommand("set", operation.string("elementPath"), mapOf("text" to operation.string("newText"))))
                }
                "cell_update" -> {
                    operation.requireOnly("type", "sheet", "cell", "value")
                    val sheet = operation.string("sheet")
                    require(SHEET_NAME.matches(sheet)) { "Invalid sheet name" }
                    val cell = operation.string("cell")
                    require(CELL.matches(cell)) { "Invalid cell address" }
                    add(batchCommand("set", "/$sheet/$cell", mapOf("value" to operation["value"]!!)))
                }
                "append_paragraph" -> {
                    operation.requireOnly("type", "parentPath", "text")
                    add(batchCommand("add", operation.string("parentPath", "/body"), mapOf(
                        "type" to JsonPrimitive("paragraph"),
                        "text" to JsonPrimitive(operation.string("text")),
                    )))
                }
                "add_slide" -> {
                    operation.requireOnly("type", "title", "body")
                    val props = linkedMapOf<String, JsonElement>()
                    operation.stringOrNull("title")?.let { props["title"] = JsonPrimitive(it) }
                    operation.stringOrNull("body")?.let { props["body"] = JsonPrimitive(it) }
                    add(batchCommand("add", "/", mapOf("type" to JsonPrimitive("slide")) + props))
                }
                "remove_element" -> {
                    operation.requireOnly("type", "elementPath")
                    add(batchCommand("remove", operation.string("elementPath"), emptyMap()))
                }
                else -> error("Unsupported document edit operation")
            }
        }
    }

    private fun batchCommand(op: String, path: String, values: Map<String, Any>): JsonObject = buildJsonObject {
        require(path.startsWith('/') && path.length <= 512) { "Invalid Office element path" }
        put("op", op)
        put("path", path)
        val type = values["type"] as? JsonPrimitive
        if (type != null) put("type", type)
        val props = values.filterKeys { it != "type" }
        if (props.isNotEmpty()) {
            put(
                "props",
                buildJsonObject {
                    props.forEach { (name, raw) ->
                        put(name, raw as? JsonElement ?: JsonPrimitive(raw.toString()))
                    }
                },
            )
        }
    }

    private fun verifyReplacementPreconditions(file: File, operations: JsonArray) {
        operations.map { it.jsonObject }
            .filter { it.string("type") == "replace_text" }
            .forEach { operation ->
                val path = operation.string("elementPath")
                val current = runBackend(
                    PrivateBackend.OFFICE,
                    listOf("get", file.absolutePath, path, "--depth", "0", "--json"),
                    30,
                    MAX_COMMAND_BYTES,
                ).requireSuccess("Office replacement precondition")
                val expected = operation.string("oldText")
                require(Json.parseToJsonElement(current.output).containsNamedText(expected)) {
                    "Office text changed before replacement"
                }
            }
    }

    private fun telegramListChats(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("query", "limit")
        val command = mutableListOf("--json", "--timeout", "30s", "channels", "list")
        args.stringOrNull("query")?.let { command += listOf("--query", it) }
        command += listOf("--limit", args.int("limit", 20, 1, 50).toString())
        return telegramRead(command, "Telegram chat listing")
    }

    private fun telegramListMessages(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("chat", "limit", "source", "beforeId", "afterId")
        val command = mutableListOf("--json", "--timeout", "45s", "messages", "list", "--chat", args.string("chat"))
        command += listOf("--limit", args.int("limit", 50, 1, 100).toString())
        command += listOf("--source", args.string("source", "both").also { require(it in TELEGRAM_SOURCES) })
        args.longOrNull("beforeId")?.let { command += listOf("--before-id", it.toString()) }
        args.longOrNull("afterId")?.let { command += listOf("--after-id", it.toString()) }
        return telegramRead(command, "Telegram message listing")
    }

    private fun telegramSearchMessages(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("query", "chat", "limit", "source", "after", "before")
        val command = mutableListOf("--json", "--timeout", "45s", "messages", "search", "--query", args.string("query"))
        args.stringOrNull("chat")?.let { command += listOf("--chat", it) }
        command += listOf("--limit", args.int("limit", 50, 1, 100).toString())
        command += listOf("--source", args.string("source", "both").also { require(it in TELEGRAM_SOURCES) })
        args.stringOrNull("after")?.let { command += listOf("--after", it) }
        args.stringOrNull("before")?.let { command += listOf("--before", it) }
        return telegramRead(command, "Telegram message search")
    }

    private fun telegramSearchContacts(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly("query", "limit")
        val command = listOf(
            "--json", "--timeout", "30s", "contacts", "search", args.string("query"),
            "--limit", args.int("limit", 20, 1, 50).toString(),
        )
        return telegramRead(command, "Telegram contact search")
    }

    private fun telegramRead(arguments: List<String>, operation: String): BuiltInToolResult {
        val result = runBackend(PrivateBackend.TELEGRAM, arguments, 55, MAX_TELEGRAM_RESULT_BYTES)
        return if (result.exitCode == 0) BuiltInToolResult.text(result.output)
        else BuiltInToolResult.text(result.failure(operation), false)
    }

    private fun telegramDownloadMedia(call: BuiltInToolCall): BuiltInToolResult {
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        val args = call.arguments.requireOnly("chat", "messageId", "outputPath")
        val destination = workspace.resolveFile(call.workspace, args.string("outputPath"), mustExist = false)
        val stage = File(destination.parentFile, ".${destination.name}.${safeCallId(call.callId)}.download")
        if (existing?.state == MutationState.DISPATCHED) {
            val result = if (stage.isFile && stage.length() > 0) {
                val after = stage.sha256()
                journal.dispatched(call, existing.beforeHash, after)
                atomicReplace(stage, destination)
                BuiltInToolResult.text("Downloaded media to ${destination.absolutePath} (SHA-256 $after).")
            } else {
                BuiltInToolResult.text("Telegram media download outcome is indeterminate; it was not retried.", false)
            }
            journal.finish(
                call,
                if (result.success) MutationState.SUCCEEDED else MutationState.INDETERMINATE,
                result,
                existing.beforeHash,
                destination.takeIf(File::isFile)?.sha256(),
            )
            return result
        }
        require(!destination.exists()) { "Download destination already exists" }
        destination.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        stage.delete()
        journal.dispatched(call)
        val command = listOf(
            "--json", "--timeout", "60s", "media", "download",
            "--chat", args.string("chat"),
            "--id", args.long("messageId", 1).toString(),
            "--output", stage.absolutePath,
        )
        val backend = runBackend(PrivateBackend.TELEGRAM, command, 70, MAX_COMMAND_BYTES)
        if (backend.exitCode == 0 && stage.isFile && stage.length() > 0) {
            val after = stage.sha256()
            journal.dispatched(call, null, after)
            atomicReplace(stage, destination)
            val success = BuiltInToolResult.text("Downloaded media to ${destination.absolutePath} (SHA-256 $after).")
            journal.finish(call, MutationState.SUCCEEDED, success, null, after)
            return success
        }
        val state = if (backend.confirmedFailure) MutationState.FAILED else MutationState.INDETERMINATE
        val failed = BuiltInToolResult.text(
            if (state == MutationState.FAILED) backend.failure("Telegram media download")
            else "Telegram media download outcome is indeterminate; it was not retried.",
            false,
        )
        journal.finish(call, state, failed)
        return failed
    }

    private fun telegramSendText(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly(
            "to", "message", "parseMode", "topic", "replyTo", "silent", "disablePreview",
        )
        val command = mutableListOf(
            "--json", "--timeout", "45s", "send", "text",
            "--to", args.string("to"), "--message", args.string("message"), "--retries", "0",
        )
        args.stringOrNull("parseMode")?.let { require(it in PARSE_MODES); command += listOf("--parse-mode", it) }
        args.longOrNull("topic")?.let { command += listOf("--topic", it.toString()) }
        args.longOrNull("replyTo")?.let { command += listOf("--reply-to", it.toString()) }
        if (args.boolean("silent", false)) command += "--silent"
        if (args.boolean("disablePreview", false)) command += "--no-preview"
        return telegramSend(call, command, "Telegram text send")
    }

    private fun telegramSendFile(call: BuiltInToolCall): BuiltInToolResult {
        val args = call.arguments.requireOnly(
            "to", "path", "caption", "parseMode", "topic", "replyTo", "silent", "forceDocument",
        )
        val file = workspace.resolveFile(call.workspace, args.string("path"), mustExist = true)
        require(file.length() in 1..MAX_TELEGRAM_FILE_BYTES) { "Telegram file is empty or too large" }
        val command = mutableListOf(
            "--json", "--timeout", "120s", "send", "file",
            "--to", args.string("to"), "--file", file.absolutePath, "--retries", "0",
        )
        args.stringOrNull("caption")?.let { command += listOf("--caption", it) }
        args.stringOrNull("parseMode")?.let { require(it in PARSE_MODES); command += listOf("--parse-mode", it) }
        args.longOrNull("topic")?.let { command += listOf("--topic", it.toString()) }
        args.longOrNull("replyTo")?.let { command += listOf("--reply-to", it.toString()) }
        if (args.boolean("silent", false)) command += "--silent"
        if (args.boolean("forceDocument", false)) command += "--force-document"
        return telegramSend(call, command, "Telegram file send")
    }

    private fun telegramSend(call: BuiltInToolCall, arguments: List<String>, operation: String): BuiltInToolResult {
        check(arguments.windowed(2).any { it == listOf("--retries", "0") }) {
            "Telegram sends must disable provider retries"
        }
        val existing = journal.prepare(call)
        replayTerminal(existing)?.let { return it }
        if (existing?.state == MutationState.DISPATCHED) {
            val result = BuiltInToolResult.text("Telegram send outcome is indeterminate; it was not retried.", false)
            journal.finish(call, MutationState.INDETERMINATE, result)
            return result
        }
        journal.dispatched(call)
        val backend = runBackend(PrivateBackend.TELEGRAM, arguments, 130, MAX_TELEGRAM_RESULT_BYTES)
        val state = when {
            backend.exitCode == 0 -> MutationState.SUCCEEDED
            backend.confirmedFailure -> MutationState.FAILED
            else -> MutationState.INDETERMINATE
        }
        val result = when (state) {
            MutationState.SUCCEEDED -> BuiltInToolResult.text(backend.output)
            MutationState.FAILED -> BuiltInToolResult.text(backend.failure(operation), false)
            MutationState.INDETERMINATE -> BuiltInToolResult.text(
                "Telegram send outcome is indeterminate; inspect Telegram before deciding what to do next.",
                false,
            )
            else -> error("Invalid terminal state")
        }
        journal.finish(call, state, result)
        return result
    }

    private fun replayTerminal(entry: JournalEntry?): BuiltInToolResult? = when (entry?.state) {
        MutationState.SUCCEEDED, MutationState.FAILED, MutationState.INDETERMINATE ->
            checkNotNull(entry.result) { "Mutation journal terminal result is missing" }
        else -> null
    }

    private fun immutableSnapshot(source: File): File {
        val hash = source.sha256()
        val snapshot = File(tools.snapshotsDirectory, "$hash.${source.extension.lowercase()}")
        if (!snapshot.isFile) {
            require(source.length() <= MAX_DOCUMENT_BYTES)
            val next = File(snapshot.parentFile, ".${snapshot.name}.next")
            Files.copy(source.toPath(), next.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(next.sha256() == hash) { "Document changed while taking a snapshot" }
            check(next.renameTo(snapshot) || snapshot.isFile) { "Unable to activate document snapshot" }
            next.delete()
            snapshot.setReadOnly()
        }
        return snapshot
    }

    private fun textSnapshotResult(cache: File, offset: Int, maxChars: Int): BuiltInToolResult {
        val value = cache.readText()
        require(offset in 0..value.length) { "Snapshot cursor offset is invalid" }
        val end = minOf(value.length, offset + maxChars)
        val next = if (end < value.length) encodeCursor(cache.name, end) else null
        return BuiltInToolResult.text(
            buildJsonObject {
                put("text", value.substring(offset, end))
                put("totalChars", value.length)
                next?.let { put("cursor", it) }
            }.toString(),
        )
    }

    private fun continueTextSnapshot(cursor: String, expectedName: String, maxChars: Int): BuiltInToolResult {
        require(cursor.length <= 512) { "Snapshot cursor is too long" }
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val name = decoded.substringBefore(':')
        val offset = decoded.substringAfter(':', "").toIntOrNull() ?: error("Snapshot cursor is invalid")
        require(SNAPSHOT_NAME.matches(name)) { "Snapshot cursor is invalid" }
        require(name == expectedName) { "Snapshot cursor does not match this read request" }
        val cache = File(tools.snapshotsDirectory, name)
        require(cache.isFile && cache.canonicalFile.parentFile == tools.snapshotsDirectory.canonicalFile) {
            "Snapshot cursor expired"
        }
        return textSnapshotResult(cache, offset, maxChars)
    }

    private fun encodeCursor(name: String, offset: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$name:$offset".toByteArray(StandardCharsets.UTF_8))

    private fun writeImmutable(file: File, bytes: ByteArray) {
        val next = File(file.parentFile, ".${file.name}.next")
        next.writeBytes(bytes)
        check(next.renameTo(file) || file.isFile) { "Unable to activate private snapshot" }
        next.delete()
        file.setReadOnly()
    }

    private fun stagedSibling(destination: File, callId: String): File {
        val suffix = destination.extension.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
        return File(destination.parentFile, ".${destination.nameWithoutExtension}.${safeCallId(callId)}.stage$suffix")
    }

    private fun safeCallId(value: String): String = sha256(value).take(16)

    private fun atomicReplace(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun dataUrl(extension: String, bytes: ByteArray): String {
        require(bytes.size <= MAX_INLINE_IMAGE_BYTES)
        val mime = when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    private fun runBackend(
        backend: PrivateBackend,
        arguments: List<String>,
        timeoutSeconds: Long,
        maxBytes: Int,
    ): CommandResult {
        val process = tools.startPrivateBackend(backend, arguments)
        val output = ByteArrayOutputStream()
        val exceeded = booleanArrayOf(false)
        val reader = thread(isDaemon = true, name = "private-backend-output") {
            runCatching {
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = process.inputStream.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        if (output.size() + count > maxBytes) {
                            exceeded[0] = true
                            process.destroyForcibly()
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
        reader.join(2_000)
        val text = synchronized(output) { output.toByteArray().toString(StandardCharsets.UTF_8) }
        return CommandResult(
            exitCode = if (finished && !exceeded[0]) runCatching { process.exitValue() }.getOrNull() else null,
            output = text,
            timedOut = !finished,
            exceeded = exceeded[0],
        )
    }

    private data class CommandResult(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean,
        val exceeded: Boolean,
    ) {
        val confirmedFailure: Boolean get() = exitCode != null && exitCode != 0 && output.isNotBlank()

        fun requireSuccess(operation: String): CommandResult {
            check(exitCode == 0) { failure(operation) }
            return this
        }

        fun failure(operation: String): String = when {
            timedOut -> "$operation timed out"
            exceeded -> "$operation exceeded its output limit"
            else -> output.trim().take(4_000).ifBlank { "$operation failed with exit code $exitCode" }
        }
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 100L * 1024 * 1024
        const val MAX_TELEGRAM_FILE_BYTES = 100L * 1024 * 1024
        const val MAX_INLINE_IMAGE_BYTES = 2L * 1024 * 1024
        const val MAX_COMMAND_BYTES = 512 * 1024
        const val MAX_TELEGRAM_RESULT_BYTES = 512 * 1024
        const val MAX_EXTRACTED_BYTES = 2 * 1024 * 1024
        const val MAX_TEXT_RESULT_CHARS = 200_000
        const val MAX_READ_PAGES = 20
        const val MAX_OCR_PAGES = 5
        const val MAX_VIEW_PAGES = 4
        val MODES = setOf("auto", "native", "ocr")
        val REQUESTS = setOf("text", "outline", "stats", "issues", "element")
        val OFFICE_EXTENSIONS = setOf("docx", "xlsx", "pptx")
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        val TELEGRAM_SOURCES = setOf("archive", "live", "both")
        val PARSE_MODES = setOf("none", "markdown", "html")
        val CELL = Regex("^[A-Z]{1,3}[1-9][0-9]{0,6}$")
        val SHEET_NAME = Regex("^[A-Za-z0-9 _-]{1,128}$")
        val SNAPSHOT_NAME = Regex("^[a-f0-9]{64}\\.txt$")
    }
}

private fun JsonObject.requireOnly(vararg allowed: String): JsonObject = apply {
    require(keys.all { it in allowed }) { "Unexpected tool argument" }
}

private fun JsonObject.string(name: String, default: String? = null): String =
    stringOrNull(name) ?: default ?: error("Missing $name")

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.int(name: String, default: Int, minimum: Int, maximum: Int): Int =
    (get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull ?: default).also {
        require(it in minimum..maximum) { "$name is out of range" }
    }

private fun JsonObject.long(name: String, minimum: Long): Long =
    (get(name)?.jsonPrimitive?.longOrNull ?: error("Missing $name")).also {
        require(it >= minimum) { "$name is out of range" }
    }

private fun JsonObject.longOrNull(name: String): Long? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull?.also {
        require(it > 0) { "$name is out of range" }
    }

private fun JsonObject.array(name: String): JsonArray =
    get(name)?.jsonArray ?: error("Missing $name")

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun JsonElement.containsNamedText(expected: String): Boolean = when (this) {
    is JsonObject -> entries.any { (name, value) ->
        name == "text" && value.jsonPrimitive.contentOrNull == expected || value.containsNamedText(expected)
    }
    is JsonArray -> any { it.containsNamedText(expected) }
    else -> false
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
