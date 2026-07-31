package io.github.ciurlaro.codexmobile.app

import android.content.ContentValues
import android.content.Context
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
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.app.ui.chat.AndroidMathBlock
import io.github.ciurlaro.codexmobile.app.ui.chat.MathImageTransformer
import io.github.ciurlaro.codexmobile.app.ui.chat.ProvideMathRendering
import io.github.ciurlaro.codexmobile.app.ui.shell.AppShell
import io.github.ciurlaro.codexmobile.app.ui.shell.MainActivity
import io.github.ciurlaro.codexmobile.app.ui.shell.VisualScenario
import io.github.ciurlaro.codexmobile.app.ui.theme.AppTheme
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
import org.junit.Assume.assumeTrue
import org.junit.Test

class VisualRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun appOwnedScenariosMatchReviewedBaselines() {
        assumeTrue(
            "Run visual checks through visualCapture or visualCheck",
            InstrumentationRegistry.getArguments().containsKey("captureOnly"),
        )
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
        val activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { host ->
            host.window.setWindowAnimations(0)
            host.setContent {
                AppTheme {
                    ProvideMathRendering(MathImageTransformer, ::AndroidMathBlock) {
                        AppShell(state = scenario.state(), onEvent = {})
                    }
                }
            }
        }
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
        activity: ActivityScenario<MainActivity>,
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
        activity: ActivityScenario<MainActivity>,
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
