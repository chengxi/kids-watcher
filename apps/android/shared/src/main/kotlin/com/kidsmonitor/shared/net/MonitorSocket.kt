package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.HubToMonitor
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaFrames
import com.kidsmonitor.shared.protocol.MonitorToHub
import com.kidsmonitor.shared.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

sealed interface MonitorSocketEvent {
    data object Connected : MonitorSocketEvent
    data class Disconnected(val reason: String) : MonitorSocketEvent
    data class Message(val message: HubToMonitor) : MonitorSocketEvent
}

/**
 * The monitor's connection to the hub. Same transport as [HubSocket]; the role,
 * the hello message and the inbound schema are what differ.
 *
 * The heartbeat carries battery and thermal state, which the hub uses for
 * liveness. If these stop arriving for 45 seconds the hub raises MONITOR_LOST
 * on the parent's alarm channel.
 */
class MonitorSocket(
    client: OkHttpClient,
    wsUrl: String,
    token: String,
    private val deviceName: String,
    appVersion: String,
    /** Read on every heartbeat, rather than cached when the notification changes. */
    private val heartbeat: () -> MonitorToHub.Heartbeat = { MonitorToHub.Heartbeat(null, "unknown") },
) {
    private val socket = RoleSocket(
        client = client,
        wsUrl = wsUrl,
        token = token,
        role = "monitor",
        encodeHello = { ProtocolJson.encodeMonitor(MonitorToHub.Hello(deviceName, appVersion)) },
        encodeHeartbeat = {
            ProtocolJson.encodeMonitor(heartbeat())
        },
        decode = ProtocolJson::decodeHubToMonitor,
    )

    /**
     * Everything the connection reports, in order, with nothing dropped.
     *
     * **One consumer.** Events are queued until taken, so a collector that
     * starts after the connection opened still receives Connected - but two
     * collectors would split the stream rather than each see all of it.
     *
     * Mapped rather than forwarded: this used to launch a second collector
     * inside connect(), which was a second place for an early event to be
     * lost, and a duplicate forwarder on every repeated connect().
     */
    val events: Flow<MonitorSocketEvent> = socket.events.map { event ->
        when (event) {
            is RoleSocket.Event.Connected -> MonitorSocketEvent.Connected
            is RoleSocket.Event.Disconnected -> MonitorSocketEvent.Disconnected(event.reason)
            is RoleSocket.Event.Message -> MonitorSocketEvent.Message(event.message)
        }
    }

    val isConnected: StateFlow<Boolean> get() = socket.isConnected
    val connectionState: StateFlow<ConnectionState> get() = socket.connectionState
    val audio: SharedFlow<MediaFrame> get() = socket.media.audio

    fun connect(scope: CoroutineScope) = socket.connect(scope)

    fun send(msg: MonitorToHub) = socket.send(ProtocolJson.encodeMonitor(msg))

    /** Returns false if the frame was dropped for backpressure or the link is down. */
    fun sendMedia(frame: MediaFrame): Boolean = socket.sendMedia(frame.kind, MediaFrames.encode(frame))

    fun disconnect() = socket.disconnect()
}
