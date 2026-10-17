package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.HEARTBEAT_INTERVAL_MS
import com.kidsmonitor.shared.protocol.MediaFrames
import com.kidsmonitor.shared.protocol.MediaKind
import com.kidsmonitor.shared.protocol.ProtocolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean

/** 1s, 2s, 4s, 8s ... capped at 60s. Deterministic so it can be asserted. */
fun backoffDelayMs(attempt: Int): Long {
    if (attempt >= 6) return 60_000L
    return 1_000L shl attempt
}

/**
 * The socket both roles share.
 *
 * Only three things differ between a parent and a monitor connection: the role
 * in the query string, the hello message, and which schema decodes inbound
 * frames. Everything else -- the reconnect loop, the backoff, the heartbeat,
 * and ignoring frames this version cannot decode -- is behaviour that took real
 * work to get right, and a second copy of it would drift.
 *
 * [InboundT] is the decoded message type for this role.
 */
internal class RoleSocket<InboundT : Any>(
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val token: String,
    private val role: String,
    private val encodeHello: () -> String,
    private val encodeHeartbeat: () -> String,
    private val decode: (String) -> InboundT,
) {
    sealed interface Event<out T> {
        data object Connected : Event<Nothing>
        data class Disconnected(val reason: String) : Event<Nothing>
        data class Message<T>(val message: T) : Event<T>
    }

    // A queue, not a broadcast. This was a SharedFlow with replay = 0, which
    // drops anything emitted while nobody is collecting - and every caller
    // launches its collector and then connects straight away, so under load
    // the handshake could win and Connected vanished. On the parent that meant
    // no backfill (spec 6.5); on the monitor, a "Not connected" banner over a
    // working connection. A channel holds each event until it is taken.
    //
    // Consequence: events has ONE consumer. Two collectors would split the
    // stream between them rather than each seeing everything. Production has
    // exactly one per socket (its service).
    private val queue = Channel<Event<InboundT>>(Channel.UNLIMITED)
    val events: Flow<Event<InboundT>> = queue.receiveAsFlow()

    // Media is the opposite of events: lossy broadcasts that drop the oldest
    // frame when nobody keeps up, one per kind so video is dropped before audio.
    // A late or slow viewer loses a few frames; it never grows memory and never
    // delays a control message.
    val media = MediaFeed()

    private val mutableConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    private val mutableConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val connectionState: StateFlow<ConnectionState> = mutableConnectionState.asStateFlow()
    private val retryRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null
    private val stopped = AtomicBoolean(false)

    fun connect(scope: CoroutineScope) {
        stopped.set(false)
        if (loop?.isActive == true) return
        mutableConnectionState.value = ConnectionState.Connecting

        loop = scope.launch {
            var attempt = 0
            var first = true
            while (isActive && !stopped.get()) {
                mutableConnectionState.value = if (first) ConnectionState.Connecting else ConnectionState.Reconnecting
                first = false
                val (closedReason, opened) = openAndAwaitClose(scope)
                if (stopped.get() || !isActive) break
                // A recovered connection starts a new outage. Do not accumulate
                // backoff across healthy sessions over an entire night.
                if (opened) attempt = 0
                emit(Event.Disconnected(closedReason))
                val retryDelay = backoffDelayMs(attempt)
                mutableConnectionState.value = ConnectionState.WaitingToRetry(retryDelay, closedReason)
                withTimeoutOrNull(retryDelay) { retryRequests.receive() }
                // Capped so it cannot overflow on a hub down for days.
                attempt = (attempt + 1).coerceAtMost(6)
            }
        }
    }

    private suspend fun openAndAwaitClose(scope: CoroutineScope): Pair<String, Boolean> {
        val request = Request.Builder()
            .url("$wsUrl/ws?role=$role")
            .header("Authorization", "Bearer $token")
            .build()

        val closed = CompletableDeferred<String>()
        var heartbeatJob: Job? = null
        var opened = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.isCancelled || stopped.get()) {
                    webSocket.cancel()
                    return
                }
                opened = true
                socket = webSocket
                mutableConnected.value = true
                mutableConnectionState.value = ConnectionState.Connected
                emit(Event.Connected)
                webSocket.send(encodeHello())
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        runCatching { webSocket.send(encodeHeartbeat()) }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // A frame this version cannot decode is not a reason to drop a
                // working connection: a newer hub may simply be saying
                // something this build does not know about yet.
                val decoded = try {
                    decode(text)
                } catch (e: ProtocolException) {
                    return
                }
                emit(Event.Message(decoded))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = try {
                    MediaFrames.decode(bytes.toByteArray())
                } catch (e: ProtocolException) {
                    return // same rule as text: a frame this build cannot read is not a reason to drop the link
                }
                media.tryEmit(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed.isCancelled || stopped.get()) return
                mutableConnected.value = false
                heartbeatJob?.cancel()
                closed.complete(if (reason.isBlank()) "closed ($code)" else reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed.isCancelled || stopped.get()) return
                mutableConnected.value = false
                heartbeatJob?.cancel()
                closed.complete(t.message ?: "connection failed")
            }
        }

        val pending = client.newWebSocket(request, listener)
        try {
            return closed.await() to opened
        } finally {
            closed.cancel()
            heartbeatJob?.cancel()
            if (socket === pending) socket = null
            pending.cancel()
        }
    }

    /** A foreground user action may skip a retry wait, without replacing a healthy socket. */
    fun retryNow() {
        if (connectionState.value is ConnectionState.WaitingToRetry) retryRequests.trySend(Unit)
    }

    fun reconnectNow() {
        if (connectionState.value == ConnectionState.Connected) {
            retryRequests.trySend(Unit)
            socket?.cancel()
        } else retryNow()
    }

    fun send(encoded: String) {
        socket?.send(encoded)
    }

    /** Sends one encoded media frame, or drops it if the link is behind. Returns whether it was queued. */
    fun sendMedia(kind: MediaKind, encoded: ByteArray, maxQueuedBytes: Long = Long.MAX_VALUE): Boolean {
        val ws = socket ?: return false
        if (ws.queueSize() > maxQueuedBytes) return false
        return sendMediaOn(ws, kind, encoded)
    }

    fun disconnect() {
        stopped.set(true)
        socket?.close(1000, "client disconnecting")
        socket = null
        loop?.cancel()
        loop = null
        mutableConnected.value = false
        mutableConnectionState.value = ConnectionState.Stopped
        retryRequests.tryReceive()
    }

    private fun emit(event: Event<InboundT>) {
        queue.trySend(event)
    }
}

/** The send path, separated so it can be tested against a fake WebSocket. */
internal fun sendMediaOn(ws: WebSocket, kind: MediaKind, encoded: ByteArray): Boolean {
    if (!MediaBackpressure.shouldSend(kind, ws.queueSize())) return false
    return ws.send(encoded.toByteString())
}
