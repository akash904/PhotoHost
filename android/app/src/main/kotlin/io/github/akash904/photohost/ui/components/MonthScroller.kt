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
import io.github.akash904.photohost.net.BucketDto
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Library tab's drag handle: crosses the whole library by month in one movement.
 *
 * Unlike the picker's [FastScroller], the grid here holds only the pages loaded so far -- the
 * library is on a server and is read a page at a time -- so a handle over the loaded items would
 * only reach what had already been scrolled past. This one is laid out over the library's months,
 * each as tall as its share of all photos, from the counts the server already gives the month rail.
 * Dragging names the month under the finger; letting go seeks there, as tapping the rail does.
 *
 * Laid over the grid with `matchParentSize`; only a thin strip on the right takes touches, and only
 * while the handle shows, which is while the grid moves and briefly after.
 *
 * @param buckets months newest first, as the server returns them, keyed "yyyy-MM".
 * @param currentMonth the month at the top of the grid now, to place the handle.
 * @param moving whether the grid is scrolling, which is what brings the handle into view.
 */
@Composable
fun MonthScroller(
    buckets: List<BucketDto>,
    currentMonth: String?,
    moving: Boolean,
    onJump: (BucketDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(moving, dragging) {
        if (moving || dragging) {
            shown = true
        } else {
            delay(1500)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "monthScrollerAlpha")

    val total = buckets.sumOf { it.count }
    // A library of a month or two has nowhere to jump to.
    if (buckets.size < 3 || total == 0) return

    // Where each month starts, counted in photos from the newest.
    val starts = remember(buckets) {
        IntArray(buckets.size).also { a -> var sum = 0; buckets.forEachIndexed { i, b -> a[i] = sum; sum += b.count } }
    }
    fun bucketAt(fraction: Float): BucketDto {
        val target = fraction * total
        val i = starts.indexOfLast { it <= target }.coerceIn(0, buckets.lastIndex)
        return buckets[i]
    }
    val restingFraction = buckets.indexOfFirst { it.bucket == currentMonth }
        .takeIf { it >= 0 }?.let { starts[it].toFloat() / total } ?: 0f

    BoxWithConstraints(modifier) {
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
        val range = (trackPx - thumbPx).coerceAtLeast(1f)
        val fraction = if (dragging) dragFraction else restingFraction
        val thumbY = (fraction * range).roundToInt()

        fun follow(y: Float) {
            dragFraction = ((y - thumbPx / 2) / range).coerceIn(0f, 1f)
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
                    monthLabel(bucketAt(dragFraction).bucket),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        if (shown) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(28.dp)
                    .pointerInput(buckets, trackPx) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; follow(it.y) },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                follow(change.position.y)
                            },
                            // One seek, on release: each seek reloads the grid from the server, so
                            // seeking all the way through a drag would fetch every month passed.
                            onDragEnd = {
                                dragging = false
                                onJump(bucketAt(dragFraction))
                            },
                            onDragCancel = { dragging = false },
                        )
                    },
            )
        }
    }
}

private val THUMB_HEIGHT = 48.dp

/** "2026-04" as "Apr 2026", in the phone's language. */
private fun monthLabel(bucket: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM", Locale.US).parse(bucket)!!
    SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(parsed)
}.getOrDefault(bucket)
