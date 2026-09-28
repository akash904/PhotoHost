package io.github.akash904.photohost.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A draggable handle on the right edge of a grid, for crossing thousands of items in one movement,
 * with a bubble naming where the finger is (a month, say) while dragging.
 *
 * Laid over the grid with `matchParentSize`. Only the thin strip along the right edge takes touches,
 * and only while the handle is showing, so the grid's own taps and scrolling are untouched
 * elsewhere. The handle appears once the grid moves and fades after a moment of stillness, the way
 * phone galleries do; a list shorter than a few screens has no use for it and gets none.
 *
 * @param label names the item at an index, for the bubble; null shows no bubble.
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    label: (Int) -> String? = { null },
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }

    val visibleCount = state.layoutInfo.visibleItemsInfo.size
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            shown = true
        } else {
            delay(1500)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "fastScrollerAlpha")

    if (visibleCount == 0 || itemCount <= visibleCount * 3) return

    BoxWithConstraints(modifier) {
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
        val range = (trackPx - thumbPx).coerceAtLeast(1f)
        val lastStart = (itemCount - visibleCount).coerceAtLeast(1)

        val fraction = if (dragging) {
            dragFraction
        } else {
            (state.firstVisibleItemIndex.toFloat() / lastStart).coerceIn(0f, 1f)
        }
        val thumbY = (fraction * range).roundToInt()

        fun jumpTo(y: Float) {
            dragFraction = ((y - thumbPx / 2) / range).coerceIn(0f, 1f)
            val index = (dragFraction * (itemCount - 1)).roundToInt()
            scope.launch { state.scrollToItem(index) }
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY) }
                .padding(end = 3.dp)
                .alpha(alpha)
                .size(width = if (dragging) 10.dp else 7.dp, height = THUMB_HEIGHT)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )

        if (dragging) {
            val text = label((dragFraction * (itemCount - 1)).roundToInt())
            if (text != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbY) }
                        .padding(end = 28.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (shown) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(TOUCH_WIDTH)
                    .pointerInput(itemCount, trackPx) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; jumpTo(it.y) },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                jumpTo(change.position.y)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        )
                    },
            )
        }
    }
}

private val THUMB_HEIGHT = 48.dp

/** Wide enough to grab without aiming, narrow enough not to take the last column's taps. */
private val TOUCH_WIDTH = 28.dp
