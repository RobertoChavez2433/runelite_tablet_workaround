package com.runelitetablet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.runelitetablet.logging.AppLog
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

        val savedState = if (savedInstanceState == null) "null (fresh start)" else "present (restored)"
        AppLog.lifecycle(
            "MainActivity.onCreate: savedInstanceState=$savedState " +
                "PID=${android.os.Process.myPid()} thread=${Thread.currentThread().name}"
        )
        AppLog.perf("onCreate: ${AppLog.perfSnapshot(applicationContext)}")

        setContent {
            RuneLiteTabletTheme {
                SetupScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.lifecycle("MainActivity.onResume: bindActions + recheckPermissions triggered")
        AppLog.perf("onResume: ${AppLog.perfSnapshot(applicationContext)}")
        viewModel.bindActions(this)
        viewModel.recheckPermissions()
    }

    override fun onPause() {
        super.onPause()
        AppLog.lifecycle("MainActivity.onPause: unbindActions called")
        AppLog.perf("onPause: ${AppLog.perfSnapshot(applicationContext)}")
        viewModel.unbindActions()
    }

}
