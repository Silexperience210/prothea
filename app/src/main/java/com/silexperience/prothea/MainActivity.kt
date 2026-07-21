package com.silexperience.prothea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.silexperience.prothea.capture.CaptureScreen
import com.silexperience.prothea.scan.ScanViewModel
import com.silexperience.prothea.ui.HomeScreen
import com.silexperience.prothea.ui.ProtheaTheme
import com.silexperience.prothea.ui.SessionScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProtheaTheme {
                val nav = rememberNavController()
                val vm: ScanViewModel = viewModel()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm = vm,
                            onNewScan = {
                                vm.startNewSession()
                                nav.navigate("capture")
                            },
                            onOpenSession = { id -> nav.navigate("session/$id") }
                        )
                    }
                    composable("capture") {
                        CaptureScreen(
                            vm = vm,
                            onDone = { nav.popBackStack() }
                        )
                    }
                    composable("session/{id}") { back ->
                        SessionScreen(
                            vm = vm,
                            sessionId = back.arguments?.getString("id") ?: "",
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
