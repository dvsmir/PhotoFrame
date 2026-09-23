package app.framealt.ui.details

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.framealt.data.QueueItem
import app.framealt.ui.home.ActivitySection
import app.framealt.ui.home.FrameStatus
import app.framealt.ui.home.HomeState
import app.framealt.ui.home.StatusChip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everything the Frame screen can ask for. */
class FrameActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSendNow: () -> Unit,
    val onRetry: (QueueItem) -> Unit,
    val onRemove: (QueueItem) -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onForget: () -> Unit,
)

/**
 * Everything about the frame that is not needed every time: status and its reason, send
 * activity, connection details, the diagnostics log, and removing the frame. `UX.md` §3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameScreen(state: HomeState, actions: FrameActions) {
    var confirming by remember { mutableStateOf(false) }
    val frame = state.frame

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(frame?.displayName ?: "Frame") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            if (frame == null) {
                Spacer(Modifier.height(24.dp))
                Text("No frame is connected.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            // --- status ---
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(state.status)
            }
            if (state.message != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = actions.onRefresh,
                enabled = state.status != FrameStatus.CHECKING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Check the connection")
            }

            Section("Activity")
            ActivitySection(state, frame.displayName, actions.onSendNow, actions.onRetry, actions.onRemove)

            Section("Connection details")
            Detail("Placement", frame.placement.ifBlank { "—" })
            Detail("Address", "${frame.host}:${frame.port}")
            Detail("Resolution", "${frame.width} × ${frame.height}")
            Detail("Protocol version", frame.protocolVersion.takeIf { it > 0 }?.toString() ?: "—")
            Detail("Last contact", formatTime(frame.lastSeenAtMillis))
            Detail("View photos", if (frame.canView) "Granted" else "Not granted")
            Detail("Manage photos", if (frame.canManage) "Granted" else "Not granted")
            // Truncated on purpose: the full key adds nothing on screen and turns any
            // screenshot into a disclosure.
            Detail("Frame ID", frame.peerId.take(16) + "…")
            Detail("Certificate issuer", frame.issuer.take(16) + "…")
            Detail("Your name on the frame", state.senderName)

            Section("Troubleshooting")
            OutlinedButton(onClick = actions.onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Diagnostics log")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirming = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Remove this frame")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove this frame?") },
            text = {
                Text(
                    "Removing the frame deletes Photo Frame's pairing. To send again you'll need " +
                        "a new friend code from the frame. Photos already on the frame are not " +
                        "affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    actions.onForget()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

private val FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

private fun formatTime(millis: Long): String =
    if (millis <= 0) "Never" else FORMATTER.format(Instant.ofEpochMilli(millis))
