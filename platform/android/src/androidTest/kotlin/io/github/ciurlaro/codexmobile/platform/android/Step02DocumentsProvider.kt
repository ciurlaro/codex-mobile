package io.github.ciurlaro.codexmobile.platform.android

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class Step02DocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        preferences = checkNotNull(context).getSharedPreferences(PROCESS_DEATH_PREFERENCES, 0)
        preferences?.getString(PERSISTED_SOURCE_NAME, null)?.let { sourceName = it }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_PROJECTION).apply {
            addRow(
                projection ?: ROOT_PROJECTION,
                mapOf(
                    Root.COLUMN_ROOT_ID to ROOT_ID,
                    Root.COLUMN_DOCUMENT_ID to ROOT_ID,
                    Root.COLUMN_TITLE to "Step 02 provider",
                    Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or SUPPORTS_IS_CHILD),
                    Root.COLUMN_ICON to 0,
                ),
            )
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (scenario == Scenario.THROW_QUERY) throw FileNotFoundException("injected")
        val document = documents()[documentId] ?: throw FileNotFoundException("missing")
        return MatrixCursor(projection ?: DOCUMENT_PROJECTION).apply {
            addDocumentRow(document, projection ?: DOCUMENT_PROJECTION)
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (scenario == Scenario.NULL_CHILDREN) return null
        if (scenario == Scenario.THROW_QUERY) throw FileNotFoundException("injected")
        val columns = projection ?: DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply {
            val children = documents().values.filter { it.parentId == parentDocumentId }.toMutableList()
            if (scenario == Scenario.ESCAPE && parentDocumentId == ROOT_ID) {
                children += documents().getValue(FOREIGN_ID)
            }
            if (scenario == Scenario.SELF_CYCLE && parentDocumentId == ROOT_ID) {
                children += documents().getValue(ROOT_ID)
            }
            children.forEach { addDocumentRow(it, columns) }
            if (scenario == Scenario.DUPLICATE && children.isNotEmpty()) {
                addDocumentRow(children.first(), columns)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        OPEN_COUNT.incrementAndGet()
        if (scenario == Scenario.THROW_OPEN) throw IllegalStateException("injected")
        if (scenario == Scenario.DELETE_ON_OPEN) {
            throw FileNotFoundException("injected")
        }
        synchronized(EXPORT_LOCK) {
            EXPORTS[documentId]?.let { export ->
                val descriptorMode = if ('w' in mode) {
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                } else {
                    ParcelFileDescriptor.MODE_READ_ONLY
                }
                return ParcelFileDescriptor.open(export.file, descriptorMode)
            }
        }
        val document = documents().getValue(documentId)
        if (document.isDirectory) throw FileNotFoundException("directory")
        val bytes = document.bytes()
        val returned = if (scenario == Scenario.SHORT_READ && bytes.isNotEmpty()) {
            bytes.copyOf(bytes.size - 1)
        } else {
            bytes
        }
        val pipe = if (scenario == Scenario.STREAM_ERROR) {
            ParcelFileDescriptor.createReliablePipe()
        } else {
            ParcelFileDescriptor.createPipe()
        }
        thread(isDaemon = true, name = "step02-provider") {
            if (scenario == Scenario.STREAM_ERROR) {
                pipe[1].closeWithError("injected")
            } else {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(returned) }
            }
        }
        return pipe[0]
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId != ROOT_ID) throw FileNotFoundException("creation outside root")
        return synchronized(EXPORT_LOCK) {
            val id = "export-${EXPORT_SEQUENCE.incrementAndGet()}"
            val file = File(checkNotNull(context).cacheDir, "step02-$id").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf())
            }
            EXPORTS[id] = ExportRecord(id, displayName, mimeType, file)
            id
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        if (
            documentId != MUTATION_SOURCE_ID && documentId != MUTATION_REPLACEMENT_ID &&
            documentId != MUTATION_SECOND_ID
        ) {
            throw FileNotFoundException("not disposable")
        }
        RENAME_COUNT.incrementAndGet()
        val active = ACTIVE_RENAMES.incrementAndGet()
        MAX_ACTIVE_RENAMES.accumulateAndGet(active, ::maxOf)
        try {
            if (scenario == Scenario.DELAY_RENAME) Thread.sleep(250)
            return synchronized(MUTATION_LOCK) {
                when (scenario) {
                    Scenario.REFUSE_RENAME -> null
                    Scenario.THROW_RENAME -> throw FileNotFoundException("injected")
                    Scenario.PARTIAL_RENAME -> {
                        partialName = displayName
                        MUTATION_VERSION.incrementAndGet()
                        documentId
                    }
                    Scenario.THROW_AFTER_RENAME -> {
                        rename(documentId, displayName)
                        throw FileNotFoundException("injected after mutation")
                    }
                    Scenario.CANCEL_AFTER_RENAME -> {
                        rename(documentId, displayName)
                        throw android.os.OperationCanceledException("injected after mutation")
                    }
                    Scenario.NULL_AFTER_RENAME -> {
                        rename(documentId, displayName)
                        null
                    }
                    Scenario.DELETE_AFTER_DISPATCH -> {
                        if (documentId == MUTATION_SOURCE_ID) sourcePresent = false else secondPresent = false
                        MUTATION_VERSION.incrementAndGet()
                        null
                    }
                    Scenario.PERSIST_RENAME_AND_BLOCK -> {
                        rename(documentId, displayName)
                        check(preferences?.edit()?.putString(PERSISTED_SOURCE_NAME, sourceName)?.commit() == true)
                        Log.i(PROCESS_DEATH_TAG, "dispatch entered:${checkNotNull(processDeathRunId)}")
                        Thread.sleep(PROCESS_DEATH_BLOCK_MILLIS)
                        documentId
                    }
                    else -> {
                        rename(documentId, displayName)
                        documentId
                    }
                }
            }
        } finally {
            ACTIVE_RENAMES.decrementAndGet()
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current = documents()[documentId] ?: return false
        while (current.parentId != null) {
            if (current.parentId == parentDocumentId) return true
            current = documents()[current.parentId] ?: return false
        }
        return false
    }

    private fun MatrixCursor.addDocumentRow(document: TestDocument, columns: Array<out String>) {
        val modified = if (scenario == Scenario.CHANGING_METADATA) {
            CHANGE_COUNTER.incrementAndGet().toLong()
        } else {
            LAST_MODIFIED
        }
        addRow(
            columns,
            mapOf(
                Document.COLUMN_DOCUMENT_ID to document.id,
                Document.COLUMN_DISPLAY_NAME to document.name,
                Document.COLUMN_MIME_TYPE to document.mimeType,
                Document.COLUMN_SIZE to document.size,
                Document.COLUMN_LAST_MODIFIED to if (scenario == Scenario.CHANGING_METADATA) modified else document.lastModified,
                Document.COLUMN_FLAGS to document.flags,
            ),
        )
    }

    private fun MatrixCursor.addRow(columns: Array<out String>, values: Map<String, Any?>) {
        val row = newRow()
        columns.forEach { row.add(it, values[it]) }
    }

    private data class TestDocument(
        val id: String,
        val parentId: String?,
        val name: String,
        val mimeType: String,
        val declaredSize: Long? = null,
        val lastModified: Long = LAST_MODIFIED,
        val flags: Int = 0,
        val content: () -> ByteArray = { byteArrayOf() },
    ) {
        val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
        val size: Long? get() = if (isDirectory) null else declaredSize ?: content().size.toLong()
        fun bytes(): ByteArray = content()
    }

    enum class Scenario {
        NORMAL,
        NULL_CHILDREN,
        DUPLICATE,
        ESCAPE,
        SELF_CYCLE,
        THROW_QUERY,
        SHORT_READ,
        STREAM_ERROR,
        THROW_OPEN,
        DELETE_ON_OPEN,
        CHANGING_METADATA,
        REFUSE_RENAME,
        THROW_RENAME,
        PARTIAL_RENAME,
        THROW_AFTER_RENAME,
        CANCEL_AFTER_RENAME,
        NULL_AFTER_RENAME,
        DELETE_AFTER_DISPATCH,
        DELAY_RENAME,
        PERSIST_RENAME_AND_BLOCK,
    }

    companion object {
        const val AUTHORITY = "io.github.ciurlaro.codexmobile.platform.android.test.documents"
        const val ROOT_ID = "root"
        const val FOREIGN_ID = "foreign"
        const val MUTATION_DIR_ID = "mutation-dir"
        const val MUTATION_SOURCE_ID = "mutation-source"
        const val MUTATION_REPLACEMENT_ID = "mutation-replacement"
        const val MUTATION_SECOND_ID = "mutation-second"
        private const val LAST_MODIFIED = 1_700_000_000_000L
        private const val SUPPORTS_IS_CHILD = 1 shl 4
        private val CHANGE_COUNTER = AtomicInteger()
        private val OPEN_COUNT = AtomicInteger()
        private val RENAME_COUNT = AtomicInteger()
        private val ACTIVE_RENAMES = AtomicInteger()
        private val MAX_ACTIVE_RENAMES = AtomicInteger()
        private val MUTATION_VERSION = AtomicInteger()
        private val EXPORT_SEQUENCE = AtomicInteger()
        private val MUTATION_LOCK = Any()
        private val EXPORT_LOCK = Any()
        private val EXPORTS = LinkedHashMap<String, ExportRecord>()
        @Volatile
        private var preferences: android.content.SharedPreferences? = null
        @Volatile
        private var sourceName = "before.txt"
        @Volatile
        private var sourceId = MUTATION_SOURCE_ID
        @Volatile
        private var secondName = "second.txt"
        @Volatile
        private var sourcePresent = true
        @Volatile
        private var secondPresent = true
        @Volatile
        private var partialName: String? = null
        @Volatile
        var scenario = Scenario.NORMAL
        @Volatile
        var processDeathRunId: String? = null

        fun reset() {
            scenario = Scenario.NORMAL
            processDeathRunId = null
            preferences?.edit()?.clear()?.commit()
            CHANGE_COUNTER.set(0)
            OPEN_COUNT.set(0)
            RENAME_COUNT.set(0)
            ACTIVE_RENAMES.set(0)
            MAX_ACTIVE_RENAMES.set(0)
            MUTATION_VERSION.set(0)
            sourceName = "before.txt"
            sourceId = MUTATION_SOURCE_ID
            secondName = "second.txt"
            sourcePresent = true
            secondPresent = true
            partialName = null
            synchronized(EXPORT_LOCK) {
                EXPORTS.values.forEach { it.file.delete() }
                EXPORTS.clear()
                EXPORT_SEQUENCE.set(0)
            }
        }

        fun openCount(): Int = OPEN_COUNT.get()

        fun renameCount(): Int = RENAME_COUNT.get()

        fun maxActiveRenames(): Int = MAX_ACTIVE_RENAMES.get()

        fun activeRenames(): Int = ACTIVE_RENAMES.get()

        fun mutationNames(): List<String> = documents().values
            .filter { it.parentId == MUTATION_DIR_ID }
            .map { it.name }

        fun changeMutationSource(name: String = sourceName) = synchronized(MUTATION_LOCK) {
            sourceName = name
            MUTATION_VERSION.incrementAndGet()
        }

        fun exportedText(name: String): String? = synchronized(EXPORT_LOCK) {
            EXPORTS.values.singleOrNull { it.name == name }?.file?.readText()
        }

        fun replaceExportText(name: String, content: String) = synchronized(EXPORT_LOCK) {
            EXPORTS.values.single { it.name == name }.file.writeText(content)
        }

        fun removeMutationSource() = synchronized(MUTATION_LOCK) {
            sourcePresent = false
            MUTATION_VERSION.incrementAndGet()
        }

        fun replaceMutationSource() = synchronized(MUTATION_LOCK) {
            sourceId = MUTATION_REPLACEMENT_ID
            MUTATION_VERSION.incrementAndGet()
        }

        private fun rename(documentId: String, displayName: String) {
            if (documentId == sourceId) sourceName = displayName else secondName = displayName
            MUTATION_VERSION.incrementAndGet()
        }

        private fun documents(): Map<String, TestDocument> = buildMap {
            put(
                ROOT_ID,
                TestDocument(
                    ROOT_ID,
                    null,
                    "Root",
                    Document.MIME_TYPE_DIR,
                    flags = Document.FLAG_DIR_SUPPORTS_CREATE,
                ),
            )
            put("empty-dir", TestDocument("empty-dir", ROOT_ID, "empty", Document.MIME_TYPE_DIR))
            put("nested-dir", TestDocument("nested-dir", ROOT_ID, "nested", Document.MIME_TYPE_DIR))
            put("large-dir", TestDocument("large-dir", ROOT_ID, "large", Document.MIME_TYPE_DIR))
            put(MUTATION_DIR_ID, TestDocument(MUTATION_DIR_ID, ROOT_ID, "mutation", Document.MIME_TYPE_DIR))
            put("empty-text", TestDocument("empty-text", ROOT_ID, "empty.txt", "text/plain"))
            put("long-name", TestDocument("long-name", ROOT_ID, "l".repeat(4_096), "text/plain"))
            put(
                "unicode-text",
                TestDocument("unicode-text", ROOT_ID, "עברית/emoji😀.txt", "text/plain") {
                    "Grüezi 👋\n第二行".toByteArray()
                },
            )
            put(
                "binary",
                TestDocument("binary", ROOT_ID, "binary.bin", "application/octet-stream") {
                    byteArrayOf(0, 1, 2, 3)
                },
            )
            put(
                "invalid-utf8",
                TestDocument("invalid-utf8", ROOT_ID, "invalid-utf8.txt", "text/plain") {
                    byteArrayOf(0xc3.toByte(), 0x28)
                },
            )
            put(
                "misleading-mime",
                TestDocument("misleading-mime", ROOT_ID, "actually-text.pdf", "application/pdf") {
                    "content wins over MIME".toByteArray()
                },
            )
            put(
                "docx",
                TestDocument(
                    "docx",
                    ROOT_ID,
                    "sample.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ) { docxBytes() },
            )
            put(
                "oversized",
                TestDocument("oversized", ROOT_ID, "oversized.txt", "text/plain", declaredSize = 65_537) {
                    ByteArray(65_537) { 'x'.code.toByte() }
                },
            )
            put(
                "nested-text",
                TestDocument("nested-text", "nested-dir", ".dots..txt", "application/json") {
                    "{}".toByteArray()
                },
            )
            val mutationModified = LAST_MODIFIED + MUTATION_VERSION.get()
            if (sourcePresent) {
                put(
                    sourceId,
                    TestDocument(
                        sourceId,
                        MUTATION_DIR_ID,
                        sourceName,
                        "text/plain",
                        lastModified = mutationModified,
                        flags = Document.FLAG_SUPPORTS_RENAME,
                    ),
                )
            }
            if (secondPresent) {
                put(
                    MUTATION_SECOND_ID,
                    TestDocument(
                        MUTATION_SECOND_ID,
                        MUTATION_DIR_ID,
                        secondName,
                        "text/plain",
                        lastModified = mutationModified,
                        flags = Document.FLAG_SUPPORTS_RENAME,
                    ),
                )
            }
            put(
                "mutation-conflict",
                TestDocument("mutation-conflict", MUTATION_DIR_ID, "taken.txt", "text/plain"),
            )
            partialName?.let { name ->
                put(
                    "mutation-partial",
                    TestDocument("mutation-partial", MUTATION_DIR_ID, name, "text/plain", lastModified = mutationModified),
                )
            }
            repeat(300) { index ->
                val id = "large-$index"
                put(id, TestDocument(id, "large-dir", "entry-${index.toString().padStart(3, '0')}", "text/plain"))
            }
            put(
                FOREIGN_ID,
                TestDocument(FOREIGN_ID, null, "foreign.txt", "text/plain") { "outside".toByteArray() },
            )
            synchronized(EXPORT_LOCK) {
                EXPORTS.values.forEach { export ->
                    put(
                        export.id,
                        TestDocument(
                            export.id,
                            ROOT_ID,
                            export.name,
                            export.mimeType,
                            lastModified = export.file.lastModified(),
                            flags = Document.FLAG_SUPPORTS_WRITE,
                        ) { export.file.readBytes() },
                    )
                }
            }
        }

        private data class ExportRecord(
            val id: String,
            val name: String,
            val mimeType: String,
            val file: File,
        )

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
        private const val PROCESS_DEATH_PREFERENCES = "step04_provider_death"
        private const val PERSISTED_SOURCE_NAME = "source_name"
        private const val PROCESS_DEATH_TAG = "CodexMobileStep04Dispatch"
        private const val PROCESS_DEATH_BLOCK_MILLIS = 120_000L

        private fun docxBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                entry(
                    "[Content_Types].xml",
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                        "<Override ContentType=\"application/vnd.openxmlformats-officedocument." +
                        "wordprocessingml.document.main+xml\"/></Types>",
                )
                entry(
                    "word/document.xml",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                        "<w:body><w:p><w:r><w:t>Hello DOCX</w:t></w:r></w:p></w:body></w:document>",
                )
            }
        }.toByteArray()
    }
}
