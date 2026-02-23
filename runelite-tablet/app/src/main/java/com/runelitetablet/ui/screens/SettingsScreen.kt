package com.runelitetablet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.runelitetablet.ui.DisplayPreferences

/**
 * Settings screen accessible from the Launch screen.
 * Provides account management, setup reset, and app info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    displayName: String?,
    appVersion: String,
    onSignOut: () -> Unit,
    onResetSetup: () -> Unit,
    onViewLogs: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val displayPreferences = remember { DisplayPreferences(context) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AccountSection(
                displayName = displayName,
                onSignOut = onSignOut
            )

            Spacer(modifier = Modifier.height(12.dp))

            DisplaySection(displayPreferences = displayPreferences)

            Spacer(modifier = Modifier.height(12.dp))

            SetupSection(
                onResetSetupClicked = { showResetConfirmDialog = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AboutSection(
                appVersion = appVersion,
                onViewLogs = onViewLogs
            )
        }
    }

    if (showResetConfirmDialog) {
        ResetSetupConfirmDialog(
            onConfirm = {
                showResetConfirmDialog = false
                onResetSetup()
            },
            onDismiss = { showResetConfirmDialog = false }
        )
    }
}

@Composable
private fun AccountSection(
    displayName: String?,
    onSignOut: () -> Unit
) {
    SectionHeader(title = "Account")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = displayName ?: "Not signed in",
                style = MaterialTheme.typography.bodyLarge,
                color = if (displayName != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            if (displayName != null) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Out")
                }
            }
        }
    }
}

@Composable
private fun SetupSection(
    onResetSetupClicked: () -> Unit
) {
    SectionHeader(title = "Setup")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedButton(
                onClick = onResetSetupClicked,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Reset Setup",
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Clears all setup progress. You'll need to run setup again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DisplaySection(
    displayPreferences: DisplayPreferences
) {
    var resolutionMode by remember { mutableStateOf(displayPreferences.resolutionMode) }
    var customWidth by remember { mutableStateOf(displayPreferences.customWidth.toString()) }
    var customHeight by remember { mutableStateOf(displayPreferences.customHeight.toString()) }
    var fullscreen by remember { mutableStateOf(displayPreferences.fullscreen) }
    var showKeyboardBar by remember { mutableStateOf(displayPreferences.showKeyboardBar) }

    SectionHeader(title = "Display")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Resolution",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Resolution mode radio buttons
            RadioButtonRow(
                label = "Native",
                selected = resolutionMode == "native",
                onClick = {
                    resolutionMode = "native"
                    displayPreferences.resolutionMode = "native"
                }
            )
            RadioButtonRow(
                label = "Scaled",
                selected = resolutionMode == "scaled",
                onClick = {
                    resolutionMode = "scaled"
                    displayPreferences.resolutionMode = "scaled"
                }
            )
            RadioButtonRow(
                label = "Custom",
                selected = resolutionMode == "exact",
                onClick = {
                    resolutionMode = "exact"
                    displayPreferences.resolutionMode = "exact"
                }
            )

            // Custom resolution fields
            if (resolutionMode == "exact") {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = customWidth,
                        onValueChange = { value ->
                            customWidth = value
                            value.toIntOrNull()?.let { displayPreferences.customWidth = it }
                        },
                        label = { Text("Width") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = customHeight,
                        onValueChange = { value ->
                            customHeight = value
                            value.toIntOrNull()?.let { displayPreferences.customHeight = it }
                        },
                        label = { Text("Height") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fullscreen toggle
            SwitchRow(
                label = "Fullscreen",
                checked = fullscreen,
                onCheckedChange = {
                    fullscreen = it
                    displayPreferences.fullscreen = it
                }
            )

            // Keyboard bar toggle
            SwitchRow(
                label = "Show keyboard bar",
                checked = showKeyboardBar,
                onCheckedChange = {
                    showKeyboardBar = it
                    displayPreferences.showKeyboardBar = it
                }
            )
        }
    }
}

@Composable
private fun RadioButtonRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AboutSection(
    appVersion: String,
    onViewLogs: () -> Unit
) {
    SectionHeader(title = "About")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoRow(label = "Version", value = appVersion)

            Spacer(modifier = Modifier.height(12.dp))

            InfoRow(label = "RuneLite", value = "Official RuneLite client")

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onViewLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Session Logs")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ResetSetupConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Reset Setup?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = "This will clear all setup progress. You'll need to run the full setup again before launching RuneLite.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = "Reset",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
