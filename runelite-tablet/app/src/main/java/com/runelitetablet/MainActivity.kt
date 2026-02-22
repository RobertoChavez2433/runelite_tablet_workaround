package com.runelitetablet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.runelitetablet.setup.SetupViewModel
import com.runelitetablet.ui.screens.SetupScreen
import com.runelitetablet.ui.theme.RuneLiteTabletTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SetupViewModel by viewModels {
        SetupViewModel.Factory(this, (application as RuneLiteTabletApp).httpClient)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RuneLiteTabletTheme {
                SetupScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.bindActions(this)
        viewModel.recheckPermissions()
    }

    override fun onPause() {
        super.onPause()
        viewModel.unbindActions()
    }
}
