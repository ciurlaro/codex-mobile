package io.github.ciurlaro.codexmobile.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import io.github.ciurlaro.codexmobile.app.presentation.formatting.decodeMathLink
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

internal object MathImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val formula = decodeMathLink(link) ?: return null
        val context = LocalContext.current
        val density = LocalDensity.current
        val color = ChatColors.Primary.toArgb()
        val bitmap by produceState<Bitmap?>(null, formula, color, density.density) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    RaTeXFontLoader.ensureLoaded(context)
                    val display = RaTeXEngine.parseBlocking(formula, false, color)
                    val renderer = RaTeXRenderer(display, 16f * density.density) {
                        RaTeXFontLoader.getTypeface(it)
                    }
                    Bitmap.createBitmap(
                        ceil(renderer.widthPx).toInt().coerceAtLeast(1),
                        ceil(renderer.totalHeightPx).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    ).also { renderer.draw(Canvas(it)) }
                }.getOrNull()
            }
        }
        val rendered = bitmap ?: return null
        val painter = remember(rendered) { BitmapPainter(rendered.asImageBitmap()) }
        return ImageData(
            painter = painter,
            modifier = Modifier,
            contentDescription = "Math: $formula",
            alignment = Alignment.CenterStart,
        )
    }

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((String, Size) -> Unit)?,
    ): PlaceholderConfig {
        val formula = decodeMathLink(link) ?: return super.placeholderConfig(
            link,
            density,
            containerSize,
            imageWidth,
            imageSize,
            imageSizeChanged,
        )
        val size = if (imageSize != Size.Unspecified) {
            with(density) { Size(imageSize.width.toDp().value, imageSize.height.toDp().value) }
        } else {
            Size((formula.length * 8f).coerceIn(18f, 180f), 24f)
        }
        return PlaceholderConfig(size, PlaceholderVerticalAlign.TextCenter)
    }
}
