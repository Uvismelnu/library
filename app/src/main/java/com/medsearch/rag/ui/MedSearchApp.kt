package com.medsearch.rag.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.medsearch.rag.ui.screens.DisclaimerScreen
import com.medsearch.rag.ui.screens.HomeScreen
import com.medsearch.rag.ui.screens.SettingsScreen

private object Routes {
    const val DISCLAIMER = "disclaimer"
    const val HOME = "home"
    const val SETTINGS = "settings"
}

@Composable
fun MedSearchApp() {
    val nav = rememberNavController()
    val viewModel: SearchViewModel = hiltViewModel()
    val home by viewModel.home.collectAsState()

    // Usamos el estado del disclaimer para decidir la pantalla inicial de forma segura.
    // Navegamos al Home solo cuando disclaimerAck es true.
    NavHost(
        navController = nav,
        startDestination = if (home.disclaimerAck) Routes.HOME else Routes.DISCLAIMER
    ) {
        composable(Routes.DISCLAIMER) {
            DisclaimerScreen(onAccept = {
                viewModel.acknowledgeDisclaimer()
            })
            // Si el estado cambia a aceptado (ej. por persistencia), navegamos.
            androidx.compose.runtime.LaunchedEffect(home.disclaimerAck) {
                if (home.disclaimerAck) {
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.DISCLAIMER) { inclusive = true }
                    }
                }
            }
        }
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
