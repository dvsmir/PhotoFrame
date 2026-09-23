package dev.dsmirnov.photoframe.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import dev.dsmirnov.photoframe.AppContainer
import dev.dsmirnov.photoframe.PhotoFrameApp
import dev.dsmirnov.photoframe.ui.theme.PhotoFrameTheme

class MainActivity : ComponentActivity() {

    /** Bumped each time something asks to open Review & Send with `pendingPicks`. */
    private val reviewRequests = mutableIntStateOf(0)

    private val container: AppContainer get() = (application as PhotoFrameApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            PhotoFrameTheme {
                PhotoFrameRoot(container, reviewRequests.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_REVIEW_PENDING && container.pendingPicks.isNotEmpty()) {
            reviewRequests.intValue++
        }
    }

    companion object {
        /**
         * Opens Review & Send with whatever is in `AppContainer.pendingPicks`. The share sheet
         * (Phase 4) and the debug build's test hook both enter the send flow this way.
         */
        const val ACTION_REVIEW_PENDING = "dev.dsmirnov.photoframe.action.REVIEW_PENDING"
    }
}

@Composable
private fun PhotoFrameRoot(container: AppContainer, reviewRequest: Int) {
    Scaffold { padding ->
        AppNavigation(container, reviewRequest, Modifier.padding(padding))
    }
}
