package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.ImageTransformer

private val LocalMathImageTransformer = staticCompositionLocalOf<ImageTransformer> {
    error("A platform math image transformer is required")
}

private val LocalMathRenderer = staticCompositionLocalOf<@Composable (String) -> Unit> {
    error("A platform math renderer is required")
}

@Composable
internal fun MessageText(text: String) {
    MarkdownMessageText(text, LocalMathImageTransformer.current, LocalMathRenderer.current)
}

@Composable
fun ProvideMathRendering(
    imageTransformer: ImageTransformer,
    renderer: @Composable (String) -> Unit,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMathImageTransformer provides imageTransformer,
        LocalMathRenderer provides renderer,
        content = content,
    )
}
