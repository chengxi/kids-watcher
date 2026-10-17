package com.kidsmonitor.parent.ui.pairing

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
fun PairingScreen(
    viewModel: PairingViewModel,
    deviceName: String,
    onPaired: () -> Unit,
    onEnterCodeInstead: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Scanning a code off another screen takes both hands and a steady aim.
    // Letting the display sleep mid-attempt kills the camera and the user comes
    // back to a black rectangle.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Seed from the real permission state. Assuming denied makes an app that
    // already has camera access flash the "grant" prompt on every launch while
    // it waits for the launcher callback.
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ONE launcher, ONE request. This screen used to launch camera, then
    // notifications, then the local network back to back; Android drops a
    // request made while another dialog is open, so ACCESS_LOCAL_NETWORK was
    // never asked for and pairing hung on Android 17. See RuntimePermissions.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> cameraGranted = results[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA) }

    fun requestMissing() {
        val wanted = RuntimePermissions.missing(RuntimePermissions.PARENT, Build.VERSION.SDK_INT, ::granted)
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    LaunchedEffect(Unit) { requestMissing() }

    LaunchedEffect(state) {
        if (state is PairingState.Paired) onPaired()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pair with your hub", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Run the pair command on your hub and scan the QR code it prints. " +
                "The code is single-use and expires after five minutes.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        when (val current = state) {
            is PairingState.Connecting -> {
                CircularProgressIndicator()
                Text("Connecting to the hub…")
            }

            is PairingState.Failed -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(current.message, modifier = Modifier.padding(16.dp))
                }
                Button(onClick = { viewModel.reset() }) { Text("Try again") }
            }

            is PairingState.Paired -> Text("Paired.")

            PairingState.Idle -> {
                if (cameraGranted) {
                    QrScanner(modifier = Modifier.fillMaxWidth().weight(1f)) { raw ->
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
        TextButton(onClick = onEnterCodeInstead) { Text("Enter a code instead") }
    }
}
