package io.github.akash904.photohost.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.akash904.photohost.media.BlurHashDecoder
import io.github.akash904.photohost.net.LibraryApi
import io.github.akash904.photohost.net.TrashItemDto
import kotlinx.coroutines.launch

/**
 * The trash.
 *
 * Deliberately a plain square grid rather than the justified timeline: this is a holding pen you
 * are reviewing, not a library you are browsing, and uniform tiles make "is this the one I meant to
 * delete" the easy question to answer.
 *
 * Thumbnails still render here, which only works because trashing is a soft delete -- the bytes and
 * the cached thumbnail both survive until a purge.
 */
@Composable
fun TrashScreen(api: LibraryApi, onChanged: () -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    val scope = rememberCoroutineScopeCompat()
    var items by remember { mutableStateOf<List<TrashItemDto>?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        items = api.trash()
        selected = emptySet()
    }

    LaunchedEffect(Unit) { reload() }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Delete everything in the trash?") },
            text = {
                Text(
                    "This removes the stored files and cannot be undone. Anything still on the " +
                        "phone that produced them is untouched, so a later backup could bring it back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    busy = true
                    scope.launch {
                        api.emptyTrash()
                        reload()
                        busy = false
                        onChanged()
                    }
                }) { Text("Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }

    val list = items

    // Opaque: this is drawn over the rest of the app now rather than inside the tab scaffold, and
    // a transparent column would let the photo grid show through behind the thumbnails.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Close") }
            Text(
                text = when {
                    list == null -> "Trash"
                    selected.isNotEmpty() -> "${selected.size} selected"
                    else -> "Trash · ${list.size}"
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (selected.isNotEmpty()) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            api.batch(selected.toList(), "restore")
                            reload()
                            busy = false
                            onChanged()
                        }
                    },
                ) { Text("Restore") }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            api.batch(selected.toList(), "purge")
                            reload()
                            busy = false
                            onChanged()
                        }
                    },
                ) { Text("Delete") }
            } else if (!list.isNullOrEmpty()) {
                TextButton(enabled = !busy, onClick = { confirmEmpty = true }) { Text("Empty") }
            }
        }

        when {
            list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading…") }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Trash is empty", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Deleted photos wait here until you empty it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.fillMaxSize().padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.id }) { item ->
                    TrashTile(
                        item = item,
                        api = api,
                        selected = item.id in selected,
                        onToggle = {
                            selected = if (item.id in selected) selected - item.id else selected + item.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashTile(
    item: TrashItemDto,
    api: LibraryApi,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val placeholder = remember(item.blurhash) {
        BlurHashDecoder.decode(item.blurhash)?.let { BitmapPainter(it.asImageBitmap()) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(api.thumbUrl(item.id))
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = if (selected) "☑" else "☐",
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
        )
        item.sourceAlbum?.let {
            Text(
                text = it,
                color = Color.White,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
            )
        }
    }
}

/** Small shim so the screen does not need a ViewModel just to launch a few one-shot calls. */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
