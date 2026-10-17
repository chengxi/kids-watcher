package com.kidsmonitor.monitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsmonitor.shared.permissions.RuntimePermissions


@Composable
fun MonitorPairingScreen(
    viewModel: MonitorPairingViewModel,
    deviceName: String,
    onPaired: () -> Unit,
    onEnterCodeInstead: () -> Unit = {},
    onChangeName: () -> Unit = {},
    debugPairingPayload: String? = null,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Scanning a code off another screen takes both hands and a steady aim.
    // Letting the display sleep mid-attempt kills the camera and leaves a black
    // rectangle -- which is exactly what happened on the parent app.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Seed from the real permission state. Assuming denied makes an app that
    // already has access flash the prompt on every launch.
    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }

    // ONE launcher, ONE request. Android drops a permission request made while
    // another dialog is open, so requesting camera and then the rest back to
    // back silently skipped the second - see RuntimePermissions.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> cameraGranted = results[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA) }

    fun requestMissing() {
        val wanted = RuntimePermissions.missing(RuntimePermissions.MONITOR, Build.VERSION.SDK_INT, ::granted)
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    LaunchedEffect(Unit) { requestMissing() }

    LaunchedEffect(state) {
        if (state is MonitorPairingState.Paired) {
            onPaired()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("MONITOR SETUP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Connect to home hub", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Run the monitor pair command on your hub and scan the QR code it prints. " +
                "The code is single-use and expires after five minutes.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
        )

        Text("Monitor: $deviceName", style = MaterialTheme.typography.titleMedium)
        val canChangeSetup = state is MonitorPairingState.Idle || state is MonitorPairingState.Failed
        TextButton(onClick = onChangeName, enabled = canChangeSetup) { Text("Change monitor name") }

        when (val current = state) {
            is MonitorPairingState.Connecting -> {
                CircularProgressIndicator()
                Text("Connecting to the hub…")
            }

            is MonitorPairingState.Failed -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(current.message, modifier = Modifier.padding(16.dp))
                }
                Button(onClick = {
                    viewModel.reset()
                    debugPairingPayload?.let { viewModel.onQrScanned(it, deviceName) }
                }) { Text("Try again") }
            }

            is MonitorPairingState.Paired -> Text("Paired.")

            MonitorPairingState.Idle -> {
                if (debugPairingPayload != null) {
                    Text("Pairing details received from setup.")
                } else if (cameraGranted) {
                    QrScanner(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(1f)) { raw ->
                        viewModel.onQrScanned(raw, deviceName)
                    }
                } else {
                    Text("Camera access is needed to scan the pairing code.")
                    Button(onClick = { requestMissing() }) {
                        Text("Grant camera access")
                    }
                }
            }
        }

        // The typed path needs no camera, which is why it is offered even when
        // scanning is working.
        Button(onClick = onEnterCodeInstead, enabled = canChangeSetup, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) { Text("Enter address & pairing code") }
    }
}
