package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions

@Composable
internal fun ExtensionTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(ChatDimensions.TopBarHeight).padding(horizontal = ChatDimensions.ScreenPadding)) {
        CircleIconButton("Back", IconGlyph.BACK, Modifier.align(Alignment.CenterStart), onClick = onBack)
        Text(
            title,
            Modifier.align(Alignment.Center).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ExtensionLoading(value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), color = ChatColors.Accent, strokeWidth = 2.dp)
        Spacer(Modifier.size(10.dp))
        Text(value, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ExtensionError(value: String, onEvent: (AppUiEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            value,
            color = ChatColors.Danger,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onEvent(AppUiEvent.RefreshExtensions) }) { Text("Retry") }
    }
}

@Composable
internal fun ExtensionNoticeCard(value: String, isError: Boolean) {
    Surface(
        color = (if (isError) ChatColors.Danger else ChatColors.PluginAccent).copy(alpha = 0.12f),
        shape = RoundedCornerShape(ChatDimensions.ControlCorner),
        border = BorderStroke(1.dp, if (isError) ChatColors.Danger else ChatColors.PluginAccent),
    ) {
        Text(
            value,
            color = if (isError) ChatColors.Danger else ChatColors.Primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun Modifier.statusAndNavigationPadding() = statusBarsPadding().navigationBarsPadding()
