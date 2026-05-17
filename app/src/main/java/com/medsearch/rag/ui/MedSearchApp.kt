package com.medsearch.rag.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.medsearch.rag.ui.screens.DisclaimerScreen
import com.medsearch.rag.ui.screens.HomeScreen

@Composable
fun MedSearchApp() {
    val viewModel: SearchViewModel = hiltViewModel()
    val home by viewModel.home.collectAsState()

    if (home.disclaimerAck) {
        HomeScreen(viewModel = viewModel)
    } else {
        DisclaimerScreen(onAccept = { viewModel.acknowledgeDisclaimer() })
    }
}
