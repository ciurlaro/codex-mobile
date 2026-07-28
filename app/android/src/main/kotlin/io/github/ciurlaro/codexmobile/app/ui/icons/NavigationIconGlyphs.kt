package io.github.ciurlaro.codexmobile.app.ui.icons

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawNavigationIcon(
    glyph: IconGlyph,
    tint: Color,
    stroke: Float,
) {
    val line: (Offset, Offset) -> Unit = { start, end ->
        drawLine(tint, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
    }
    when (glyph) {
            IconGlyph.MENU -> {
                line(Offset(size.width * .22f, size.height * .36f), Offset(size.width * .78f, size.height * .36f))
                line(Offset(size.width * .22f, size.height * .64f), Offset(size.width * .60f, size.height * .64f))
            }

            IconGlyph.PLUS -> {
                line(Offset(size.width * .5f, size.height * .18f), Offset(size.width * .5f, size.height * .82f))
                line(Offset(size.width * .18f, size.height * .5f), Offset(size.width * .82f, size.height * .5f))
            }

            IconGlyph.NEW_CHAT -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * .17f, size.height * .24f),
                    size = Size(size.width * .56f, size.height * .58f),
                    cornerRadius = CornerRadius(size.width * .08f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .48f, size.height * .17f), Offset(size.width * .83f, size.height * .17f))
                line(Offset(size.width * .83f, size.height * .17f), Offset(size.width * .83f, size.height * .52f))
                line(Offset(size.width * .48f, size.height * .52f), Offset(size.width * .83f, size.height * .17f))
            }

            IconGlyph.SEARCH -> {
                drawCircle(
                    color = tint,
                    radius = size.minDimension * .27f,
                    center = Offset(size.width * .43f, size.height * .42f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .62f, size.height * .62f), Offset(size.width * .82f, size.height * .82f))
            }

            IconGlyph.USER -> {
                drawCircle(
                    tint,
                    size.minDimension * .17f,
                    Offset(size.width * .5f, size.height * .34f),
                    style = Stroke(stroke),
                )
                drawArc(
                    color = tint,
                    startAngle = 195f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(size.width * .2f, size.height * .43f),
                    size = Size(size.width * .6f, size.height * .48f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            IconGlyph.BACK -> {
                line(Offset(size.width * .72f, size.height * .18f), Offset(size.width * .30f, size.height * .5f))
                line(Offset(size.width * .30f, size.height * .5f), Offset(size.width * .72f, size.height * .82f))
            }

            IconGlyph.CHEVRON_DOWN -> {
                line(Offset(size.width * .22f, size.height * .38f), Offset(size.width * .5f, size.height * .66f))
                line(Offset(size.width * .5f, size.height * .66f), Offset(size.width * .78f, size.height * .38f))
            }

            IconGlyph.CHEVRON_RIGHT -> {
                line(Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f))
                line(Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f))
            }

            IconGlyph.CHECK -> {
                line(Offset(size.width * .18f, size.height * .52f), Offset(size.width * .42f, size.height * .74f))
                line(Offset(size.width * .42f, size.height * .74f), Offset(size.width * .84f, size.height * .24f))
            }

            IconGlyph.CHECKLIST -> repeat(3) { index ->
                val y = size.height * (.25f + index * .25f)
                line(Offset(size.width * .10f, y), Offset(size.width * .17f, y + size.height * .07f))
                line(Offset(size.width * .17f, y + size.height * .07f), Offset(size.width * .29f, y - size.height * .08f))
                line(Offset(size.width * .42f, y), Offset(size.width * .90f, y))
            }

            IconGlyph.CONNECTED_STEPS -> {
                line(Offset(size.width * .24f, size.height * .20f), Offset(size.width * .24f, size.height * .80f))
                repeat(3) { index ->
                    val y = size.height * (.20f + index * .30f)
                    drawCircle(tint, size.minDimension * .08f, Offset(size.width * .24f, y), style = Stroke(stroke))
                    line(Offset(size.width * .42f, y), Offset(size.width * .88f, y))
                }
            }

            IconGlyph.SEND -> {
                line(Offset(center.x, size.height * .78f), Offset(center.x, size.height * .22f))
                line(Offset(size.width * .27f, size.height * .45f), Offset(center.x, size.height * .22f))
                line(Offset(center.x, size.height * .22f), Offset(size.width * .73f, size.height * .45f))
            }

            IconGlyph.STOP -> drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * .28f, size.height * .28f),
                size = Size(size.width * .44f, size.height * .44f),
                cornerRadius = CornerRadius(size.width * .06f),
            )

            IconGlyph.CLOSE -> {
                line(Offset(size.width * .24f, size.height * .24f), Offset(size.width * .76f, size.height * .76f))
                line(Offset(size.width * .76f, size.height * .24f), Offset(size.width * .24f, size.height * .76f))
            }

            IconGlyph.SETTINGS -> {
                drawCircle(tint, size.minDimension * .29f, center, style = Stroke(stroke))
                drawCircle(tint, size.minDimension * .08f, center, style = Stroke(stroke))
                repeat(8) { index ->
                    val angle = index * PI.toFloat() / 4f
                    val x = cos(angle)
                    val y = sin(angle)
                    line(
                        Offset(center.x + x * size.width * .29f, center.y + y * size.height * .29f),
                        Offset(center.x + x * size.width * .42f, center.y + y * size.height * .42f),
                    )
                }
            }

            IconGlyph.FILTER_SLIDERS -> {
                line(Offset(size.width * .16f, size.height * .28f), Offset(size.width * .84f, size.height * .28f))
                line(Offset(size.width * .16f, size.height * .50f), Offset(size.width * .84f, size.height * .50f))
                line(Offset(size.width * .16f, size.height * .72f), Offset(size.width * .84f, size.height * .72f))
                drawCircle(tint, size.minDimension * .07f, Offset(size.width * .37f, size.height * .28f))
                drawCircle(tint, size.minDimension * .07f, Offset(size.width * .66f, size.height * .50f))
                drawCircle(tint, size.minDimension * .07f, Offset(size.width * .46f, size.height * .72f))
            }
        else -> Unit
    }
}
