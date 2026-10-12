package com.della.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.della.app.ui.DellaRoot
import com.della.app.ui.common.LocalContainer
import com.della.app.ui.common.PrimaryButton
import com.della.app.ui.common.Wordmark
import com.della.app.ui.theme.Della
import com.della.app.ui.theme.DellaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DellaApp).container
        setContent {
            DellaTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    Startup(container)
                }
            }
        }
    }
}

private enum class Phase { CHECKING, SEEDING, READY, FAILED }

@Composable
private fun Startup(container: AppContainer) {
    var phase by remember { mutableStateOf(Phase.CHECKING) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        try {
            if (container.needsDemoSeed()) {
                phase = Phase.SEEDING
                withContext(Dispatchers.IO) { container.demoSeeder().seed { progress = it } }
            }
            phase = Phase.READY
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            phase = Phase.FAILED
        }
    }

    when (phase) {
        Phase.READY -> DellaRoot()
        else -> Column(
            Modifier.fillMaxSize().background(Della.Background).padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Wordmark(size = 34)
            Spacer(Modifier.height(10.dp))
            Text("Data-Enabled Local Business & Livelihood Assistant", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
            Spacer(Modifier.height(36.dp))
            when (phase) {
                Phase.SEEDING -> {
                    Text("Preparing the Macha Bakehouse demo…", style = MaterialTheme.typography.bodyMedium, color = Della.Text)
                    Spacer(Modifier.height(4.dp))
                    Text("Simulating 10 weeks of fictional sales, baking, purchasing and expenses.", style = MaterialTheme.typography.bodySmall, color = Della.TextMuted)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Della.Accent, trackColor = Della.Panel)
                }
                Phase.FAILED -> {
                    Text("Could not open data: $error", color = Della.Danger, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Try again") { phase = Phase.CHECKING; attempt++ }
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton("Reset this workspace") { container.resetCurrentWorkspace() }
                }
                else -> Spacer(Modifier.width(1.dp))
            }
        }
    }
}
