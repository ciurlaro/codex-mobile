package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import kotlin.math.roundToInt

@Composable
internal fun ConversationSectionTitle(title: String) {
    Text(
        text = title,
        color = ChatColors.Primary,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
internal fun HistoryConversationRow(
    conversation: AgentConversationSummary,
    selected: Boolean,
    pinned: Boolean,
    menuExpanded: Boolean,
    menuPosition: Offset,
    onSelect: () -> Unit,
    onOpenMenu: (Offset) -> Unit,
    onDismissMenu: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = when {
            pressed -> ChatColors.Elevated
            selected -> ChatColors.ElevatedStrong
            else -> Color.Transparent
        },
        animationSpec = tween(90),
        label = "history-press",
    )
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatDimensions.TouchTarget)
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .indication(interactionSource, ripple(color = ChatColors.Primary.copy(alpha = 0.14f)))
                .pointerInput(conversation.sessionId) {
                    detectTapGestures(
                        onPress = { position ->
                            val press = PressInteraction.Press(position)
                            interactionSource.emit(press)
                            interactionSource.emit(
                                if (tryAwaitRelease()) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onSelect() },
                        onLongPress = onOpenMenu,
                    )
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    if (selected) stateDescription = "Current conversation"
                    onClick("Open conversation") { onSelect(); true }
                    onLongClick("Conversation actions") { onOpenMenu(Offset.Zero); true }
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) ChatColors.Primary else ChatColors.Secondary,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(7.dp).background(ChatColors.Accent, CircleShape))
            }
        }
        Box(
            Modifier
                .offset { IntOffset(menuPosition.x.roundToInt(), menuPosition.y.roundToInt()) }
                .size(1.dp),
        ) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin" else "Pin") },
                    leadingIcon = { AppIcon(IconGlyph.PIN, Modifier.size(22.dp), ChatColors.Primary) },
                    onClick = onTogglePin,
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { AppIcon(IconGlyph.EDIT, Modifier.size(22.dp), ChatColors.Primary) },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = ChatColors.Danger) },
                    leadingIcon = { AppIcon(IconGlyph.TRASH, Modifier.size(22.dp), ChatColors.Danger) },
                    onClick = onDelete,
                )
            }
        }
    }
}
