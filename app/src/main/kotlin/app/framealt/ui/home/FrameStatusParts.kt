package app.framealt.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.framealt.data.QueueItem
import app.framealt.ui.describeQueueFailure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The status chip on Home's frame card and on the Frame screen. */
@Composable
fun StatusChip(status: FrameStatus) {
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

/** Byte progress of the batch in flight, 0..1. */
fun ActivityState.progress(): Float = if (totalBytes == 0L) 0f else sentBytes.toFloat() / totalBytes

/** The full Activity section of the Frame screen. */
@Composable
fun ActivitySection(
    state: HomeState,
    frameName: String,
    onSendNow: () -> Unit,
    onRetry: (QueueItem) -> Unit,
    onRemove: (QueueItem) -> Unit,
) {
    val activity = state.activity
    if (activity.isEmpty) {
        Text(
            "Nothing sent yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

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
                    LinearProgressIndicator(progress = { activity.progress() }, modifier = Modifier.fillMaxWidth())
                } else if (activity.waiting > 0) {
                    Text(waitingLine(activity.waiting, state.status, frameName), style = MaterialTheme.typography.bodyMedium)
                    if (state.status != FrameStatus.NO_WIFI) {
                        TextButton(onClick = onSendNow) { Text("Send now") }
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
                    TextButton(onClick = { onRemove(item) }) { Text("Remove") }
                    TextButton(onClick = { onRetry(item) }) { Text("Retry") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    activity.recent.forEach { batch ->
        Text(
            recentLine(batch),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

private fun waitingLine(count: Int, status: FrameStatus, frameName: String): String {
    val photos = if (count == 1) "1 photo" else "$count photos"
    return if (status == FrameStatus.NO_WIFI) "$photos waiting for Wi-Fi at home." else "$photos waiting to go to $frameName."
}

private fun recentLine(batch: BatchSummary): String =
    (if (batch.sent == 1) "1 photo sent" else "${batch.sent} photos sent") + " · " + whenText(batch.completedAt)

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
