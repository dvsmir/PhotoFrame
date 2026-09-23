package app.framealt.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.framealt.data.QueueItem
import app.framealt.data.StoredFrame
import app.framealt.ui.describeQueueFailure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything Home can ask for. Grouped so the screen's signature stays readable. */
class HomeActions(
    val onConnectFrame: () -> Unit,
    val onOpenDetails: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onRefresh: () -> Unit,
    val onAddPhotos: () -> Unit,
    val onSendNow: () -> Unit,
    val onRetry: (QueueItem) -> Unit,
    val onRemove: (QueueItem) -> Unit,
    val onOpenGallery: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FrameAlt") },
                actions = {
                    IconButton(onClick = actions.onOpenDiagnostics) {
                        Icon(Icons.Default.Info, contentDescription = "Diagnostics")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.frame == null) {
                EmptyState(actions.onConnectFrame)
            } else {
                PairedState(state, state.frame, actions)
            }
        }
    }
}

@Composable
private fun EmptyState(onConnectFrame: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Text("Connect your frame", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "FrameAlt sends photos straight to your Frameo frame over Wi-Fi. No account, no " +
            "subscription, no limit on how many photos you send at once.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(24.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Before you start", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "On the frame: open the menu, choose Add friend, and leave the code on screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your phone and the frame must be on the same Wi-Fi network.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(onClick = onConnectFrame, modifier = Modifier.fillMaxWidth()) {
        Text("Find my frame")
    }
}

@Composable
private fun PairedState(state: HomeState, frame: StoredFrame, actions: HomeActions) {
    Spacer(Modifier.height(16.dp))

    Card(onClick = actions.onOpenDetails, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(frame.displayName, style = MaterialTheme.typography.titleLarge)
                    if (frame.placement.isNotBlank()) {
                        Text(frame.placement, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "${frame.width} × ${frame.height}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusChip(state.status)
            }

            if (state.message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    Button(onClick = actions.onAddPhotos, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Add photos")
    }

    if (!state.activity.isEmpty) {
        Spacer(Modifier.height(24.dp))
        Activity(state, frame.displayName, actions)
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = actions.onOpenGallery, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("On the frame")
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = actions.onRefresh, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Check the connection")
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = actions.onOpenDetails, modifier = Modifier.fillMaxWidth()) {
        Text("Connection details")
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Activity(state: HomeState, frameName: String, actions: HomeActions) {
    val activity = state.activity
    Text("Activity", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    if (activity.total > 0) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (activity.sendingNow) {
                    Text(
                        "Sending ${minOf(activity.done + 1, activity.total)} of ${activity.total} — " +
                            "${megabytes(activity.sentBytes)} of ${megabytes(activity.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (activity.totalBytes == 0L) 0f else activity.sentBytes.toFloat() / activity.totalBytes
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (activity.waiting > 0) {
                    val photos = if (activity.waiting == 1) "1 photo" else "${activity.waiting} photos"
                    Text(
                        if (state.status == FrameStatus.NO_WIFI) {
                            "$photos waiting for Wi-Fi at home."
                        } else {
                            "$photos waiting to go to $frameName."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.status != FrameStatus.NO_WIFI) {
                        TextButton(onClick = actions.onSendNow) { Text("Send now") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    activity.failed.forEach { item ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    describeQueueFailure(item, frameName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = { actions.onRemove(item) }) { Text("Remove") }
                    TextButton(onClick = { actions.onRetry(item) }) { Text("Retry") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    activity.recent.forEach { batch ->
        Text(
            (if (batch.sent == 1) "1 photo sent" else "${batch.sent} photos sent") + " · " + whenText(batch.completedAt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)

private fun whenText(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now(zone)
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (at.toLocalDate()) {
        today -> "today $time"
        today.minusDays(1) -> "yesterday $time"
        else -> at.format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }
}

@Composable
private fun StatusChip(status: FrameStatus) {
    if (status == FrameStatus.CHECKING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text("Checking…", style = MaterialTheme.typography.labelMedium)
        }
        return
    }

    val (label, color) = when (status) {
        FrameStatus.READY -> "Ready" to Color(0xFF2E7D32)
        FrameStatus.OFFLINE -> "Not reachable" to MaterialTheme.colorScheme.error
        FrameStatus.NO_WIFI -> "No Wi-Fi" to MaterialTheme.colorScheme.error
        else -> "Unknown" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
    )
}
