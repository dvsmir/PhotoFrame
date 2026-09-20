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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.framealt.data.StoredFrame

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onConnectFrame: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FrameAlt") },
                actions = {
                    IconButton(onClick = onOpenDiagnostics) {
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
                EmptyState(onConnectFrame)
            } else {
                PairedState(state, state.frame, onOpenDetails, onRefresh)
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
private fun PairedState(
    state: HomeState,
    frame: StoredFrame,
    onOpenDetails: () -> Unit,
    onRefresh: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))

    Card(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth()) {
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

    Button(
        onClick = { /* Phase 3: the photo picker lands here. */ },
        modifier = Modifier.fillMaxWidth(),
        enabled = false,
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Add photos")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Sending arrives in the next step. For now this screen proves the phone can find " +
            "and talk to your frame.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Start,
    )

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Check the connection")
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth()) {
        Text("Connection details")
    }
    Spacer(Modifier.height(24.dp))
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
