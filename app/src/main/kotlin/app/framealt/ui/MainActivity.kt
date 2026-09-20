package app.framealt.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.framealt.AppContainer
import app.framealt.FrameAltApp
import app.framealt.ui.theme.FrameAltTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as FrameAltApp).container
        setContent {
            FrameAltTheme {
                FrameAltRoot(container)
            }
        }
    }
}

@Composable
private fun FrameAltRoot(container: AppContainer) {
    Scaffold { padding ->
        AppNavigation(container, Modifier.padding(padding))
    }
}
