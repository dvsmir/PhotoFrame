package app.framealt.ui.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.framealt.ui.home.HomeState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDetailsScreen(
    state: HomeState,
    onBack: () -> Unit,
    onForget: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val frame = state.frame
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

            Spacer(Modifier.height(8.dp))
            Detail("Name", frame.displayName)
            Detail("Placement", frame.placement.ifBlank { "—" })
            Detail("Address", "${frame.host}:${frame.port}")
            Detail("Resolution", "${frame.width} × ${frame.height}")
            Detail("Protocol version", frame.protocolVersion.takeIf { it > 0 }?.toString() ?: "—")
            Detail("Last contact", formatTime(frame.lastSeenAtMillis))

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Detail("View photos", if (frame.canView) "Granted" else "Not granted")
            Detail("Manage photos", if (frame.canManage) "Granted" else "Not granted")

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Identity", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // Truncated on purpose: the full key adds nothing on screen and turns any
            // screenshot into a disclosure.
            Detail("Frame ID", frame.peerId.take(16) + "…")
            Detail("Certificate issuer", frame.issuer.take(16) + "…")
            Detail("Your name on the frame", state.senderName)

            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = { confirming = true },
                modifier = Modifier.fillMaxWidth(),
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
                    "Removing the frame deletes FrameAlt's pairing. To send again you'll need " +
                        "a new friend code from the frame. Photos already on the frame are not " +
                        "affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onForget()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep") }
            },
        )
    }
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
