package io.github.ciurlaro.codexmobile.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.MediaStore
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.app.ui.shell.VisualScenario
import io.github.ciurlaro.codexmobile.app.ui.shell.VisualScenarioActivity
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun appOwnedScenariosMatchReviewedBaselines() {
        VisualRegressionConfig.requireCanonicalDevice(context)
        val captureOnly = InstrumentationRegistry.getArguments()
            .getString("captureOnly")
            .toBoolean()
        val failures = mutableListOf<String>()

        VisualScenario.entries.forEach { scenario ->
            runCatching { captureAndCheck(scenario, captureOnly) }.exceptionOrNull()?.let {
                if (!captureOnly) publishForReview("failures", scenario.id)
                failures += "${scenario.id}: ${it.message ?: it::class.java.simpleName}"
            }
        }

        assertTrue(failures.joinToString(prefix = "Visual regression failures:\n", separator = "\n"), failures.isEmpty())
    }

    private fun captureAndCheck(scenario: VisualScenario, captureOnly: Boolean) {
        val intent = Intent(context, VisualScenarioActivity::class.java)
            .putExtra(VisualScenarioActivity.EXTRA_SCENARIO, scenario.id)
        val activity = ActivityScenario.launch<VisualScenarioActivity>(intent)
        val screenshot = try {
            takeStableScreenshot(activity)
        } finally {
            activity.close()
        }
        val actual = VisualRegressionConfig.crop(screenshot)
        if (captureOnly) {
            val candidate = File(outputDirectory("candidates"), "${scenario.id}.png")
            actual.writePng(candidate)
            publishForReview("candidates", scenario.id)
            return
        }

        val baseline = loadBaseline(scenario)
        VisualComparator.compare(
            scenario = scenario,
            actual = actual,
            expected = baseline,
            outputDirectory = outputDirectory("failures"),
        )
    }

    private fun takeStableScreenshot(
        activity: ActivityScenario<VisualScenarioActivity>,
    ): Bitmap {
        var previous: Bitmap? = null
        repeat(12) { attempt ->
            instrumentation.waitForIdleSync()
            SystemClock.sleep(if (attempt == 0) 1_000 else 300)
            val current = copyAppWindow(activity)
            if (previous?.visuallyMatches(current) == true) {
                previous.recycle()
                return current
            }
            previous?.recycle()
            previous = current
        }
        previous?.recycle()
        error("App-owned pixels did not settle to two consecutive matching frames")
    }

    private fun copyAppWindow(
        activity: ActivityScenario<VisualScenarioActivity>,
    ): Bitmap {
        val copied = CountDownLatch(1)
        val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
        lateinit var bitmap: Bitmap
        activity.onActivity { host ->
            val decor = host.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                host.window,
                bitmap,
                { status -> result.set(status); copied.countDown() },
                Handler(Looper.getMainLooper()),
            )
        }
        check(copied.await(5, TimeUnit.SECONDS)) { "App window capture timed out" }
        check(result.get() == PixelCopy.SUCCESS) { "App window capture failed: ${result.get()}" }
        return bitmap
    }

    private fun loadBaseline(scenario: VisualScenario): Bitmap? = try {
        instrumentation.context.assets.open("visual-baselines/${scenario.id}.png").use { input ->
            checkNotNull(BitmapFactory.decodeStream(input)) { "Unreadable baseline for ${scenario.id}" }
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun outputDirectory(kind: String): File {
        val base = checkNotNull(context.getExternalFilesDir("visual-regression")) {
            "External test output directory is unavailable"
        }
        return File(base, kind).also { check(it.isDirectory || it.mkdirs()) }
    }

    private fun publishForReview(kind: String, scenarioId: String) {
        val files = outputDirectory(kind).listFiles().orEmpty().filter { file ->
            file.name == "$scenarioId.png" || file.name.startsWith("$scenarioId-")
        }
        if (files.isEmpty()) return
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/codex-mobile-visual-$kind/"
        files.forEach { file ->
            context.contentResolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(file.name, relativePath),
            )
            val pending = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(context.contentResolver.insert(collection, pending)) {
                "Could not publish ${file.name} for review"
            }
            try {
                checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (error: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        println("Visual $kind for review: $relativePath")
    }
}

private object VisualRegressionConfig {
    private const val EXPECTED_API = 37
    private const val EXPECTED_WIDTH_PX = 1080
    private const val EXPECTED_HEIGHT_PX = 2400
    private const val EXPECTED_DENSITY_DPI = 420
    private const val EXPECTED_LANGUAGE_TAG = "en-US"

    const val channelTolerance = 3
    const val allowedDifferentPixelRatio = 0.001

    private val crop = PixelInsets(left = 0, top = 0, right = 0, bottom = 0)

    // PixelCopy excludes System UI; these masks reserve the transparent inset glyph zones.
    private val masks = listOf(
        NormalizedRect(left = 0.00, top = 0.00, right = 0.30, bottom = 0.04),
        NormalizedRect(left = 0.60, top = 0.00, right = 1.00, bottom = 0.04),
        NormalizedRect(left = 0.34, top = 0.965, right = 0.66, bottom = 1.00),
    )

    fun requireCanonicalDevice(context: Context) {
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val languageTag = configuration.locales[0].toLanguageTag()
        check(Build.VERSION.SDK_INT == EXPECTED_API) {
            "Use the canonical API $EXPECTED_API emulator; found API ${Build.VERSION.SDK_INT}"
        }
        check(metrics.widthPixels == EXPECTED_WIDTH_PX && metrics.heightPixels == EXPECTED_HEIGHT_PX) {
            "Use ${EXPECTED_WIDTH_PX}x$EXPECTED_HEIGHT_PX; found ${metrics.widthPixels}x${metrics.heightPixels}"
        }
        check(metrics.densityDpi == EXPECTED_DENSITY_DPI) {
            "Use density $EXPECTED_DENSITY_DPI dpi; found ${metrics.densityDpi} dpi"
        }
        check(abs(configuration.fontScale - 1f) < 0.001f) {
            "Use font scale 1.0; found ${configuration.fontScale}"
        }
        check(languageTag == EXPECTED_LANGUAGE_TAG) {
            "Use locale $EXPECTED_LANGUAGE_TAG; found $languageTag"
        }
        listOf(
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        ).forEach { setting ->
            val scale = Settings.Global.getFloat(context.contentResolver, setting, 1f)
            check(abs(scale) < 0.001f) { "Disable $setting on the canonical emulator; found $scale" }
        }
    }

    fun crop(bitmap: Bitmap): Bitmap = Bitmap.createBitmap(
        bitmap,
        crop.left,
        crop.top,
        bitmap.width - crop.left - crop.right,
        bitmap.height - crop.top - crop.bottom,
    )

    fun isMasked(x: Int, y: Int, width: Int, height: Int): Boolean =
        masks.any { it.contains(x, y, width, height) }
}

private object VisualComparator {
    fun compare(
        scenario: VisualScenario,
        actual: Bitmap,
        expected: Bitmap?,
        outputDirectory: File,
    ) {
        val baseline = expected ?: Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
        if (expected == null) {
            writeArtifacts(scenario, actual, baseline, outputDirectory)
            error("Reviewed baseline is missing; candidate and diagnostic artifacts: ${outputDirectory.absolutePath}")
        }
        if (actual.width != expected.width || actual.height != expected.height) {
            val scaled = Bitmap.createScaledBitmap(expected, actual.width, actual.height, true)
            writeArtifacts(scenario, actual, scaled, outputDirectory)
            error(
                "Expected ${expected.width}x${expected.height}, found ${actual.width}x${actual.height}; " +
                    "artifacts: ${outputDirectory.absolutePath}",
            )
        }

        val actualPixels = actual.readPixels()
        val expectedPixels = expected.readPixels()
        var compared = 0
        var different = 0
        actualPixels.indices.forEach { index ->
            val x = index % actual.width
            val y = index / actual.width
            if (!VisualRegressionConfig.isMasked(x, y, actual.width, actual.height)) {
                compared++
                if (colorDelta(actualPixels[index], expectedPixels[index]) >
                    VisualRegressionConfig.channelTolerance
                ) {
                    different++
                }
            }
        }
        val ratio = different.toDouble() / compared
        if (ratio > VisualRegressionConfig.allowedDifferentPixelRatio) {
            writeArtifacts(scenario, actual, expected, outputDirectory)
            error(
                "${"%.4f".format(Locale.US, ratio * 100)}% pixels differ " +
                    "(limit ${VisualRegressionConfig.allowedDifferentPixelRatio * 100}%); " +
                    "artifacts: ${outputDirectory.absolutePath}",
            )
        }
    }

    private fun writeArtifacts(
        scenario: VisualScenario,
        actual: Bitmap,
        expected: Bitmap,
        outputDirectory: File,
    ) {
        actual.writePng(File(outputDirectory, "${scenario.id}-actual.png"))
        val actualPixels = actual.readPixels()
        val expectedPixels = expected.readPixels()
        val overlayPixels = IntArray(actualPixels.size)
        val diffPixels = IntArray(actualPixels.size)
        actualPixels.indices.forEach { index ->
            val x = index % actual.width
            val y = index / actual.width
            val masked = VisualRegressionConfig.isMasked(x, y, actual.width, actual.height)
            overlayPixels[index] = if (masked) actualPixels[index] else blend(actualPixels[index], expectedPixels[index])
            val delta = colorDelta(actualPixels[index], expectedPixels[index])
            diffPixels[index] = if (masked || delta <= VisualRegressionConfig.channelTolerance) {
                Color.TRANSPARENT
            } else {
                Color.rgb(255, max(0, 255 - delta * 3), 0)
            }
        }
        Bitmap.createBitmap(overlayPixels, actual.width, actual.height, Bitmap.Config.ARGB_8888)
            .writePng(File(outputDirectory, "${scenario.id}-overlay.png"))
        Bitmap.createBitmap(diffPixels, actual.width, actual.height, Bitmap.Config.ARGB_8888)
            .writePng(File(outputDirectory, "${scenario.id}-diff.png"))
    }

    private fun blend(first: Int, second: Int): Int = Color.rgb(
        (Color.red(first) + Color.red(second)) / 2,
        (Color.green(first) + Color.green(second)) / 2,
        (Color.blue(first) + Color.blue(second)) / 2,
    )

    fun colorDelta(first: Int, second: Int): Int = maxOf(
        abs(Color.red(first) - Color.red(second)),
        abs(Color.green(first) - Color.green(second)),
        abs(Color.blue(first) - Color.blue(second)),
    )
}

private data class PixelInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

private data class NormalizedRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun contains(x: Int, y: Int, width: Int, height: Int): Boolean =
        x >= width * left && x < width * right && y >= height * top && y < height * bottom
}

private fun Bitmap.readPixels(): IntArray = IntArray(width * height).also {
    getPixels(it, 0, width, 0, 0, width, height)
}

private fun Bitmap.visuallyMatches(other: Bitmap): Boolean {
    if (width != other.width || height != other.height) return false
    val first = readPixels()
    val second = other.readPixels()
    var compared = 0
    var different = 0
    first.indices.forEach { index ->
        val x = index % width
        val y = index / width
        if (!VisualRegressionConfig.isMasked(x, y, width, height)) {
            compared++
            if (VisualComparator.colorDelta(first[index], second[index]) >
                VisualRegressionConfig.channelTolerance
            ) {
                different++
            }
        }
    }
    return different.toDouble() / compared <= 0.0001
}

private fun Bitmap.writePng(file: File) {
    check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
    FileOutputStream(file).use { output -> check(compress(Bitmap.CompressFormat.PNG, 100, output)) }
}
