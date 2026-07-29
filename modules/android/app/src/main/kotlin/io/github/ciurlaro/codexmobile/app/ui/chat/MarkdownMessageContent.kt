package io.github.ciurlaro.codexmobile.app.ui.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import io.github.ciurlaro.codexmobile.app.presentation.formatting.normalizeMarkdownTaskLists
import io.github.ciurlaro.codexmobile.app.presentation.formatting.normalizeMathMarkdown
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.ratex.RaTeXView

@Composable
internal fun MessageText(text: String) {
    val markdown = remember(text) { text.normalizeMarkdownTaskLists().normalizeMathMarkdown() }
    val delegate = LocalUriHandler.current
    val safeLinks = remember(delegate) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val scheme = Uri.parse(uri).scheme?.lowercase()
                if (scheme == "http" || scheme == "https") delegate.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides safeLinks) {
        Markdown(
            content = markdown,
            imageTransformer = MathImageTransformer,
            typography = markdownTypography(
                code = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                inlineCode = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            ),
            components = markdownComponents(
                checkbox = { model ->
                    MarkdownCheckBox(model.content, model.node, model.typography.text)
                },
                codeFence = { model ->
                    MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                        if (language.isMathLanguage()) MathBlock(code) else TerminalCodeBlock(code, language, style)
                    }
                },
                codeBlock = { model ->
                    MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
                        TerminalCodeBlock(code, language, style)
                    }
                },
            ),
        )
    }
}

private fun String?.isMathLanguage() = this?.trim()?.lowercase() in setOf("math", "latex", "tex")

@Composable
private fun MathBlock(formula: String) {
    val color = ChatColors.Primary.toArgb()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.MathAccent.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .border(1.dp, ChatColors.MathAccent.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                "Math",
                color = ChatColors.MathAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        HorizontalDivider(color = ChatColors.MathAccent.copy(alpha = 0.25f))
        AndroidView(
            factory = { context ->
                RaTeXView(context).apply {
                    fontSize = 22f
                    displayMode = true
                    this.color = color
                    setPadding(0, 0, 0, 0)
                }
            },
            update = {
                it.color = color
                it.latex = formula.trim()
            },
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
        )
    }
}

@Composable
private fun TerminalCodeBlock(code: String, language: String?, style: TextStyle) {
    val label = language.orEmpty().trim().ifEmpty { "CODE" }.take(24).uppercase()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.CodeSurface, RoundedCornerShape(16.dp))
            .border(1.dp, ChatColors.CodeAccent.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            TerminalHeader(label, ChatColors.CodeAccent)
        }
        HorizontalDivider(color = ChatColors.Border)
        Text(
            text = code.trimEnd(),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
            color = ChatColors.Primary,
            style = style.copy(
                color = ChatColors.Primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            softWrap = false,
        )
    }
}
