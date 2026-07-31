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

internal fun DrawScope.drawFeatureIcon(
    glyph: IconGlyph,
    tint: Color,
    stroke: Float,
) {
    val line: (Offset, Offset) -> Unit = { start, end ->
        drawLine(tint, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
    }
    when (glyph) {
            IconGlyph.SPEED -> {
                drawArc(
                    tint,
                    200f,
                    140f,
                    false,
                    Offset(size.width * .16f, size.height * .20f),
                    Size(size.width * .68f, size.height * .68f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                line(center, Offset(size.width * .70f, size.height * .36f))
            }

            IconGlyph.BRAIN -> {
                val left = Path().apply {
                    moveTo(center.x, size.height * .82f)
                    cubicTo(size.width * .34f, size.height * .88f, size.width * .20f, size.height * .72f, size.width * .28f, size.height * .59f)
                    cubicTo(size.width * .12f, size.height * .50f, size.width * .19f, size.height * .29f, size.width * .34f, size.height * .30f)
                    cubicTo(size.width * .31f, size.height * .13f, center.x, size.height * .11f, center.x, size.height * .27f)
                    lineTo(center.x, size.height * .82f)
                }
                val right = Path().apply {
                    moveTo(center.x, size.height * .82f)
                    cubicTo(size.width * .66f, size.height * .88f, size.width * .80f, size.height * .72f, size.width * .72f, size.height * .59f)
                    cubicTo(size.width * .88f, size.height * .50f, size.width * .81f, size.height * .29f, size.width * .66f, size.height * .30f)
                    cubicTo(size.width * .69f, size.height * .13f, center.x, size.height * .11f, center.x, size.height * .27f)
                    lineTo(center.x, size.height * .82f)
                }
                drawPath(left, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                drawPath(right, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                line(Offset(size.width * .28f, size.height * .59f), Offset(size.width * .42f, size.height * .55f))
                line(Offset(size.width * .72f, size.height * .59f), Offset(size.width * .58f, size.height * .55f))
            }

            IconGlyph.INTELLIGENCE -> {
                for (index in 0..4) {
                    val x = size.width * (.22f + index * .14f)
                    val half = size.height * if (index % 2 == 0) .25f else .15f
                    line(Offset(x, center.y - half), Offset(x, center.y + half))
                }
            }

            IconGlyph.FOLDER -> {
                val path = Path().apply {
                    moveTo(size.width * .12f, size.height * .30f)
                    lineTo(size.width * .40f, size.height * .30f)
                    lineTo(size.width * .49f, size.height * .40f)
                    lineTo(size.width * .88f, size.height * .40f)
                    lineTo(size.width * .82f, size.height * .78f)
                    lineTo(size.width * .18f, size.height * .78f)
                    close()
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }

            IconGlyph.STORAGE -> {
                drawOval(
                    color = tint,
                    topLeft = Offset(size.width * .18f, size.height * .15f),
                    size = Size(size.width * .64f, size.height * .25f),
                    style = Stroke(stroke),
                )
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(size.width * .18f, size.height * .49f),
                    size = Size(size.width * .64f, size.height * .25f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                line(Offset(size.width * .18f, size.height * .28f), Offset(size.width * .18f, size.height * .62f))
                line(Offset(size.width * .82f, size.height * .28f), Offset(size.width * .82f, size.height * .62f))
            }

            IconGlyph.SHIELD -> {
                val path = Path().apply {
                    moveTo(center.x, size.height * .12f)
                    lineTo(size.width * .82f, size.height * .28f)
                    lineTo(size.width * .75f, size.height * .68f)
                    quadraticTo(center.x, size.height * .9f, size.width * .25f, size.height * .68f)
                    lineTo(size.width * .18f, size.height * .28f)
                    close()
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }

            IconGlyph.LINK -> {
                drawArc(
                    color = tint,
                    startAngle = 120f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(size.width * .10f, size.height * .18f),
                    size = Size(size.width * .48f, size.height * .48f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = tint,
                    startAngle = -60f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(size.width * .42f, size.height * .34f),
                    size = Size(size.width * .48f, size.height * .48f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                line(Offset(size.width * .39f, size.height * .58f), Offset(size.width * .61f, size.height * .42f))
            }

            IconGlyph.LOCK -> {
                drawRoundRect(
                    tint,
                    Offset(size.width * .2f, size.height * .42f),
                    Size(size.width * .6f, size.height * .42f),
                    CornerRadius(size.width * .08f),
                    style = Stroke(stroke),
                )
                drawArc(
                    tint,
                    180f,
                    180f,
                    false,
                    Offset(size.width * .31f, size.height * .12f),
                    Size(size.width * .38f, size.height * .48f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            IconGlyph.INFO -> {
                drawCircle(tint, size.minDimension * .38f, center, style = Stroke(stroke))
                drawCircle(tint, stroke * .55f, Offset(center.x, size.height * .30f))
                line(Offset(center.x, size.height * .45f), Offset(center.x, size.height * .70f))
            }

            IconGlyph.LOGOUT -> {
                drawArc(
                    tint,
                    90f,
                    180f,
                    false,
                    Offset(size.width * .12f, size.height * .18f),
                    Size(size.width * .52f, size.height * .64f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .42f, center.y), Offset(size.width * .88f, center.y))
                line(Offset(size.width * .70f, size.height * .33f), Offset(size.width * .88f, center.y))
                line(Offset(size.width * .88f, center.y), Offset(size.width * .70f, size.height * .67f))
            }

            IconGlyph.PIN -> {
                line(Offset(size.width * .30f, size.height * .20f), Offset(size.width * .70f, size.height * .20f))
                line(Offset(size.width * .38f, size.height * .20f), Offset(size.width * .42f, size.height * .50f))
                line(Offset(size.width * .62f, size.height * .20f), Offset(size.width * .58f, size.height * .50f))
                line(Offset(size.width * .28f, size.height * .50f), Offset(size.width * .72f, size.height * .50f))
                line(Offset(center.x, size.height * .50f), Offset(center.x, size.height * .86f))
            }

            IconGlyph.EDIT -> {
                line(Offset(size.width * .20f, size.height * .72f), Offset(size.width * .68f, size.height * .24f))
                line(Offset(size.width * .31f, size.height * .83f), Offset(size.width * .79f, size.height * .35f))
                line(Offset(size.width * .68f, size.height * .24f), Offset(size.width * .79f, size.height * .35f))
                line(Offset(size.width * .20f, size.height * .72f), Offset(size.width * .17f, size.height * .86f))
                line(Offset(size.width * .17f, size.height * .86f), Offset(size.width * .31f, size.height * .83f))
            }

            IconGlyph.TRASH -> {
                drawRoundRect(
                    tint,
                    Offset(size.width * .25f, size.height * .30f),
                    Size(size.width * .5f, size.height * .55f),
                    CornerRadius(size.width * .04f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .18f, size.height * .25f), Offset(size.width * .82f, size.height * .25f))
                line(Offset(size.width * .40f, size.height * .14f), Offset(size.width * .60f, size.height * .14f))
            }

            IconGlyph.GLOBE -> {
                drawCircle(tint, size.minDimension * .36f, center, style = Stroke(stroke))
                drawOval(
                    tint,
                    Offset(size.width * .34f, size.height * .14f),
                    Size(size.width * .32f, size.height * .72f),
                    style = Stroke(stroke),
                )
                line(Offset(size.width * .15f, center.y), Offset(size.width * .85f, center.y))
            }

            IconGlyph.SPARKLES -> {
                val large = Path().apply {
                    moveTo(size.width * .42f, size.height * .10f)
                    lineTo(size.width * .50f, size.height * .36f)
                    lineTo(size.width * .72f, size.height * .45f)
                    lineTo(size.width * .50f, size.height * .54f)
                    lineTo(size.width * .42f, size.height * .80f)
                    lineTo(size.width * .34f, size.height * .54f)
                    lineTo(size.width * .12f, size.height * .45f)
                    lineTo(size.width * .34f, size.height * .36f)
                    close()
                }
                drawPath(large, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                line(Offset(size.width * .78f, size.height * .16f), Offset(size.width * .78f, size.height * .34f))
                line(Offset(size.width * .69f, size.height * .25f), Offset(size.width * .87f, size.height * .25f))
            }

            IconGlyph.PUZZLE -> {
                drawRoundRect(
                    tint,
                    Offset(size.width * .18f, size.height * .18f),
                    Size(size.width * .64f, size.height * .64f),
                    CornerRadius(size.width * .08f),
                    style = Stroke(stroke),
                )
                drawCircle(tint, size.minDimension * .10f, Offset(center.x, size.height * .18f))
                drawCircle(ChatColors.ElevatedStrong, size.minDimension * .06f, Offset(center.x, size.height * .18f))
                drawCircle(tint, size.minDimension * .10f, Offset(size.width * .82f, center.y))
                drawCircle(ChatColors.ElevatedStrong, size.minDimension * .06f, Offset(size.width * .82f, center.y))
            }

            IconGlyph.COPY -> {
                drawRoundRect(
                    tint,
                    Offset(size.width * .30f, size.height * .17f),
                    Size(size.width * .52f, size.height * .58f),
                    CornerRadius(size.width * .07f),
                    style = Stroke(stroke),
                )
                drawRoundRect(
                    tint,
                    Offset(size.width * .17f, size.height * .30f),
                    Size(size.width * .52f, size.height * .53f),
                    CornerRadius(size.width * .07f),
                    style = Stroke(stroke),
                )
            }
        else -> Unit
    }
}
