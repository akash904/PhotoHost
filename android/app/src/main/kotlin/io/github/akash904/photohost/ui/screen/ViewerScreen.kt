package io.github.akash904.photohost.ui.screen

import android.widget.Toast
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.painterResource
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
import io.github.akash904.photohost.R
import io.github.akash904.photohost.media.BlurHashDecoder
import io.github.akash904.photohost.net.AssetDetailDto
import io.github.akash904.photohost.net.LibraryApi
import io.github.akash904.photohost.net.TimelineItemDto
import io.github.akash904.photohost.ui.JustifiedGrid
import io.github.akash904.photohost.ui.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Bytes fetched so far and the total, while an original is being fetched to share; null
    // otherwise. Sharing needs the full-quality file, and a video can take a while.
    var sharing by remember { mutableStateOf<Pair<Long, Long?>?>(null) }
    var shareJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val current = items.getOrNull(pagerState.currentPage)

    fun share(item: TimelineItemDto) {
        if (shareJob?.isActive == true) return
        shareJob = scope.launch {
            sharing = 0L to null
            try {
                val info = detail?.takeIf { it.id == item.id } ?: api.asset(item.id)
                val mime = info?.mime?.takeIf { it.isNotBlank() } ?: item.mime
                val file = withContext(Dispatchers.IO) {
                    Share.freshFile(context, Share.fileName(info?.relPath, mime, item.id))
                }
                var shown = 0L
                val ok = api.downloadOriginal(item.id, file) { done, total ->
                    // Every 256 KB, not every 64 KB chunk: recomposing per chunk is wasted work.
                    if (done - shown >= 256 * 1024 || done == total) {
                        shown = done
                        sharing = done to total
                    }
                }
                if (ok) {
                    Share.launch(context, file, mime)
                } else {
                    Toast.makeText(
                        context,
                        "Could not fetch the original to share: ${api.lastError ?: "unknown error"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                sharing = null
            }
        }
    }

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
                        // Clear of the top bar, so it never covers the top of the picture.
                        modifier = Modifier.fillMaxSize().padding(top = 48.dp),
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

        // Icons only. Words for Close, favourite, Share and Info left the date so little room that
        // it wrapped onto three lines; the date and time now head the Info panel instead.
        if (chromeVisible) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Close", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                val fav = current?.favorite == true
                IconButton(onClick = {
                    val item = current ?: return@IconButton
                    onFavoriteChanged(item.id, !item.favorite)
                }) {
                    Icon(
                        painterResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border),
                        contentDescription = if (fav) "Remove from favourites" else "Add to favourites",
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = {
                        // A video keeps playing behind the share sheet otherwise.
                        player.pause()
                        current?.let { share(it) }
                    },
                    enabled = sharing == null,
                ) {
                    Icon(painterResource(R.drawable.ic_share), contentDescription = "Share", tint = Color.White)
                }
                IconButton(onClick = { infoVisible = !infoVisible }) {
                    Icon(painterResource(R.drawable.ic_info), contentDescription = "Info", tint = Color.White)
                }
            }

            Text(
                text = "${pagerState.currentPage + 1} / ${items.size}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }

        sharing?.let { (done, total) ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Preparing to share…", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (total != null && total > 0) {
                            "%.1f of %.1f MB".format(done / 1048576.0, total / 1048576.0)
                        } else {
                            "%.1f MB".format(done / 1048576.0)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = { shareJob?.cancel() }) { Text("Cancel") }
                }
            }
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
            // The date and time head the panel: they left the top bar to make room for its icons,
            // and are known from the timeline without waiting for the details to load.
            item?.let {
                Text(JustifiedGrid.labelFor(it), style = MaterialTheme.typography.titleMedium)
                Text(
                    JustifiedGrid.timeOf(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail == null) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall)
                return@Column
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
