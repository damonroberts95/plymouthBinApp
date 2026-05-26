package com.plymouthbins.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.plymouthbins.app.data.Prefs
import com.plymouthbins.app.data.Updater
import com.plymouthbins.app.ui.AddressCaptureScreen
import com.plymouthbins.app.ui.BinTheme
import com.plymouthbins.app.ui.DebugScreen
import com.plymouthbins.app.ui.LogScreen
import com.plymouthbins.app.ui.MainScreen
import com.plymouthbins.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — user can re-enable from settings */ }

    // Play Store builds (ENABLE_UPDATER=false) use Play in-app updates instead of
    // the GitHub-release auto-updater. ActivityResultLauncher must be registered
    // before STARTED state, so initialise as a field.
    private val playUpdater: PlayUpdateHelper? =
        if (!Updater.isEnabled) PlayUpdateHelper(this) else null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        maybeRequestPostNotifications()
        setContent {
            BinTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        playUpdater?.checkAndPrompt()
    }

    private fun maybeRequestPostNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val prefs by Prefs.flow(ctx).collectAsState(initial = null)
    var routedInitial by remember { mutableStateOf(false) }

    LaunchedEffect(prefs) {
        if (!routedInitial && prefs != null) {
            routedInitial = true
            val needsOnboarding = prefs!!.uprn.isBlank() || prefs!!.collectiveKey.isBlank()
            if (needsOnboarding) {
                nav.navigate("capture?back=false") {
                    popUpTo("main") { inclusive = true }
                }
            }
        }
    }

    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScreen(
                onOpenSettings = { nav.navigate("settings") },
                onOpenLog = { nav.navigate("log") },
                onOpenCapture = { nav.navigate("capture?back=true") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onChangeAddress = { nav.navigate("capture?back=true") },
                onOpenLog = { nav.navigate("log") },
                onOpenDebug = { nav.navigate("debug") },
            )
        }
        composable("debug") {
            DebugScreen(onBack = { nav.popBackStack() })
        }
        composable("capture?back=true") {
            AddressCaptureScreen(
                showBack = true,
                onCaptured = { nav.popBackStack(route = "main", inclusive = false) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("capture?back=false") {
            AddressCaptureScreen(
                showBack = false,
                onCaptured = {
                    nav.navigate("main") { popUpTo("main") { inclusive = true } }
                },
                onBack = {},
            )
        }
        composable("log") {
            LogScreen(onBack = { nav.popBackStack() })
        }
    }
}
