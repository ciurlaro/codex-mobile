package io.github.ciurlaro.codexmobile.platform.android

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

internal data class DocumentRead(
    val format: String,
    val sha256: String,
    val text: String,
    val position: Int,
    val nextPosition: Int?,
    val imageUrls: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

internal class DocumentReadFailure(val code: String, message: String) : Exception(message)

/** Content-signature reader used by read_document. Add formats here, not in the tool. */
internal class DocumentReader(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun read(
        uri: Uri,
        declaredSize: Long?,
        position: Int,
    ): DocumentRead = withContext(Dispatchers.IO) {
        if (declaredSize != null && declaredSize > MAX_SOURCE_BYTES) {
            throw DocumentReadFailure("document_too_large", "Document exceeds the 50 MiB read limit")
        }
        val bytes = resolver.openInputStream(uri)?.use(::readBounded)
            ?: throw DocumentReadFailure("open_failed", "Document provider did not open the document")
        if (declaredSize != null && declaredSize != bytes.size.toLong()) {
            throw DocumentReadFailure("size_mismatch", "Document ended before its declared size")
        }
        val sha256 = bytes.sha256()
        when {
            bytes.startsWith(PDF_MAGIC) -> readPdf(bytes, sha256, position)
            bytes.startsWith(ZIP_MAGIC) -> readOoxml(bytes, sha256, position)
            bytes.hasImageSignature() -> readImage(bytes, sha256, position)
            else -> readText(bytes, sha256, position)
        }
    }

    private suspend fun readPdf(bytes: ByteArray, sha256: String, position: Int): DocumentRead {
        val temporary = File.createTempFile("codex-document-", ".pdf", appContext.cacheDir)
        try {
            temporary.outputStream().use { it.write(bytes) }
            val descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount > MAX_PDF_PAGES) {
                    throw DocumentReadFailure("document_too_large", "PDF exceeds the 500 page limit")
                }
                if (position !in 0 until renderer.pageCount) {
                    throw DocumentReadFailure("cursor_invalid", "PDF cursor is outside the document")
                }
                renderer.openPage(position).use { page ->
                    val bitmap = renderPage(page)
                    try {
                        val exact = if (hasExactPdfText()) exactPdfText(page) else ""
                        val warnings = mutableListOf<String>()
                        val text = if (exact.isNotBlank()) exact else {
                            warnings += "ocr_used"
                            recognize(bitmap).getOrElse {
                                warnings += "ocr_failed"
                                ""
                            }
                        }
                        return DocumentRead(
                            format = "pdf",
                            sha256 = sha256,
                            text = text,
                            position = position,
                            nextPosition = (position + 1).takeIf { it < renderer.pageCount },
                            imageUrls = listOf(bitmap.toDataUrl()),
                            warnings = warnings,
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (error: DocumentReadFailure) {
            throw error
        } catch (_: SecurityException) {
            throw DocumentReadFailure("encrypted_document", "Encrypted PDFs are not supported")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw DocumentReadFailure("corrupt_document", "PDF is corrupt or unsupported")
        } finally {
            temporary.delete()
        }
    }

    @Suppress("NewApi")
    private fun exactPdfText(page: PdfRenderer.Page): String =
        page.textContents.joinToString("\n") { it.text }.trim()

    private fun hasExactPdfText(): Boolean = Build.VERSION.SDK_INT >= 35 ||
        Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

    private suspend fun readImage(bytes: ByteArray, sha256: String, position: Int): DocumentRead {
        if (position != 0) throw DocumentReadFailure("cursor_invalid", "Image cursor is invalid")
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw DocumentReadFailure("corrupt_document", "Image is corrupt or unsupported")
        val bitmap = decoded.bounded()
        if (bitmap !== decoded) decoded.recycle()
        try {
            val warnings = mutableListOf<String>()
            val text = recognize(bitmap).getOrElse {
                warnings += "ocr_failed"
                ""
            }
            return DocumentRead(
                format = "image",
                sha256 = sha256,
                text = text,
                position = 0,
                nextPosition = null,
                imageUrls = listOf(bitmap.toDataUrl()),
                warnings = warnings,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun readText(bytes: ByteArray, sha256: String, position: Int): DocumentRead {
        val text = bytes.strictUtf8()
            ?: throw DocumentReadFailure("unsupported_format", "Document format is not supported")
        if (text.indexOf('\u0000') >= 0 || text.count { it.isISOControl() && it !in "\n\r\t" } > 16) {
            throw DocumentReadFailure("unsupported_format", "Document is binary, not UTF-8 text")
        }
        return chunk(if (looksLikeCsv(text)) "csv" else "text", sha256, text, position)
    }

    private fun readOoxml(bytes: ByteArray, sha256: String, position: Int): DocumentRead {
        val entries = unzip(bytes)
        val contentTypes = entries["[Content_Types].xml"]?.strictUtf8()
            ?: throw DocumentReadFailure("unsupported_format", "ZIP document is not a registered Office format")
        if ("macroEnabled" in contentTypes) {
            throw DocumentReadFailure("unsupported_format", "Macro-enabled Office documents are not supported")
        }
        val (format, text) = when {
            "wordprocessingml.document" in contentTypes -> "docx" to extractDocx(entries)
            "presentationml.presentation" in contentTypes -> "pptx" to extractPptx(entries)
            "spreadsheetml.sheet" in contentTypes -> "xlsx" to extractXlsx(entries)
            else -> throw DocumentReadFailure(
                "unsupported_format",
                "ZIP document is not DOCX, PPTX, or XLSX",
            )
        }
        return chunk(format, sha256, text, position)
    }

    private fun extractDocx(entries: Map<String, ByteArray>): String = buildString {
        val names = entries.keys.filter {
            it == "word/document.xml" || it.matches(Regex("word/(header|footer)\\d+\\.xml")) ||
                it in setOf("word/footnotes.xml", "word/endnotes.xml")
        }.sortedWith(compareBy<String> { it != "word/document.xml" }.thenBy { it })
        names.forEach { name ->
            if (isNotEmpty()) append("\n\n")
            append(xmlText(entries.getValue(name), paragraphTags = setOf("p", "tr")))
        }
    }.trim()

    private fun extractPptx(entries: Map<String, ByteArray>): String = buildString {
        val slides = entries.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy(::trailingNumber)
        slides.forEachIndexed { index, name ->
            if (isNotEmpty()) append("\n\n")
            append("# Slide ").append(index + 1).append('\n')
            append(xmlText(entries.getValue(name), paragraphTags = setOf("p")))
            entries["ppt/notesSlides/notesSlide${index + 1}.xml"]?.let { notes ->
                val noteText = xmlText(notes, paragraphTags = setOf("p"))
                if (noteText.isNotBlank()) append("\nNotes:\n").append(noteText)
            }
        }
    }.trim()

    private fun extractXlsx(entries: Map<String, ByteArray>): String {
        val shared = entries["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        return buildString {
            val sheets = entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy(::trailingNumber)
            sheets.forEachIndexed { sheetIndex, name ->
                if (isNotEmpty()) append("\n\n")
                append("# Sheet ").append(sheetIndex + 1).append('\n')
                val document = parseXml(entries.getValue(name))
                val rows = document.getElementsByTagNameNS("*", "row")
                repeat(rows.length) { rowIndex ->
                    val row = rows.item(rowIndex) as? Element ?: return@repeat
                    val cells = row.getElementsByTagNameNS("*", "c")
                    val values = ArrayList<String>(cells.length)
                    repeat(cells.length) { cellIndex ->
                        val cell = cells.item(cellIndex) as Element
                        val formula = cell.firstText("f")
                        val raw = cell.firstText("v")
                        val inline = cell.firstText("t")
                        val value = when (cell.getAttribute("t")) {
                            "s" -> raw.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                            "inlineStr" -> inline
                            else -> raw.ifEmpty { inline }
                        }
                        values += if (formula.isEmpty()) value else "=$formula [$value]"
                    }
                    append(values.joinToString("\t")).append('\n')
                }
            }
        }.trim()
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val document = parseXml(bytes)
        val strings = document.getElementsByTagNameNS("*", "si")
        return List(strings.length) { index ->
            val item = strings.item(index)
            buildString { collectTextNodes(item, this) }
        }
    }

    private fun xmlText(bytes: ByteArray, paragraphTags: Set<String>): String {
        val document = parseXml(bytes)
        return buildString { appendXml(document.documentElement, this, paragraphTags) }
            .lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n")
    }

    private fun appendXml(node: Node, output: StringBuilder, paragraphTags: Set<String>) {
        if (node.nodeType == Node.ELEMENT_NODE && node.localName == "t") output.append(node.textContent)
        if (node.nodeType == Node.ELEMENT_NODE && node.localName == "tab") output.append('\t')
        var child = node.firstChild
        while (child != null) {
            appendXml(child, output, paragraphTags)
            child = child.nextSibling
        }
        if (node.nodeType == Node.ELEMENT_NODE && node.localName in paragraphTags) output.append('\n')
        if (node.nodeType == Node.ELEMENT_NODE && node.localName == "tc") output.append('\t')
    }

    private fun collectTextNodes(node: Node, output: StringBuilder) {
        if (node.nodeType == Node.ELEMENT_NODE && node.localName == "t") output.append(node.textContent)
        var child = node.firstChild
        while (child != null) {
            collectTextNodes(child, output)
            child = child.nextSibling
        }
    }

    private fun parseXml(bytes: ByteArray) = try {
        if (bytes.containsAsciiIgnoreCase("<!DOCTYPE")) {
            throw DocumentReadFailure("corrupt_document", "Office document contains a forbidden document type")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { setExpandEntityReferences(false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (error: DocumentReadFailure) {
        throw error
    } catch (_: Exception) {
        throw DocumentReadFailure("corrupt_document", "Office document contains invalid XML")
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var expandedBytes = 0L
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entryCount > MAX_ZIP_ENTRIES || entry.name in entries) {
                    if (entry.name in entries) {
                        throw DocumentReadFailure("corrupt_document", "Office document repeats an entry")
                    }
                    throw DocumentReadFailure("document_too_large", "Office document exceeds expansion limits")
                }
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expandedBytes += count
                        if (expandedBytes > MAX_ZIP_EXPANDED_BYTES) {
                            throw DocumentReadFailure("document_too_large", "Office document exceeds expansion limits")
                        }
                        output.write(buffer, 0, count)
                    }
                    entries[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun chunk(format: String, sha256: String, text: String, position: Int): DocumentRead {
        if (position !in 0..text.length) {
            throw DocumentReadFailure("cursor_invalid", "Document cursor is outside the text")
        }
        if (position == text.length) {
            return DocumentRead(format, sha256, "", position, null)
        }
        var low = position + 1
        var high = minOf(text.length, position + MAX_TEXT_CHARS)
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            val end = if (middle < text.length && text[middle - 1].isHighSurrogate()) middle - 1 else middle
            if (text.substring(position, end).toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_BYTES) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        var end = low
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        return DocumentRead(
            format = format,
            sha256 = sha256,
            text = text.substring(position, end),
            position = position,
            nextPosition = end.takeIf { it < text.length },
        )
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(page.width, page.height))
        val width = maxOf(1, (page.width * scale).roundToInt())
        val height = maxOf(1, (page.height * scale).roundToInt())
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    private fun Bitmap.bounded(): Bitmap {
        val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(width, height))
        if (scale == 1f) return this
        return Bitmap.createScaledBitmap(
            this,
            maxOf(1, (width * scale).roundToInt()),
            maxOf(1, (height * scale).roundToInt()),
            true,
        )
    }

    private fun Bitmap.toDataUrl(): String {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        val bytes = output.toByteArray()
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw DocumentReadFailure("image_too_large", "Rendered image exceeds the model input limit")
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private suspend fun recognize(bitmap: Bitmap): Result<String> = runCatching {
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var emptyReads = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                if (++emptyReads > MAX_EMPTY_READS) {
                    throw DocumentReadFailure("stream_stalled", "Document stream stopped making progress")
                }
                continue
            }
            emptyReads = 0
            if (output.size().toLong() + count > MAX_SOURCE_BYTES) {
                throw DocumentReadFailure("document_too_large", "Document exceeds the 50 MiB read limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ByteArray.strictUtf8(): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.containsAsciiIgnoreCase(value: String): Boolean {
        val target = value.uppercase().toByteArray(StandardCharsets.US_ASCII)
        outer@ for (start in 0..size - target.size) {
            for (offset in target.indices) {
                if (
                    this[start + offset].toInt().and(0xFF).toChar().uppercaseChar().code !=
                    target[offset].toInt()
                ) {
                    continue@outer
                }
            }
            return true
        }
        return false
    }

    private fun ByteArray.hasImageSignature(): Boolean =
        startsWith(JPEG_MAGIC) || startsWith(PNG_MAGIC) || startsWith(GIF87_MAGIC) ||
            startsWith(GIF89_MAGIC) || startsWith(BMP_MAGIC) ||
            size >= 12 && startsWith(WEBP_MAGIC) &&
            String(this, 8, 4, StandardCharsets.US_ASCII) == "WEBP" ||
            size >= 12 && String(this, 4, 4, StandardCharsets.US_ASCII) == "ftyp" &&
            String(this, 8, 4, StandardCharsets.US_ASCII) in ISO_IMAGE_BRANDS

    private fun looksLikeCsv(text: String): Boolean {
        val lines = text.lineSequence().filter(String::isNotBlank).take(5).toList()
        if (lines.size < 2) return false
        return listOf(',', '\t', ';').any { delimiter ->
            val counts = lines.map { line -> line.count { it == delimiter } }
            counts.first() > 0 && counts.distinct().size == 1
        }
    }

    private fun trailingNumber(name: String): Int =
        Regex("(\\d+)(?=\\.xml$)").find(name)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    private fun Element.firstText(localName: String): String =
        getElementsByTagNameNS("*", localName).item(0)?.textContent.orEmpty()

    private companion object {
        const val MAX_SOURCE_BYTES = 50L * 1024 * 1024
        const val MAX_ZIP_EXPANDED_BYTES = 100L * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_PDF_PAGES = 500
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_TEXT_CHARS = 64 * 1024
        const val MAX_IMAGE_EDGE = 1_600
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val JPEG_QUALITY = 85
        const val MAX_EMPTY_READS = 16
        val PDF_MAGIC = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val GIF87_MAGIC = "GIF87a".toByteArray(StandardCharsets.US_ASCII)
        val GIF89_MAGIC = "GIF89a".toByteArray(StandardCharsets.US_ASCII)
        val WEBP_MAGIC = "RIFF".toByteArray(StandardCharsets.US_ASCII)
        val BMP_MAGIC = "BM".toByteArray(StandardCharsets.US_ASCII)
        val ISO_IMAGE_BRANDS = setOf("avif", "avis", "heic", "heix", "hevc", "hevx", "mif1", "msf1")
    }
}
