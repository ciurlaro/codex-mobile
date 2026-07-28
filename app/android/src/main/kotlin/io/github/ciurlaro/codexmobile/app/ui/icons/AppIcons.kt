package io.github.ciurlaro.codexmobile.app.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions

@Composable
internal fun CircleIconButton(
    label: String,
    glyph: IconGlyph,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = ChatColors.Elevated,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(ChatDimensions.TouchTarget)
            .background(containerColor, CircleShape)
            .semantics { contentDescription = label },
    ) {
        AppIcon(
            glyph,
            Modifier.size(24.dp),
            if (enabled) ChatColors.Primary else ChatColors.Secondary.copy(alpha = 0.45f),
        )
    }
}

internal enum class IconGlyph {
    MENU, PLUS, NEW_CHAT, SEARCH, USER, BACK, CHEVRON_DOWN, CHEVRON_RIGHT,
    CHECK, CHECKLIST, CONNECTED_STEPS, SEND, STOP, CLOSE, SETTINGS,
    FILTER_SLIDERS, SPEED, BRAIN, INTELLIGENCE, FOLDER, STORAGE, SHIELD,
    LINK, LOCK, INFO, LOGOUT, PIN, EDIT, TRASH, GLOBE, SPARKLES, PUZZLE, COPY,
}

@Composable
internal fun AppIcon(
    glyph: IconGlyph,
    modifier: Modifier = Modifier,
    tint: Color = ChatColors.Primary,
) {
    Canvas(modifier) {
        val stroke = (size.minDimension * 0.09f).coerceAtLeast(1.5f)
        when (glyph) {
            IconGlyph.MENU,
            IconGlyph.PLUS,
            IconGlyph.NEW_CHAT,
            IconGlyph.SEARCH,
            IconGlyph.USER,
            IconGlyph.BACK,
            IconGlyph.CHEVRON_DOWN,
            IconGlyph.CHEVRON_RIGHT,
            IconGlyph.CHECK,
            IconGlyph.CHECKLIST,
            IconGlyph.CONNECTED_STEPS,
            IconGlyph.SEND,
            IconGlyph.STOP,
            IconGlyph.CLOSE,
            IconGlyph.SETTINGS,
            IconGlyph.FILTER_SLIDERS,
            -> drawNavigationIcon(glyph, tint, stroke)
            else -> drawFeatureIcon(glyph, tint, stroke)
        }
    }
}
