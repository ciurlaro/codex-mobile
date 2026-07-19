package io.github.ciurlaro.codexmobile.platform.android

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Step02DocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

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
        val document = documents().getValue(documentId)
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
                Document.COLUMN_LAST_MODIFIED to modified,
                Document.COLUMN_FLAGS to 0,
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
    }

    companion object {
        const val AUTHORITY = "io.github.ciurlaro.codexmobile.platform.android.test.documents"
        const val ROOT_ID = "root"
        const val FOREIGN_ID = "foreign"
        private const val LAST_MODIFIED = 1_700_000_000_000L
        private const val SUPPORTS_IS_CHILD = 1 shl 4
        private val CHANGE_COUNTER = AtomicInteger()
        private val OPEN_COUNT = AtomicInteger()
        @Volatile
        var scenario = Scenario.NORMAL

        fun reset() {
            scenario = Scenario.NORMAL
            CHANGE_COUNTER.set(0)
            OPEN_COUNT.set(0)
        }

        fun openCount(): Int = OPEN_COUNT.get()

        private fun documents(): Map<String, TestDocument> = buildMap {
            put(ROOT_ID, TestDocument(ROOT_ID, null, "Root", Document.MIME_TYPE_DIR))
            put("empty-dir", TestDocument("empty-dir", ROOT_ID, "empty", Document.MIME_TYPE_DIR))
            put("nested-dir", TestDocument("nested-dir", ROOT_ID, "nested", Document.MIME_TYPE_DIR))
            put("large-dir", TestDocument("large-dir", ROOT_ID, "large", Document.MIME_TYPE_DIR))
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
            repeat(300) { index ->
                val id = "large-$index"
                put(id, TestDocument(id, "large-dir", "entry-${index.toString().padStart(3, '0')}", "text/plain"))
            }
            put(
                FOREIGN_ID,
                TestDocument(FOREIGN_ID, null, "foreign.txt", "text/plain") { "outside".toByteArray() },
            )
        }

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
    }
}
