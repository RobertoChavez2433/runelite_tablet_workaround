package com.runelitetablet.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.runelitetablet.setup.StepStatus

@Composable
fun StepItem(
    label: String,
    status: StepStatus,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val statusColor = when (status) {
        StepStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
        StepStatus.InProgress -> MaterialTheme.colorScheme.primary
        StepStatus.Completed -> Color(0xFF4CAF50)
        is StepStatus.Failed -> MaterialTheme.colorScheme.error
        is StepStatus.ManualAction -> Color(0xFFFFA726)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = status is StepStatus.ManualAction) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        when (status) {
            StepStatus.Pending -> Icon(
                imageVector = Icons.Filled.RadioButtonUnchecked,
                contentDescription = "Pending",
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )
            StepStatus.InProgress -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = statusColor
            )
            StepStatus.Completed -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Completed",
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )
            is StepStatus.Failed -> Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = "Failed",
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )
            is StepStatus.ManualAction -> Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Action Required",
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = status.displayText,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor
        )
    }
}
