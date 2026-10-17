package com.kidsmonitor.parent.ui.alerts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kidsmonitor.parent.service.Notifications

/**
 * Prompts for Do Not Disturb policy access.
 *
 * Without it Android silently ignores `setBypassDnd(true)` and the
 * monitor-offline alert -- the one that means nobody is watching the child --
 * is muted whenever Do Not Disturb is on. There is no dialog for this: the user
 * has to grant it from a Settings screen, so the app has to ask.
 */
@Composable
fun DndAccessBanner() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(Notifications.isDndBypassGranted(context)) }

    // Re-check when the user comes back from Settings, and rebuild the channel
    // if they granted it -- bypassDnd cannot be edited on an existing channel.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.currentStateFlow.collect { lifecycleState ->
            if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                val nowGranted = Notifications.isDndBypassGranted(context)
                if (nowGranted && !granted) Notifications.createChannels(context)
                granted = nowGranted
            }
        }
    }

    if (granted) return

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Monitor-offline alerts are muted by Do Not Disturb",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "If the monitor stops reporting, nobody is watching the room. " +
                    "Allow Do Not Disturb access so that alert always sounds.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { context.startActivity(Notifications.dndAccessSettingsIntent()) }) {
                Text("Open settings")
            }
        }
    }
}
