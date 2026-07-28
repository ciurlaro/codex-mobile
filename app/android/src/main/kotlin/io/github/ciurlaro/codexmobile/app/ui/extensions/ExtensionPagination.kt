package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import kotlin.math.ceil
import kotlin.math.max

@Composable
internal fun PagedExtensionList(
    itemCount: Int,
    pageKey: String,
    emptyMessage: String,
    modifier: Modifier,
    item: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val largeText = LocalDensity.current.fontScale > ACCESSIBILITY_FONT_SCALE
        val fullPageSize = extensionPageSize(maxHeight.value)
        val showPager = itemCount > fullPageSize
        val availableForCards = (maxHeight - if (showPager) PAGE_FOOTER_HEIGHT else 0.dp)
            .coerceAtLeast(EXTENSION_CARD_HEIGHT)
        val pageSize = extensionPageSize(availableForCards.value)
        val pageCount = max(1, ceil(itemCount.toDouble() / pageSize).toInt())
        var page by rememberSaveable(pageKey, pageSize) { mutableIntStateOf(0) }
        LaunchedEffect(pageCount) {
            if (page >= pageCount) page = pageCount - 1
        }
        val first = page * pageSize
        val last = minOf(itemCount, first + pageSize)
        Column(Modifier.fillMaxSize()) {
            when {
                itemCount == 0 -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(emptyMessage, color = ChatColors.Secondary, textAlign = TextAlign.Center)
                }
                largeText -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(EXTENSION_CARD_SPACING),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items((first until last).toList()) { index -> item(index) }
                }
                else -> Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(EXTENSION_CARD_SPACING),
                ) {
                    for (index in first until last) item(index)
                }
            }
            if (showPager) PageFooter(page, pageCount, onPage = { page = it })
        }
    }
}

@Composable
private fun PageFooter(page: Int, pageCount: Int, onPage: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(PAGE_FOOTER_HEIGHT),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("‹") }
        if (pageCount > 1) {
            pageTokens(page, pageCount).forEachIndexed { index, token ->
                if (token == null) {
                    Text("…", color = ChatColors.Secondary, modifier = Modifier.padding(horizontal = 4.dp))
                } else {
                    TextButton(onClick = { onPage(token) }, enabled = token != page) {
                        Text(
                            "${token + 1}",
                            color = if (token == page) ChatColors.Accent else ChatColors.Primary,
                            fontWeight = if (token == page) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        } else {
            Text("Page 1", color = ChatColors.Secondary, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { onPage(page + 1) }, enabled = page < pageCount - 1) { Text("›") }
    }
}

internal fun pageTokens(page: Int, pageCount: Int): List<Int?> {
    if (pageCount <= 5) return (0 until pageCount).toList()
    val visible = sortedSetOf(0, pageCount - 1, page - 1, page, page + 1).filter { it in 0 until pageCount }
    return buildList {
        visible.forEachIndexed { index, value ->
            if (index > 0 && value - visible[index - 1] > 1) add(null)
            add(value)
        }
    }
}

internal fun extensionPageSize(availableHeightDp: Float): Int =
    max(1, ((availableHeightDp + EXTENSION_CARD_SPACING.value) / EXTENSION_CARD_EXTENT.value).toInt())

private val EXTENSION_CARD_SPACING = 10.dp
private val EXTENSION_CARD_EXTENT = EXTENSION_CARD_HEIGHT + EXTENSION_CARD_SPACING
private val PAGE_FOOTER_HEIGHT = 52.dp
