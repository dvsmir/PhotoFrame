package app.framealt.ui.connect

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.framealt.protocol.DiscoveredFrame

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: ConnectState,
    onScan: () -> Unit,
    onSelect: (DiscoveredFrame) -> Unit,
    onManualEntry: () -> Unit,
    onFriendCodeChange: (String) -> Unit,
    onSenderNameChange: (String) -> Unit,
    onPair: () -> Unit,
    onBack: () -> Unit,
    onPaired: () -> Unit,
) {
    LaunchedEffect(state.pairedFrameName) {
        if (state.pairedFrameName != null) onPaired()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect your frame") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            when (state.step) {
                ConnectStep.FIND -> FindStep(state, onScan, onSelect, onManualEntry)
                ConnectStep.MANUAL -> ManualStep(state)
                ConnectStep.CODE -> CodeStep(state, onFriendCodeChange, onSenderNameChange, onPair)
            }

            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FindStep(
    state: ConnectState,
    onScan: () -> Unit,
    onSelect: (DiscoveredFrame) -> Unit,
    onManualEntry: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))

    if (state.scanning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text("Looking for frames on ${state.networkName}…")
        }
        return
    }

    if (state.found.isEmpty()) {
        Text("No frames found.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Check that the frame is on and connected to the same Wi-Fi as this phone. Some " +
                "routers block device discovery — you can enter the frame's address by hand " +
                "instead.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The frame's address is on the frame under Settings → About.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan again") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
            Text("Enter an address")
        }
        return
    }

    Text("Choose your frame", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    for (frame in state.found) {
        Card(
            onClick = { onSelect(frame) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Frame at ${frame.host}", style = MaterialTheme.typography.titleMedium)
                // The instance name is a 63-character hex blob; showing it raw helps nobody.
                Text(
                    "ID ${frame.instance.take(8)}…  ·  port ${frame.port}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onManualEntry) { Text("Enter an address instead") }
}

@Composable
private fun ManualStep(state: ConnectState) {
    Spacer(Modifier.height(16.dp))
    Text("Enter the frame's address", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "You'll find the address and port on the frame under Settings → About.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "Manual entry is wired up in the next step; discovery covers the normal case.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text("Host: ${state.manualHost.ifBlank { "—" }}   Port: ${state.manualPort.ifBlank { "—" }}")
}

@Composable
private fun CodeStep(
    state: ConnectState,
    onFriendCodeChange: (String) -> Unit,
    onSenderNameChange: (String) -> Unit,
    onPair: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text("Enter the friend code", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "On the frame, choose Add friend. Type the code it shows.",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.endpoint?.let {
        Spacer(Modifier.height(4.dp))
        Text("Frame at $it", style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = state.friendCode,
        onValueChange = onFriendCodeChange,
        label = { Text("Friend code") },
        placeholder = { Text("1234 5678 90") },
        singleLine = true,
        enabled = !state.pairing,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = state.senderName,
        onValueChange = onSenderNameChange,
        label = { Text("Your name on the frame") },
        supportingText = { Text("Shown next to photos you send.") },
        singleLine = true,
        enabled = !state.pairing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onPair,
        enabled = !state.pairing && state.endpoint != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.pairing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text("Connecting…")
        } else {
            Text("Connect")
        }
    }
}
