package com.runelitetablet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.runelitetablet.logging.AppLog
import com.runelitetablet.setup.AppScreen
import com.runelitetablet.setup.SetupViewModel
import com.runelitetablet.setup.StepStatus
import com.runelitetablet.ui.components.StepItem

/**
 * Top-level screen router. Observes [SetupViewModel.currentScreen] and delegates
 * to the appropriate screen composable.
 */
@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    when (val screen = currentScreen) {
        is AppScreen.Setup -> SetupWizardContent(viewModel)
        is AppScreen.Login -> LoginScreen(
            onLogin = {
                AppLog.ui("SetupScreen: Login button clicked")
                viewModel.startLogin()
            },
            onSkip = {
                AppLog.ui("SetupScreen: Skip login clicked")
                viewModel.skipLogin()
            }
        )
        is AppScreen.CharacterSelect -> CharacterSelectScreen(
            characters = screen.characters,
            onCharacterSelected = { character ->
                AppLog.ui("SetupScreen: character selected displayName=***")
                viewModel.onCharacterSelected(character)
            }
        )
        is AppScreen.Launch -> LaunchScreen(
            displayName = viewModel.getDisplayName(),
            onLaunch = {
                AppLog.ui("SetupScreen: Launch RuneLite button clicked")
                viewModel.launchRuneLite()
            },
            onSettings = {
                // Phase 14 will handle settings navigation
                AppLog.ui("SetupScreen: Settings button clicked (Phase 14)")
            }
        )
        is AppScreen.AuthError -> AuthErrorScreen(
            message = screen.message,
            onRetry = {
                AppLog.ui("SetupScreen: AuthError retry clicked")
                viewModel.startLogin()
            },
            onSkip = {
                AppLog.ui("SetupScreen: AuthError skip clicked")
                viewModel.skipLogin()
            }
        )
    }
}

// =============================================================================
// Setup wizard content (moved from the old SetupScreen body)
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupWizardContent(viewModel: SetupViewModel) {
    val steps by viewModel.steps.collectAsState()
    val canLaunch by viewModel.canLaunch.collectAsState()
    val currentOutput by viewModel.currentOutput.collectAsState()
    val showPermissionsSheet by viewModel.showPermissionsSheet.collectAsState()
    val permissionInstructions by viewModel.permissionInstructions.collectAsState()
    val sheetState = rememberModalBottomSheetState()

    val hasFailed = steps.any { it.status is StepStatus.Failed }

    SideEffect {
        AppLog.ui(
            "SetupWizardContent: canLaunch=$canLaunch hasFailed=$hasFailed " +
                "showPermissionsSheet=$showPermissionsSheet hasOutput=${currentOutput != null}"
        )
    }

    LaunchedEffect(Unit) {
        viewModel.startSetup()
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "RuneLite for Tablet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            steps.forEach { stepState ->
                StepItem(
                    label = stepState.step.label,
                    status = stepState.status,
                    onClick = { viewModel.onManualStepClick(stepState) }
                )
            }

            currentOutput?.let { output ->
                Spacer(modifier = Modifier.height(16.dp))
                OutputCard(
                    text = output,
                    isError = hasFailed
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth()) {
                if (hasFailed) {
                    OutlinedButton(onClick = {
                        AppLog.ui("SetupWizardContent: Retry button clicked hasFailed=$hasFailed")
                        viewModel.retry()
                    }) {
                        Text("Retry")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        AppLog.ui("SetupWizardContent: Launch RuneLite button clicked canLaunch=$canLaunch")
                        viewModel.launchRuneLite()
                    },
                    enabled = canLaunch
                ) {
                    Text("Launch RuneLite")
                }
            }
        }
    }

    if (showPermissionsSheet) {
        PermissionsBottomSheet(
            instructions = permissionInstructions,
            onVerify = { viewModel.verifyPermissions() },
            onDismiss = {
                AppLog.ui("SetupWizardContent: permissions sheet dismissed")
                viewModel.dismissPermissionsSheet()
            },
            sheetState = sheetState
        )
    }
}

// =============================================================================
// Login screen
// =============================================================================

@Composable
private fun LoginScreen(
    onLogin: () -> Unit,
    onSkip: () -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "RuneLite for Tablet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Sign in to play with your Jagex account",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            Button(
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Sign in with Jagex",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSkip) {
                Text(
                    text = "Skip (play without account)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// Launch screen
// =============================================================================

@Composable
private fun LaunchScreen(
    displayName: String?,
    onLaunch: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "RuneLite for Tablet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (displayName != null) {
                Text(
                    text = "Signed in as $displayName",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Text(
                text = "Setup complete. Ready to play!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            Button(
                onClick = onLaunch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Launch RuneLite",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSettings) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// Auth error screen
// =============================================================================

@Composable
private fun AuthErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Sign-in Failed",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Try Again",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSkip) {
                Text(
                    text = "Skip (play without account)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// Shared composables
// =============================================================================

@Composable
private fun OutputCard(text: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionsBottomSheet(
    instructions: List<String>,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState
) {
    val clipboardManager = LocalClipboardManager.current

    SideEffect {
        AppLog.ui("PermissionsBottomSheet: shown instructionCount=${instructions.size}")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Configure Permissions",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            instructions.forEachIndexed { index, instruction ->
                Text(
                    text = "${index + 1}. $instruction",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (index == 0) {
                    val command =
                        "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties"
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(command))
                        }
                    ) {
                        Text("Copy Command")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    AppLog.ui("PermissionsBottomSheet: Verify Setup button clicked")
                    onVerify()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Verify Setup")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
