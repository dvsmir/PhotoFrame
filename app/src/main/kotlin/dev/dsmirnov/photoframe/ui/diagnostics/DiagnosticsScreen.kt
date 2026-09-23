package dev.dsmirnov.photoframe.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsmirnov.photoframe.device.WifiSocketFactory
import dev.dsmirnov.photoframe.diag.EventLog

/**
 * The screen that makes invisible failures visible.
 *
 * Blocked multicast, a guest SSID, a sleeping frame and a missing permission all present to
 * the user as the same "can't reach the frame". This is where they can be told apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    eventLog: EventLog,
    socketFactory: WifiSocketFactory,
    onBack: () -> Unit,
) {
    val events by eventLog.events.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Network", style = MaterialTheme.typography.titleMedium)
            Text(socketFactory.describe(), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { copyToClipboard(context, eventLog.dump()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy diagnostics")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Contains no keys, friend codes or photo content. Frame identifiers are shortened.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("Recent events", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (events.isEmpty()) {
                Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(events.asReversed()) { event ->
                        Text(
                            event.toString(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            color = when (event.level) {
                                EventLog.Level.ERROR -> MaterialTheme.colorScheme.error
                                EventLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("Photo Frame diagnostics", text))
}
