package app.framealt.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.framealt.AppContainer
import app.framealt.ui.connect.ConnectScreen
import app.framealt.ui.connect.ConnectViewModel
import app.framealt.ui.details.ConnectionDetailsScreen
import app.framealt.ui.diagnostics.DiagnosticsScreen
import app.framealt.ui.home.HomeActions
import app.framealt.ui.home.HomeScreen
import app.framealt.ui.home.HomeViewModel
import app.framealt.ui.review.ReviewScreen
import app.framealt.ui.review.ReviewViewModel

object Routes {
    const val HOME = "home"
    const val CONNECT = "connect"
    const val DETAILS = "details"
    const val DIAGNOSTICS = "diagnostics"
    const val REVIEW = "review"
}

@Composable
fun AppNavigation(container: AppContainer, modifier: Modifier = Modifier) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            val model: HomeViewModel = viewModel { HomeViewModel(container) }
            val state by model.state.collectAsState()
            // The system photo picker: no storage permission, and the URIs it returns stay
            // readable long enough for the review screen to prepare them.
            val picker = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
                if (uris.isNotEmpty()) {
                    container.pendingPicks = uris
                    navController.navigate(Routes.REVIEW)
                }
            }
            HomeScreen(
                state = state,
                actions = HomeActions(
                    onConnectFrame = { navController.navigate(Routes.CONNECT) },
                    onOpenDetails = { navController.navigate(Routes.DETAILS) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onRefresh = model::refresh,
                    onAddPhotos = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    onSendNow = model::sendNow,
                    onRetry = model::retry,
                    onRemove = model::remove,
                ),
            )
        }

        composable(Routes.CONNECT) {
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
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }

        composable(Routes.REVIEW) {
            val model: ReviewViewModel = viewModel { ReviewViewModel(container) }
            val state by model.state.collectAsState()
            // Asked once, at the moment it becomes relevant. Denial changes nothing but the
            // progress notification, so the send goes ahead either way.
            val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) { model.send() }
            val context = LocalContext.current
            LaunchedEffect(state.done) {
                if (state.done) navController.popBackStack(Routes.HOME, inclusive = false)
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
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.DETAILS) {
            val model: HomeViewModel = viewModel { HomeViewModel(container) }
            val state by model.state.collectAsState()
            ConnectionDetailsScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onForget = {
                    model.forget()
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }

        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                eventLog = container.eventLog,
                socketFactory = container.socketFactory,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
