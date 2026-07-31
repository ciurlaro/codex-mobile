package io.github.ciurlaro.codexmobile.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import io.github.ciurlaro.codexmobile.app.ui.shell.VisualScenario
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

internal object VisualRegressionConfig {
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

internal object VisualComparator {
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

internal data class PixelInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class NormalizedRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun contains(x: Int, y: Int, width: Int, height: Int): Boolean =
        x >= width * left && x < width * right && y >= height * top && y < height * bottom
}

internal fun Bitmap.readPixels(): IntArray = IntArray(width * height).also {
    getPixels(it, 0, width, 0, 0, width, height)
}

internal fun Bitmap.visuallyMatches(other: Bitmap): Boolean {
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

internal fun Bitmap.writePng(file: File) {
    check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
    FileOutputStream(file).use { output -> check(compress(Bitmap.CompressFormat.PNG, 100, output)) }
}
