package io.github.akash904.photohost.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.akash904.photohost.media.BlurHashDecoder
import io.github.akash904.photohost.net.AssetDetailDto
import io.github.akash904.photohost.net.LibraryApi
import io.github.akash904.photohost.net.TimelineItemDto
import io.github.akash904.photohost.ui.JustifiedGrid

/**
 * Full-screen viewer.
 *
 * A pager over the already-loaded timeline rather than a single-item screen, so swiping between
 * photos costs nothing and never round-trips for the list. Zoom state deliberately lives at this
 * level, not per page: while an image is zoomed the pager's own horizontal gesture must be disabled
 * or panning across a zoomed photo would flick to the next one instead.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ViewerScreen(
    items: List<TimelineItemDto>,
    startId: Long,
    api: LibraryApi,
    onClose: () -> Unit,
    onNearEnd: () -> Unit,
    onFavoriteChanged: (Long, Boolean) -> Unit,
) {
    if (items.isEmpty()) {
        onClose()
        return
    }
    val context = LocalContext.current
    val container = remember { io.github.akash904.photohost.di.AppContainer.get(context) }
    val startIndex = remember(startId) { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var chromeVisible by remember { mutableStateOf(true) }
    var infoVisible by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<AssetDetailDto?>(null) }

    val current = items.getOrNull(pagerState.currentPage)

    // Media3 fetches the original over HTTP, so it needs the same bearer token every other
    // request carries. One player for the whole viewer: creating one per page would churn codecs.
    val player = remember {
        // OkHttp rather than the default HttpURLConnection stack: video has to travel the same
        // pinned TLS path as everything else, or playback fails with a trust error on exactly the
        // servers that are set up most carefully.
        val http = OkHttpDataSource.Factory(
            okhttp3.Call.Factory { request -> container.http.newCall(request) },
        ).setDefaultRequestProperties(mapOf("Authorization" to api.authHeader()))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Reset zoom on page change, otherwise the next photo opens mid-zoom at a random offset.
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
        val item = items.getOrNull(pagerState.currentPage)
        if (item != null && item.isVideo) {
            player.setMediaItem(MediaItem.fromUri(api.originalUrl(item.id)))
            player.prepare()
            player.playWhenReady = true
        } else {
            player.stop()
            player.clearMediaItems()
        }
        if (item != null && pagerState.currentPage >= items.size - 4) onNearEnd()
        detail = null
    }

    LaunchedEffect(infoVisible, current?.id) {
        val id = current?.id
        if (infoVisible && id != null && detail?.id != id) detail = api.asset(id)
    }

    BackHandler {
        when {
            infoVisible -> infoVisible = false
            scale > 1.01f -> {
                scale = 1f
                offset = Offset.Zero
            }
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        HorizontalPager(
            state = pagerState,
            // Pinch-to-zoom and horizontal paging both want the same gesture; while zoomed in,
            // panning must win or the photo cannot be explored.
            userScrollEnabled = scale <= 1.01f,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val isCurrent = page == pagerState.currentPage

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (item.isVideo && isCurrent) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = true
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                            }
                        },
                        update = { it.player = player },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val placeholder = remember(item.blurhash) {
                        BlurHashDecoder.decode(item.blurhash)?.let { BitmapPainter(it.asImageBitmap()) }
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            // The preview is ~250 KB and already cached from nothing; the original
                            // would stall the swipe. Full resolution only matters once zoomed.
                            .data(if (scale > 1.5f && isCurrent) api.originalUrl(item.id) else api.thumbUrl(item.id, "preview"))
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        placeholder = placeholder,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = if (isCurrent) scale else 1f,
                                scaleY = if (isCurrent) scale else 1f,
                                translationX = if (isCurrent) offset.x else 0f,
                                translationY = if (isCurrent) offset.y else 0f,
                            )
                            .pointerInput(item.id) {
                                // Hand-rolled rather than detectTransformGestures, which consumes
                                // every drag the moment it passes touch slop -- including a
                                // one-finger swipe at 1x, where there is nothing to pan. That ate
                                // the pager's horizontal gesture and made swiping between photos
                                // do nothing at all.
                                //
                                // Events are consumed only when the gesture is genuinely ours: a
                                // pinch (two or more pointers), or a drag while already zoomed in.
                                // Everything else falls through to the pager untouched.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pinching = event.changes.size > 1
                                        val zoomed = scale > 1.01f
                                        if (pinching || zoomed) {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            scale = (scale * zoomChange).coerceIn(1f, 6f)
                                            offset = if (scale > 1.01f) {
                                                offset + panChange
                                            } else {
                                                Offset.Zero
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .pointerInput(item.id) {
                                detectTapGestures(
                                    onTap = { chromeVisible = !chromeVisible },
                                    onDoubleTap = {
                                        if (scale > 1.01f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                        }
                                    },
                                )
                            },
                    )
                }
            }
        }

        if (chromeVisible) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                Text(
                    text = current?.let { "${JustifiedGrid.labelFor(it)}  ${JustifiedGrid.timeOf(it)}" }.orEmpty(),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                val fav = current?.favorite == true
                TextButton(onClick = {
                    val item = current ?: return@TextButton
                    onFavoriteChanged(item.id, !item.favorite)
                }) { Text(if (fav) "★" else "☆", color = Color.White, fontSize = 18.sp) }
                TextButton(onClick = { infoVisible = !infoVisible }) {
                    Text("Info", color = Color.White)
                }
            }

            Text(
                text = "${pagerState.currentPage + 1} / ${items.size}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }

        AnimatedVisibility(
            visible = infoVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoPanel(detail, current)
        }
    }
}

@Composable
private fun InfoPanel(detail: AssetDetailDto?, item: TimelineItemDto?) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (detail == null) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            item?.let {
                Text(
                    "${JustifiedGrid.labelFor(it)} · ${JustifiedGrid.timeOf(it)}",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Provenance is surfaced, not hidden: a date guessed from a filename should not look
            // as authoritative as one the camera actually recorded.
            val sources = listOf(
                "EXIF + offset", "EXIF (no timezone)", "container", "file mtime", "filename", "unknown",
            )
            Field("Date from", sources.getOrElse(detail.capturedAtSource) { "?" })
            Field("Size", "%.1f MB".format(detail.byteSize / 1048576.0))
            Field("Dimensions", "${detail.width ?: "?"} × ${detail.height ?: "?"}")
            detail.durationMs?.let { Field("Length", JustifiedGrid.durationLabel(it)) }
            detail.cameraMake?.let { Field("Camera", "$it ${detail.cameraModel.orEmpty()}") }
            detail.latitude?.let {
                Field("Location", "%.5f, %.5f".format(it, detail.longitude ?: 0.0))
            }
            Field("Type", detail.mime)
            Field("Path", detail.relPath ?: "(unresolved)")
            Field("Hash", detail.contentHash.take(24) + "…")
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (label == "Hash") FontFamily.Monospace else FontFamily.Default,
        )
    }
}
