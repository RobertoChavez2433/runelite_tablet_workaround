package com.runelitetablet.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.runelitetablet.setup.SetupViewModel
import com.runelitetablet.setup.StepStatus
import com.runelitetablet.ui.components.StepItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val steps by viewModel.steps.collectAsState()
    val canLaunch by viewModel.canLaunch.collectAsState()
    val currentOutput by viewModel.currentOutput.collectAsState()
    val showPermissionsSheet by viewModel.showPermissionsSheet.collectAsState()
    val permissionInstructions by viewModel.permissionInstructions.collectAsState()
    val sheetState = rememberModalBottomSheetState()

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

            if (currentOutput != null) {
                Spacer(modifier = Modifier.height(16.dp))
                OutputCard(
                    text = currentOutput!!,
                    isError = steps.any { it.status is StepStatus.Failed }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth()) {
                if (steps.any { it.status is StepStatus.Failed }) {
                    OutlinedButton(onClick = { viewModel.retry() }) {
                        Text("Retry")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { viewModel.launch() },
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
            onDismiss = { viewModel.dismissPermissionsSheet() },
            sheetState = sheetState
        )
    }
}

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
                .verticalScroll(rememberScrollState()),
            maxLines = 10
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
                onClick = onVerify,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Verify Setup")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
