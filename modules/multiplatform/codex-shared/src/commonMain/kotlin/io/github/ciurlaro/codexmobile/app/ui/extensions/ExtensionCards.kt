package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions

internal enum class ExtensionActionStyle { PRIMARY, DANGER, DISABLED }

@Composable
internal fun ExtensionCard(
    title: String,
    subtitle: String,
    metadata: String,
    glyph: IconGlyph,
    actionLabel: String,
    actionStyle: ExtensionActionStyle,
    busy: Boolean,
    error: String?,
    controlsEnabled: Boolean,
    onAction: (() -> Unit)?,
    secondaryActionLabel: String? = null,
    secondaryActionGlyph: IconGlyph? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val largeText = LocalDensity.current.fontScale > ACCESSIBILITY_FONT_SCALE
    Surface(
        modifier = Modifier.fillMaxWidth().then(
            if (largeText) Modifier.heightIn(min = EXTENSION_CARD_HEIGHT) else Modifier.height(EXTENSION_CARD_HEIGHT),
        ),
        color = ChatColors.Elevated,
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
        border = BorderStroke(1.dp, ChatColors.Border),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = ChatColors.ElevatedStrong,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(glyph, Modifier.size(23.dp), ChatColors.Primary)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    metadata.replaceFirstChar(Char::uppercase),
                    color = ChatColors.Accent,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = ChatColors.Secondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                error?.let {
                    Text(it, color = ChatColors.Danger, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            Spacer(Modifier.size(8.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                ExtensionCardActions(
                    actionLabel = actionLabel,
                    actionStyle = actionStyle,
                    controlsEnabled = controlsEnabled,
                    onAction = onAction,
                    secondaryActionLabel = secondaryActionLabel,
                    secondaryActionGlyph = secondaryActionGlyph,
                    onSecondaryAction = onSecondaryAction,
                )
            }
        }
    }
}

@Composable
private fun ExtensionCardActions(
    actionLabel: String,
    actionStyle: ExtensionActionStyle,
    controlsEnabled: Boolean,
    onAction: (() -> Unit)?,
    secondaryActionLabel: String?,
    secondaryActionGlyph: IconGlyph?,
    onSecondaryAction: (() -> Unit)?,
) {
    if (secondaryActionLabel != null && secondaryActionGlyph != null && onSecondaryAction != null) {
        IconButton(
            onClick = onSecondaryAction,
            enabled = controlsEnabled,
            modifier = Modifier.size(40.dp).semantics { contentDescription = secondaryActionLabel },
        ) {
            AppIcon(secondaryActionGlyph, Modifier.size(20.dp), ChatColors.Danger)
        }
        Spacer(Modifier.size(4.dp))
    }
    Button(
        onClick = { onAction?.invoke() },
        enabled = controlsEnabled && onAction != null,
        colors = when (actionStyle) {
            ExtensionActionStyle.DANGER -> ButtonDefaults.buttonColors(
                containerColor = ChatColors.Danger,
                contentColor = ChatColors.Primary,
            )
            ExtensionActionStyle.PRIMARY -> ButtonDefaults.buttonColors()
            ExtensionActionStyle.DISABLED -> ButtonDefaults.buttonColors(
                disabledContainerColor = ChatColors.ElevatedStrong,
                disabledContentColor = ChatColors.Secondary,
            )
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.widthIn(min = 92.dp),
    ) { Text(actionLabel, maxLines = 1) }
}

internal fun AppUiState.actionError(operationId: String): String? =
    extensionActionError?.takeIf { it.operationId == operationId }?.message

internal val EXTENSION_CARD_HEIGHT = 96.dp
internal const val ACCESSIBILITY_FONT_SCALE = 1.3f
