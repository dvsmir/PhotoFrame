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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.framealt.AppContainer
import app.framealt.ui.connect.ConnectScreen
import app.framealt.ui.connect.ConnectViewModel
import app.framealt.ui.details.FrameActions
import app.framealt.ui.details.FrameScreen
import app.framealt.ui.diagnostics.DiagnosticsScreen
import app.framealt.ui.gallery.GalleryActions
import app.framealt.ui.gallery.GalleryScreen
import app.framealt.ui.gallery.GalleryViewModel
import app.framealt.ui.gallery.PhotoActions
import app.framealt.ui.gallery.PhotoScreen
import app.framealt.ui.gallery.PhotoViewModel
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
    const val GALLERY = "gallery"
    const val PHOTO = "photo/{id}"

    fun photo(id: Long) = "photo/$id"
}

@Composable
fun AppNavigation(container: AppContainer, reviewRequest: Int = 0, modifier: Modifier = Modifier) {
    val navController: NavHostController = rememberNavController()

    LaunchedEffect(reviewRequest) {
        if (reviewRequest > 0 && container.pendingPicks.isNotEmpty()) {
            navController.navigate(Routes.REVIEW) { launchSingleTop = true }
        }
    }

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
                    onAddPhotos = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    onOpenGallery = { navController.navigate(Routes.GALLERY) },
                    onOpenFrame = { navController.navigate(Routes.DETAILS) },
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
            val model: ReviewViewModel = viewModel {
                val picks = container.pendingPicks
                container.pendingPicks = emptyList()
                ReviewViewModel(container, picks)
            }
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

        composable(Routes.GALLERY) {
            val model: GalleryViewModel = viewModel { GalleryViewModel(container) }
            val state by model.state.collectAsState()
            GalleryScreen(
                state = state,
                actions = GalleryActions(
                    onBack = { navController.popBackStack() },
                    onRefresh = model::refresh,
                    onRequestAccess = model::requestAccess,
                    onCancelRequest = model::cancelRequest,
                    onOpen = { navController.navigate(Routes.photo(it.id)) },
                    onToggle = model::toggle,
                    onStartSelection = model::startSelection,
                    onClearSelection = model::clearSelection,
                    onSelectSentFromThisPhone = model::selectSentFromThisPhone,
                    onAskDelete = model::askDelete,
                    onDismissDelete = model::dismissDelete,
                    onConfirmDelete = model::confirmDelete,
                    onSetVisibility = model::setVisibility,
                    onNeedThumbnail = model::requestThumbnail,
                    onMessageShown = model::messageShown,
                ),
            )
        }

        composable(
            Routes.PHOTO,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val context = LocalContext.current
            val model: PhotoViewModel = viewModel { PhotoViewModel(container, id) }
            val state by model.state.collectAsState()
            PhotoScreen(
                state = state,
                actions = PhotoActions(
                    onBack = { navController.popBackStack() },
                    onSave = { model.save(context) },
                    onDisplayNow = model::displayNow,
                    onSetVisible = model::setVisible,
                    onAskDelete = model::askDelete,
                    onDismissDelete = model::dismissDelete,
                    onConfirmDelete = model::confirmDelete,
                    onMessageShown = model::messageShown,
                ),
            )
        }

        composable(Routes.DETAILS) {
            val model: HomeViewModel = viewModel { HomeViewModel(container) }
            val state by model.state.collectAsState()
            FrameScreen(
                state = state,
                actions = FrameActions(
                    onBack = { navController.popBackStack() },
                    onRefresh = model::refresh,
                    onSendNow = model::sendNow,
                    onRetry = model::retry,
                    onRemove = model::remove,
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onForget = {
                        model.forget()
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                ),
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
