package app.framealt.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    state: ReviewState,
    onRemove: (ReviewPhoto) -> Unit,
    onCaptionChange: (String) -> Unit,
    onFitChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(photoCount(state.photos.size - state.failed.size) + " to ${state.frameName}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = { SendBar(state, onSend) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Options(state, onCaptionChange, onFitChange)
            }
            items(state.photos, key = { it.uri.toString() }) { photo ->
                PhotoTile(photo, onRemove)
            }
        }
    }
}

@Composable
private fun Options(state: ReviewState, onCaptionChange: (String) -> Unit, onFitChange: (Boolean) -> Unit) {
    Column {
        OutlinedTextField(
            value = state.caption,
            onValueChange = onCaptionChange,
            label = { Text("Caption (optional)") },
            supportingText = { Text("Added to every photo in this batch.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show the whole photo", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (state.fit) "The frame fits the whole photo on screen." else "The frame crops to fill the screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.fit, onCheckedChange = onFitChange)
        }

        if (state.duplicateCount > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.duplicateCount == 1) "1 of these was sent before." else "${state.duplicateCount} of these were sent before.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.failed.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SkippedSummary(state.failed)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SkippedSummary(failed: List<ReviewPhoto.Failed>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val count = failed.size
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(8.dp))
            Text(
                if (count == 1) "1 photo couldn't be prepared and was skipped." else "$count photos couldn't be prepared and were skipped.",
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            failed.forEach {
                Text(
                    "${it.displayName} couldn't be prepared — it may be too large or in a format FrameAlt can't read.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotoTile(photo: ReviewPhoto, onRemove: (ReviewPhoto) -> Unit) {
    val name = (photo as? ReviewPhoto.Ready)?.prepared?.displayName ?: (photo as? ReviewPhoto.Failed)?.displayName ?: "photo"
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        when (photo) {
            is ReviewPhoto.Preparing -> CircularProgressIndicator(
                Modifier
                    .align(Alignment.Center)
                    .size(24.dp),
                strokeWidth = 2.dp,
            )
            is ReviewPhoto.Failed -> Badge("Skipped", MaterialTheme.colorScheme.error, Modifier.align(Alignment.BottomStart))
            is ReviewPhoto.Ready -> if (photo.alreadySent) {
                Badge("Already sent", MaterialTheme.colorScheme.secondary, Modifier.align(Alignment.BottomStart))
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(32.dp),
        ) {
            IconButton(
                onClick = { onRemove(photo) },
                modifier = Modifier.semantics { contentDescription = "Remove $name from this batch" },
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.surface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(4.dp)
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SendBar(state: ReviewState, onSend: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = onSend,
            enabled = !state.isPreparing && !state.sending && state.usable.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(
                when {
                    state.isPreparing -> "Preparing ${state.preparedCount + 1} of ${state.photos.size}…"
                    state.usable.isEmpty() -> "Nothing to send"
                    else -> "Send " + photoCount(state.usable.size)
                },
            )
        }
    }
}

private fun photoCount(count: Int): String = if (count == 1) "1 photo" else "$count photos"
