package app.framealt.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.framealt.data.StoredFrame

/** Everything Home can ask for. Everything else lives on the Frame screen (UX §3). */
class HomeActions(
    val onConnectFrame: () -> Unit,
    val onAddPhotos: () -> Unit,
    val onOpenGallery: () -> Unit,
    val onOpenFrame: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Photo Frame") }) }) { padding ->
        if (!state.loaded) return@Scaffold
        if (state.frame == null) {
            NotPaired(Modifier.padding(padding), actions.onConnectFrame)
        } else {
            Paired(Modifier.padding(padding), state, state.frame, actions)
        }
    }
}

/** One button, nothing else: there is nothing to do before a frame is connected. */
@Composable
private fun NotPaired(modifier: Modifier, onConnectFrame: () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onConnectFrame,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text("Connect your frame", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Paired(modifier: Modifier, state: HomeState, frame: StoredFrame, actions: HomeActions) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = actions.onAddPhotos,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Add photos", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedButton(
            onClick = actions.onOpenGallery,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("On the frame")
        }

        FrameCard(state, frame, actions.onOpenFrame)
        Spacer(Modifier.height(12.dp))
    }
}

/** The frame's name and status, nothing more; tapping it opens everything else (UX §3). */
@Composable
private fun FrameCard(state: HomeState, frame: StoredFrame, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(frame.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StatusChip(state.status)
        }
    }
}
