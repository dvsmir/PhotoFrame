package app.framealt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import app.framealt.ui.home.HomeScreen
import app.framealt.ui.home.HomeViewModel

object Routes {
    const val HOME = "home"
    const val CONNECT = "connect"
    const val DETAILS = "details"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun AppNavigation(container: AppContainer, modifier: Modifier = Modifier) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            val model: HomeViewModel = viewModel { HomeViewModel(container) }
            val state by model.state.collectAsState()
            HomeScreen(
                state = state,
                onConnectFrame = { navController.navigate(Routes.CONNECT) },
                onOpenDetails = { navController.navigate(Routes.DETAILS) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onRefresh = model::refresh,
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
