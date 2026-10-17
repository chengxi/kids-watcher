package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.MediaFrame
import com.kidsmonitor.shared.protocol.MediaFrames
import com.kidsmonitor.shared.protocol.ParentToHub
import com.kidsmonitor.shared.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

sealed interface HubSocketEvent {
    data object Connected : HubSocketEvent
    data class Disconnected(val reason: String) : HubSocketEvent
    data class Message(val message: HubToParent) : HubSocketEvent
}

/**
 * The parent's connection to the hub.
 *
 * Its reconnect behaviour matters more than its happy path: spec §3.3 rates an
 * OEM battery manager killing this connection as the highest risk in the
 * design. Reconnection is automatic and unbounded, and callers are expected to
 * backfill the event log whenever [HubSocketEvent.Connected] arrives.
 */
class HubSocket(
    client: OkHttpClient,
    wsUrl: String,
    token: String,
    deviceName: String,
    appVersion: String,
) {
    private val socket = RoleSocket(
        client = client,
        wsUrl = wsUrl,
        token = token,
        role = "parent",
        encodeHello = { ProtocolJson.encode(ParentToHub.Hello(deviceName, appVersion)) },
        encodeHeartbeat = { ProtocolJson.encode(ParentToHub.Heartbeat) },
        decode = ProtocolJson::decodeHubToParent,
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
    val events: Flow<HubSocketEvent> = socket.events.map { event ->
        when (event) {
            is RoleSocket.Event.Connected -> HubSocketEvent.Connected
            is RoleSocket.Event.Disconnected -> HubSocketEvent.Disconnected(event.reason)
            is RoleSocket.Event.Message -> HubSocketEvent.Message(event.message)
        }
    }

    /**
     * Live pictures for the stream this parent is watching. A lossy broadcast:
     * unlike [events] it may have several collectors, and a late one simply
     * misses the frames sent before it subscribed. Separate from [audio], with
     * its own buffer, so a burst of pictures never pushes out sound.
     */
    val video: SharedFlow<MediaFrame> get() = socket.media.video

    /** Live PCM for the stream this parent is watching. Lossy, like [video]. */
    val audio: SharedFlow<MediaFrame> get() = socket.media.audio

    val isConnected: StateFlow<Boolean> get() = socket.isConnected
    val connectionState: StateFlow<ConnectionState> get() = socket.connectionState

    fun retryNow() = socket.retryNow()

    fun reconnectNow() = socket.reconnectNow()

    fun connect(scope: CoroutineScope) = socket.connect(scope)

    fun send(msg: ParentToHub) = socket.send(ProtocolJson.encode(msg))

    fun sendMedia(frame: MediaFrame): Boolean = socket.sendMedia(frame.kind, MediaFrames.encode(frame), maxQueuedBytes = 8 * 1024L)

    fun disconnect() = socket.disconnect()
}
