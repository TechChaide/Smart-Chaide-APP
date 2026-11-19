package com.chaide.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.chaide.app.ui.screens.AddDeviceScreen
import com.chaide.app.ui.screens.DeviceDetailScreen
import com.chaide.app.ui.screens.FriendListScreen
import com.chaide.app.ui.screens.LiveMonitorScreen
import com.chaide.app.ui.screens.LoginScreen
import com.chaide.app.ui.screens.MainScreen
import com.chaide.app.ui.screens.MusicLibraryScreen
import com.chaide.app.ui.screens.NowPlayingScreen
import com.chaide.app.ui.screens.ReportDetailScreen
import com.chaide.app.ui.screens.SettingsScreen
import com.chaide.app.ui.screens.SignUpScreen
import com.chaide.app.ui.screens.UserAccountScreen
import com.chaide.app.ui.screens.WelcomeScreen
import com.chaide.app.net.Session

@Composable
fun AppNav() {
    val nav = rememberNavController()
    // Sesion persistida (SharedPreferences/NSUserDefaults, ver initApp() en App.kt):
    // si ya habia token guardado, saltar Welcome/Login directo a "main".
    val startDestination = if (Session.isLoggedIn) "main" else "welcome"
    // Hoisted por encima de MainScreen (no dentro) para que el MediaPlayer y
    // la pista en curso sobrevivan al navegar a la biblioteca y volver, y al
    // cambiar de pestaña Home/Device/Me.
    val scope = rememberCoroutineScope()
    val music = remember { MusicPlayerState(scope) }
    DisposableEffect(Unit) { onDispose { music.release() } }
    NavHost(navController = nav, startDestination = startDestination) {
        composable("welcome") {
            WelcomeScreen(
                onLogin = { nav.navigate("login") },
                onSignUp = { nav.navigate("signup") },
            )
        }
        composable("login") {
            LoginScreen(
                onBack = { nav.popBackStack() },
                onSuccess = {
                    nav.navigate("main") {
                        popUpTo("welcome") { inclusive = true }
                    }
                }
            )
        }
        composable("signup") {
            SignUpScreen(
                onBack = { nav.popBackStack() },
                onSuccess = {
                    nav.navigate("main") {
                        popUpTo("welcome") { inclusive = true }
                    }
                }
            )
        }
        composable("main") {
            MainScreen(
                music = music,
                onOpenDeviceDetail = { id -> nav.navigate("device/$id") },
                onOpenReport = { period -> nav.navigate("report/$period") },
                onOpenMusic = { nav.navigate("music") },
                onOpenNowPlaying = { nav.navigate("nowplaying") },
                onAddDevice = { nav.navigate("adddevice") },
                onOpenFriends = { nav.navigate("friends") },
                onOpenAccount = { nav.navigate("account") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("music") {
            MusicLibraryScreen(music = music, onBack = { nav.popBackStack() })
        }
        composable("nowplaying") {
            NowPlayingScreen(music = music, onBack = { nav.popBackStack() })
        }
        composable(
            "report/{period}",
            arguments = listOf(navArgument("period") { type = NavType.StringType })
        ) { entry ->
            val p = entry.arguments?.getString("period") ?: "day"
            ReportDetailScreen(onBack = { nav.popBackStack() }, initialPeriod = p)
        }
        // Sub-pantallas reales del tab "Me" (rutas /FriendList, /UserAccount
        // y /Setting del router Vue original).
        composable("friends") {
            FriendListScreen(onBack = { nav.popBackStack() })
        }
        composable("account") {
            UserAccountScreen(
                onBack = { nav.popBackStack() },
                onLoggedOut = { nav.navigate("welcome") { popUpTo("main") { inclusive = true } } },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable("adddevice") {
            AddDeviceScreen(onBack = { nav.popBackStack() })
        }
        composable(
            "device/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("deviceId") ?: ""
            DeviceDetailScreen(
                deviceId = id,
                onBack = { nav.popBackStack() },
                onOpenLiveData = { nav.navigate("live") },
            )
        }
        composable("live") {
            LiveMonitorScreen(onBack = { nav.popBackStack() })
        }
    }
}
