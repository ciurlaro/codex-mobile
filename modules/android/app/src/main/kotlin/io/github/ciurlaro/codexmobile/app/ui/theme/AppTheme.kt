package io.github.ciurlaro.codexmobile.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object ChatColors {
    val Background = Color.Black
    val Drawer = Color(0xFF101010)
    val Elevated = Color(0xFF202020)
    val ElevatedStrong = Color(0xFF2A2A2A)
    val UserBubble = Color(0xFF3D3D3D)
    val ShellBubble = Color(0xFF17345C)
    val CodeSurface = Color(0xFF151515)
    val Border = Color(0xFF3A3A3A)
    val Primary = Color(0xFFF5F5F5)
    val Secondary = Color(0xFFA5A5A5)
    val Accent = Color(0xFF3F83F8)
    val SkillAccent = Color(0xFFA78BFA)
    val PluginAccent = Color(0xFF58C77B)
    val CodeAccent = Color(0xFF58C77B)
    val PlanAccent = Color(0xFFFFA94D)
    val MathAccent = Color(0xFFFFCF5C)
    val ThoughtAccent = Color(0xFFA78BFA)
    val Danger = Color(0xFFFF7A83)
    val Scrim = Color(0x99000000)
}

internal object ChatDimensions {
    val ScreenPadding = 16.dp
    val TopBarHeight = 64.dp
    val TouchTarget = 48.dp
    val ControlCorner = 24.dp
    val CardCorner = 28.dp
    val ComposerCorner = 30.dp
    val MessageCorner = 24.dp
    val SelectorWidth = 340.dp
    val SelectorBottomOffset = 112.dp
}

private val CodexDarkScheme = darkColorScheme(
    primary = ChatColors.Accent,
    onPrimary = Color.White,
    background = ChatColors.Background,
    onBackground = ChatColors.Primary,
    surface = ChatColors.Elevated,
    onSurface = ChatColors.Primary,
    surfaceVariant = ChatColors.ElevatedStrong,
    onSurfaceVariant = ChatColors.Secondary,
    outline = ChatColors.Border,
    error = ChatColors.Danger,
)

@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CodexDarkScheme, content = content)
}
