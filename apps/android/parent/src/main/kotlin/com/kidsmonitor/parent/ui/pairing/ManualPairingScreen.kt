package com.kidsmonitor.parent.ui.pairing

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsmonitor.shared.net.ManualPairing
import com.kidsmonitor.shared.net.ManualPairingState
import com.kidsmonitor.shared.permissions.RuntimePermissions
import com.kidsmonitor.shared.store.Credentials

/**
 * Pairing without a camera. The hub's certificate is confirmed by eye before
 * the code is sent, so answering "they do not match" costs nothing: the code
 * is still unused.
 */
@Composable
fun ManualPairingScreen(
    credentials: Credentials,
    deviceName: String,
    onPaired: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // No camera here -- nothing is scanned. The local network permission is a
    // different matter: without it, on Android 17, every connection to a LAN
    // address times out instead of failing, so the typed path would hang on a
    // freshly installed app. One launcher, one request: Android drops a
    // request made while another dialog is open.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        val wanted = RuntimePermissions.missing(
            RuntimePermissions.PARENT_TYPED,
            Build.VERSION.SDK_INT,
        ) { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }
    val pairing = remember {
        ManualPairing(
            scope = scope,
            role = "parent",
            deviceName = deviceName,
            save = { credentials.save(it) },
        )
    }
    val state by pairing.state.collectAsStateWithLifecycle()

    // Kept across a rotation and a failed attempt: only the code should ever
    // need retyping.
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8444") }
    var code by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is ManualPairingState.Paired) onPaired()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pair by typing", style = MaterialTheme.typography.headlineSmall)

        when (val current = state) {
            is ManualPairingState.Confirm -> {
                Text(
                    "Does the hub show these six characters?",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Text(current.check, style = MaterialTheme.typography.displaySmall)
                Text(
                    "The pair command prints them on the line that starts with \"check:\". " +
                        "If they are different, someone else is answering at this address.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { pairing.confirmMatch() }, modifier = Modifier.fillMaxWidth()) {
                    Text("They match - pair")
                }
                OutlinedButton(
                    onClick = { pairing.confirmMismatch() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("They do not match")
                }
            }

            is ManualPairingState.Probing -> {
                CircularProgressIndicator()
                Text("Looking for the hub…")
            }

            is ManualPairingState.Pairing -> {
                CircularProgressIndicator()
                Text("Pairing…")
            }

            is ManualPairingState.Paired -> Text("Paired.")

            is ManualPairingState.Failed -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(current.message, modifier = Modifier.padding(16.dp))
                }
                Button(onClick = { pairing.reset() }) { Text("Try again") }
            }

            ManualPairingState.Entering -> {
                Text(
                    "Run the pair command on your hub and type what it prints.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Hub address") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Six-digit code") },
                    placeholder = { Text("123 456") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { pairing.submit(host, port, code) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue")
                }
            }
        }

        TextButton(onClick = onBack) { Text("Scan a QR code instead") }
    }
}
