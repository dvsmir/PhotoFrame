package app.framealt.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PhotoActions(
    val onBack: () -> Unit,
    val onSave: () -> Unit,
    val onDisplayNow: () -> Unit,
    val onSetVisible: (Boolean) -> Unit,
    val onAskDelete: () -> Unit,
    val onDismissDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onMessageShown: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PhotoScreen(state: PhotoState, actions: PhotoActions) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        actions.onMessageShown()
    }
    LaunchedEffect(state.gone) { if (state.gone) actions.onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.frameName) },
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.bitmap?.let { it.width.toFloat() / it.height } ?: 1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.bitmap != null -> Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = state.caption.ifBlank { "Photo on the frame" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.loading -> CircularProgressIndicator()
                    else -> Text(state.unavailable.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(12.dp))
            if (state.caption.isNotBlank()) {
                Text(state.caption, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            state.item?.let { item ->
                Text("Taken ${date(item.capturedAtMillis)}", style = MaterialTheme.typography.bodyMedium)
                Text("Received ${date(item.receivedAtMillis)}", style = MaterialTheme.typography.bodyMedium)
                if (!item.visible) {
                    Text(
                        "Hidden from the slideshow",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onSave, enabled = state.bitmap != null) { Text("Save to phone") }
                if (state.canManage && state.item != null) {
                    OutlinedButton(onClick = actions.onDisplayNow, enabled = !state.working) { Text("Show on frame now") }
                    OutlinedButton(onClick = { actions.onSetVisible(!state.item.visible) }, enabled = !state.working) {
                        Text(if (state.item.visible) "Hide" else "Show")
                    }
                    OutlinedButton(
                        onClick = actions.onAskDelete,
                        enabled = !state.working,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
            }
            Spacer(Modifier.height(24.dp))
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

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

private fun date(millis: Long): String =
    if (millis <= 0) "—" else DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
