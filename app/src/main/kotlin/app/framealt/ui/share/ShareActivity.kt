package app.framealt.ui.share

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.framealt.AppContainer
import app.framealt.FrameAltApp
import app.framealt.ui.connect.ConnectScreen
import app.framealt.ui.connect.ConnectViewModel
import app.framealt.ui.review.ReviewScreen
import app.framealt.ui.review.ReviewViewModel
import app.framealt.ui.theme.FrameAltTheme

private const val TAG = "share"

private object ShareRoutes {
    const val REVIEW = "review"
    const val NOT_PAIRED = "not-paired"
    const val CONNECT = "connect"
    const val NOTHING = "nothing"
}

/**
 * The share-sheet entry: `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with images.
 * `Spec/00 - Initial/03 - UX.md` §5.
 *
 * A separate activity rather than a route in [app.framealt.ui.MainActivity], for two reasons:
 *
 *  - **The URI grant.** Read access to shared items is granted to the activity that received
 *    them and lasts while it lives. Keeping preparation inside this activity means the grant
 *    covers it for the whole flow, including a detour through pairing.
 *  - **Where Back and Send lead.** Launched into the sharing app's task, finishing returns
 *    the user to Google Photos (or wherever they shared from), which is what UX §4 asks for.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as FrameAltApp).container
        val shared = readShare(intent, contentResolver)
        container.eventLog.info(TAG, "received ${shared.offered} item(s), ${shared.photos.size} of them image(s)")

        setContent {
            FrameAltTheme {
                ShareFlow(container, shared, onFinished = ::finish)
            }
        }
    }
}

@Composable
private fun ShareFlow(container: AppContainer, shared: SharedItems<android.net.Uri>, onFinished: () -> Unit) {
    // null while reading; a pairing is on disk, so this is one fast DataStore read.
    val paired by produceState<Boolean?>(null) { value = container.frameStore.current() != null }
    val start = when {
        shared.photos.isEmpty() -> ShareRoutes.NOTHING
        paired == null -> return
        paired == true -> ShareRoutes.REVIEW
        else -> ShareRoutes.NOT_PAIRED
    }
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = start) {
        composable(ShareRoutes.REVIEW) {
            val context = LocalContext.current
            val model: ReviewViewModel = viewModel { ReviewViewModel(container, shared.photos, shared.skipped) }
            val state by model.state.collectAsState()
            val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) { model.send() }
            LaunchedEffect(state.done) {
                if (!state.done) return@LaunchedEffect
                val photos = if (state.queued == 1) "1 photo" else "${state.queued} photos"
                Toast.makeText(context, "$photos queued for ${state.frameName}", Toast.LENGTH_LONG).show()
                onFinished()
            }
            ReviewScreen(
                state = state,
                onRemove = { model.remove(it.uri) },
                onCaptionChange = model::setCaption,
                onFitChange = model::setFit,
                onSend = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) model.send() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onBack = onFinished,
            )
        }

        composable(ShareRoutes.NOT_PAIRED) {
            Message(
                title = "Connect your frame first",
                body = "Photo Frame needs to pair with your Frameo frame before it can send photos. " +
                    "The photos you shared will be waiting once it's done.",
                primary = "Connect frame" to { navController.navigate(ShareRoutes.CONNECT) },
                onClose = onFinished,
            )
        }

        composable(ShareRoutes.CONNECT) {
            val model: ConnectViewModel = viewModel { ConnectViewModel(container) }
            val state by model.state.collectAsState()
            ConnectScreen(
                state = state,
                onScan = model::scan,
                onSelect = model::select,
                onManualEntry = model::enterManually,
                onFriendCodeChange = model::setFriendCode,
                onSenderNameChange = model::setSenderName,
                onPair = model::pair,
                onBack = { navController.popBackStack() },
                onPaired = {
                    // The held batch goes straight to review; Back from there leaves the share.
                    navController.navigate(ShareRoutes.REVIEW) {
                        popUpTo(ShareRoutes.NOT_PAIRED) { inclusive = true }
                    }
                },
            )
        }

        composable(ShareRoutes.NOTHING) {
            Message(
                title = "Nothing to send",
                body = "None of the shared items were photos. Photo Frame sends photos only.",
                primary = null,
                onClose = onFinished,
            )
        }
    }
}

@Composable
private fun Message(title: String, body: String, primary: Pair<String, () -> Unit>?, onClose: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            if (primary != null) {
                Button(onClick = primary.second, modifier = Modifier.fillMaxWidth()) { Text(primary.first) }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
