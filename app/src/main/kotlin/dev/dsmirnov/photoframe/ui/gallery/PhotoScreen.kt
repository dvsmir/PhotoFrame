package dev.dsmirnov.photoframe.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PhotoActions(
    val onBack: () -> Unit,
    val onShow: (Long) -> Unit,
    val onDisplayNow: () -> Unit,
    val onSetVisible: (Boolean) -> Unit,
    val onAskDelete: () -> Unit,
    val onDismissDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onMessageShown: () -> Unit,
)

/**
 * Photos from the frame, one per page; swipe for the next or previous one, in the grid's
 * order. Nothing scrolls vertically: the details and actions take the height they need and
 * the photo is scaled into the rest, so every action is always on screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoScreen(state: PhotoState, actions: PhotoActions) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        actions.onMessageShown()
    }
    LaunchedEffect(state.gone) { if (state.gone) actions.onBack() }

    val items = state.items
    val startPage = remember { items.indexOfFirst { it.id == state.currentId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startPage) { items.size }

    // Report the page once it settles, so a fling across several pages fetches only the end.
    LaunchedEffect(pager, items) {
        snapshotFlow { pager.settledPage }.collect { page ->
            items.getOrNull(page)?.let { actions.onShow(it.id) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.frameName)
                        if (items.isNotEmpty()) {
                            Text(
                                "${pager.currentPage + 1} of ${items.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalPager(
                state = pager,
                key = { items[it].id },
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val load = state.loads[items[page].id]) {
                        is PhotoLoad.Loaded -> Image(
                            bitmap = load.bitmap.asImageBitmap(),
                            contentDescription = load.caption.ifBlank { "Photo on the frame" },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        is PhotoLoad.Unavailable -> Text(load.text, style = MaterialTheme.typography.bodyLarge)
                        else -> CircularProgressIndicator()
                    }
                }
            }

            Details(state, actions)
        }
    }

    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = actions.onDismissDelete,
            title = { Text("Delete this photo from ${state.frameName}?") },
            text = { Text("It'll be removed from the frame for everyone. This can't be undone.") },
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

/** Caption, dates and actions for the photo on screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(state: PhotoState, actions: PhotoActions) {
    val item = state.current ?: return
    val caption = (state.loads[item.id] as? PhotoLoad.Loaded)?.caption.orEmpty()
    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        if (caption.isNotBlank()) {
            Text(caption, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val hidden = if (item.visible) "" else " · Hidden from the slideshow"
        Text(
            "Taken ${date(item.capturedAtMillis)} · Received ${date(item.receivedAtMillis)}$hidden",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.canManage) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onDisplayNow, enabled = !state.working) { Text("Show on frame now") }
                OutlinedButton(onClick = { actions.onSetVisible(!item.visible) }, enabled = !state.working) {
                    Text(if (item.visible) "Hide" else "Show")
                }
                OutlinedButton(
                    onClick = actions.onAskDelete,
                    enabled = !state.working,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

private fun date(millis: Long): String =
    if (millis <= 0) "—" else DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
