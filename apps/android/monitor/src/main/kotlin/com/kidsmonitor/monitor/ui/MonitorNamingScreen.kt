package com.kidsmonitor.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kidsmonitor.monitor.store.monitorNameError

@Composable
fun MonitorNamingScreen(name: String, onNameChange: (String) -> Unit, onContinue: () -> Unit) {
    val error = monitorNameError(name)
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("MONITOR SETUP", style = MaterialTheme.typography.labelMedium)
        Text("Name this monitor", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Keep this device name or edit it to a name you will recognize on the parent phone. " +
                "Use a different name for each monitor, even if the phones are the same model.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Monitor name") },
            placeholder = { Text("Pixel 6") },
            supportingText = { Text(if (name.isNotEmpty() && error != null) error else "1–64 characters") },
            isError = name.isNotEmpty() && error != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Button(onClick = onContinue, enabled = error == null, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to pairing")
        }
    }
}
