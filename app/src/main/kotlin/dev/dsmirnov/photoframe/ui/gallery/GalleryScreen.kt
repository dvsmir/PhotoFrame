package dev.dsmirnov.photoframe.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dsmirnov.photoframe.gallery.MediaFilter
import dev.dsmirnov.photoframe.protocol.client.MediaItem
import dev.dsmirnov.photoframe.protocol.client.MediaType

/** Everything the gallery screen can ask for. */
class GalleryActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onRequestAccess: () -> Unit,
    val onCancelRequest: () -> Unit,
    val onOpen: (MediaItem) -> Unit,
    val onToggle: (Long) -> Unit,
    val onStartSelection: (Long) -> Unit,
    val onClearSelection: () -> Unit,
    val onSelectSentFromThisPhone: () -> Unit,
    val onFilter: (MediaFilter) -> Unit,
    val onAskDelete: () -> Unit,
    val onDismissDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onSetVisibility: (Boolean) -> Unit,
    val onNeedThumbnail: (Long) -> Unit,
    val onMessageShown: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(state: GalleryState, actions: GalleryActions) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        actions.onMessageShown()
    }

    Scaffold(
        topBar = { if (state.selecting) SelectionBar(state, actions) else NormalBar(state, actions) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.access) {
                GalleryAccess.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                GalleryAccess.NEEDS_ACCESS -> AccessNeeded(state, actions)
                GalleryAccess.WAITING -> Waiting(actions)
                GalleryAccess.TIMED_OUT -> Message(
                    "No response from the frame yet. You can leave this and try again later — " +
                        "the request stays pending on the frame.",
                    "Ask again" to actions.onRequestAccess,
                )
                GalleryAccess.TOO_OLD -> Message(
                    "This frame's software is too old for Photo Frame to list its photos. Sending still works.",
                    null,
                )
                GalleryAccess.FAILED -> Message(state.failure ?: "Couldn't reach ${state.frameName}.", "Try again" to actions.onRefresh)
                GalleryAccess.READY -> Grid(state, actions)
            }
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    if (state.confirmingDelete) {
        val count = state.selection.size
        AlertDialog(
            onDismissRequest = actions.onDismissDelete,
            title = { Text("Delete ${items(count)} from ${state.frameName}?") },
            text = { Text("They'll be removed from the frame for everyone. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = actions.onConfirmDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = actions.onDismissDelete) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalBar(state: GalleryState, actions: GalleryActions) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("On the frame") },
        navigationIcon = {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            if (state.access == GalleryAccess.READY) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    FilterItem("Show all", MediaFilter.ALL, state.filter) { menu = false; actions.onFilter(it) }
                    FilterItem("Photos only", MediaFilter.PHOTOS, state.filter) { menu = false; actions.onFilter(it) }
                    FilterItem("Videos only", MediaFilter.VIDEOS, state.filter) { menu = false; actions.onFilter(it) }
                    if (state.canManage) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Select photos sent from this phone") },
                            onClick = {
                                menu = false
                                actions.onSelectSentFromThisPhone()
                            },
                        )
                    }
                }
            }
        },
    )
}

/** One filter choice; the active one carries a check mark. */
@Composable
private fun FilterItem(label: String, filter: MediaFilter, current: MediaFilter, onPick: (MediaFilter) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onPick(filter) },
        leadingIcon = {
            if (filter == current) Icon(Icons.Default.Check, contentDescription = "Selected") else Spacer(Modifier.size(24.dp))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(state: GalleryState, actions: GalleryActions) {
    TopAppBar(
        title = { Text("${state.selection.size} selected") },
        navigationIcon = {
            IconButton(onClick = actions.onClearSelection) { Icon(Icons.Default.Close, contentDescription = "Clear selection") }
        },
        actions = {
            IconButton(onClick = { actions.onSetVisibility(false) }, enabled = !state.working) {
                Icon(Icons.Default.VisibilityOff, contentDescription = "Hide from the slideshow")
            }
            IconButton(onClick = { actions.onSetVisibility(true) }, enabled = !state.working) {
                Icon(Icons.Default.Visibility, contentDescription = "Show in the slideshow")
            }
            IconButton(onClick = actions.onAskDelete, enabled = !state.working) {
                Icon(Icons.Default.Delete, contentDescription = "Delete from the frame")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Grid(state: GalleryState, actions: GalleryActions) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Header(state, actions) }
            items(state.shown, key = { it.id }) { item ->
                Tile(item, state.thumbnails[item.id], selected = item.id in state.selection, state.selecting, actions)
            }
        }
    }
}

@Composable
private fun Header(state: GalleryState, actions: GalleryActions) {
    Column(Modifier.padding(bottom = 8.dp)) {
        val photos = state.items.count { it.type == MediaType.PHOTO }
        val videos = state.items.count { it.type == MediaType.VIDEO }
        val greetings = state.items.count { it.type == MediaType.GREETING }
        Text(
            listOfNotNull(
                if (photos == 1) "1 photo" else "$photos photos",
                videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
                greetings.takeIf { it > 0 }?.let { if (it == 1) "1 greeting" else "$it greetings" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.filter != MediaFilter.ALL) {
            val what = if (state.filter == MediaFilter.VIDEOS) "video" else "photo"
            val count = state.shown.size
            Text(
                if (count == 0) "No ${what}s on the frame." else "Showing $count $what${if (count == 1) "" else "s"} of ${state.items.size} items.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { actions.onFilter(MediaFilter.ALL) }) { Text("Show all") }
        }
        if (!state.canManage) {
            Spacer(Modifier.height(4.dp))
            Text(
                "To delete or hide photos, allow management on the frame.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = actions.onRequestAccess) { Text("Request access") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    item: MediaItem,
    thumbnail: android.graphics.Bitmap?,
    selected: Boolean,
    selecting: Boolean,
    actions: GalleryActions,
) {
    LaunchedEffect(item.id, thumbnail == null) {
        if (thumbnail == null) actions.onNeedThumbnail(item.id)
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = describeTile(item, selected) }
            .combinedClickable(
                onClick = { if (selecting) actions.onToggle(item.id) else actions.onOpen(item) },
                onLongClick = { actions.onStartSelection(item.id) },
            ),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val badge = when {
            item.type == MediaType.VIDEO -> "Video"
            item.type == MediaType.GREETING -> "Greeting"
            !item.visible -> "Hidden"
            else -> null
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            )
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

private fun describeTile(item: MediaItem, selected: Boolean): String {
    val kind = when (item.type) {
        MediaType.VIDEO -> "Video"
        MediaType.GREETING -> "Greeting"
        else -> "Photo"
    }
    return listOfNotNull(kind, "hidden".takeIf { !item.visible }, "selected".takeIf { selected }).joinToString(", ")
}

@Composable
private fun AccessNeeded(state: GalleryState, actions: GalleryActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("See what's on your frame", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Photo Frame can show the photos on ${state.frameName} and let you delete, hide or show them. " +
                "The frame's owner has to allow this once, on the frame itself.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = actions.onRequestAccess, modifier = Modifier.fillMaxWidth()) { Text("Request access") }
    }
}

@Composable
private fun Waiting(actions: GalleryActions) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text("Waiting for approval — go to the frame and tap Allow.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = actions.onCancelRequest) { Text("Cancel") }
    }
}

@Composable
private fun Message(text: String, action: Pair<String, () -> Unit>?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = action.second) { Text(action.first) }
        }
    }
}
