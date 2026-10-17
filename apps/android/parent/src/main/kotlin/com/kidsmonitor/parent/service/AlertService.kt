package com.kidsmonitor.parent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.kidsmonitor.parent.data.EventRepository
import com.kidsmonitor.parent.data.EventEntity
import com.kidsmonitor.parent.data.ParentDatabase
import com.kidsmonitor.parent.data.toEntity
import com.kidsmonitor.parent.live.LiveLink
import com.kidsmonitor.parent.live.LiveSession
import com.kidsmonitor.parent.live.StreamGateway
import com.kidsmonitor.shared.net.HubApi
import com.kidsmonitor.shared.net.ConnectionState
import com.kidsmonitor.shared.net.HubSocket
import com.kidsmonitor.shared.net.HubSocketEvent
import com.kidsmonitor.shared.net.HubTrust
import com.kidsmonitor.shared.protocol.HubToParent
import com.kidsmonitor.shared.protocol.ParentToHub
import com.kidsmonitor.shared.store.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ServiceState {
    internal val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    internal val mutableConnection = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val connection: StateFlow<ConnectionState> = mutableConnection.asStateFlow()
}

/**
 * Holds the socket that delivers every alert.
 *
 * Typed `specialUse`, and the type is not a detail.
 *
 * `dataSync` is capped at six hours per rolling day on Android 15+, after which
 * the system stops the service and refuses to start another until the user
 * manually foregrounds the app. For a 24/7 alert socket that means alerts
 * silently stop overnight -- the worst failure mode this product has.
 *
 * `connectedDevice` looked right (it covers devices reached over a network) and
 * was used first, but on Android 17 it additionally requires one of
 * BLUETOOTH_*, NFC, USB, UWB or CHANGE_WIFI_STATE. Declaring one of those
 * purely to pass the check would be claiming a capability this app never uses,
 * so the type moved to `specialUse`: the category Android provides for a valid
 * case the other types do not cover. It carries no time limit, and the manifest
 * states the justification. See spec §3.4 and docs/lessons-learned.md.
 */
class AlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var socket: HubSocket? = null
    private var liveSession: LiveSession? = null
    private var historyRecovery: Job? = null
    private lateinit var monitorNames: MonitorNames
    private val notificationEvents = Channel<EventEntity>(Channel.UNLIMITED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Post the notification before anything that can fail or block. A
        // foreground service that has not called startForeground in time is
        // killed with a ForegroundServiceDidNotStartInTimeException.
        startForegroundCompat(socket?.connectionState?.value ?: ConnectionState.Connecting)

        val credentials = credentialsFactory(this).load()
        if (credentials == null) {
            // Not paired: there is no address to connect to. Stop rather than
            // loop against a hub we do not know about.
            stopSelf()
            return START_NOT_STICKY
        }

        if (socket == null) {
            val client = HubTrust.clientFor(credentials.fingerprint)
            val api = HubApi(client, credentials.baseUrl, credentials.token)
            monitorNames = MonitorNames(
                // A new pairing must not inherit names from another hub.
                getSharedPreferences("monitor_names_${credentials.deviceId}", MODE_PRIVATE),
                api::monitors,
            )
            scope.launch {
                // Resolve names off the socket consumer and preserve event order,
                // including an offline event followed immediately by recovery.
                for (event in notificationEvents) {
                    Notifications.notifyEvent(this@AlertService, event, monitorNames.nameFor(event.monitorId))
                }
            }
            val repository = EventRepository(
                ParentDatabase.open(this).events(),
                api,
            )
            val hubSocket = HubSocket(
                client = client,
                wsUrl = credentials.wsUrl,
                token = credentials.token,
                deviceName = Build.MODEL ?: "Android phone",
                appVersion = APP_VERSION,
            )
            socket = hubSocket
            scope.launch {
                hubSocket.connectionState.collect { state ->
                    ServiceState.mutableConnection.value = state
                    ServiceState.mutableConnected.value = state == ConnectionState.Connected
                    val power = getSystemService(PowerManager::class.java)
                    Log.i("KidsMonitorParent", "hub=$state idle=${power.isDeviceIdleMode} batterySaver=${power.isPowerSaveMode} exempt=${power.isIgnoringBatteryOptimizations(packageName)}")
                    startForegroundCompat(state)
                }
            }
            val live = LiveSession(
                gateway = object : StreamGateway {
                    override fun requestStream(monitorId: String) =
                        hubSocket.send(ParentToHub.RequestStream(monitorId = monitorId, video = true, audio = true))
                    override fun requestStream(monitorId: String, requestId: String) =
                        hubSocket.send(ParentToHub.RequestStream(monitorId, true, true, requestId))
                    override fun cancelRequest(monitorId: String, requestId: String) =
                        hubSocket.send(ParentToHub.CancelStreamRequest(monitorId, requestId))
                    override fun reconnectHub() = hubSocket.reconnectNow()
                    override fun stopStream(streamId: Int) =
                        hubSocket.send(ParentToHub.StopStream(streamId = streamId))
                },
                scope = scope,
                initialConnection = ConnectionState.Stopped,
            )
            liveSession = live
            LiveLink.attach(live, hubSocket)
            scope.launch { live.state.collect { Log.i("KidsMonitorParent", "live=$it") } }
            // Media is a separate, lossy flow - NOT a second collector on
            // hubSocket.events, which must keep exactly one consumer.
            // One collector per kind, each forwarding into its own buffer, so
            // video is dropped before audio on this hop too.
            for (flow in listOf(hubSocket.video, hubSocket.audio)) {
                scope.launch {
                    flow.collect { frame -> if (live.accepts(frame)) LiveLink.media.tryEmit(frame) }
                }
            }
            observe(hubSocket, repository, credentials.deviceId)
            hubSocket.connect(scope)
        }

        if (intent?.action == ACTION_RETRY) socket?.retryNow()

        // START_STICKY: if the OS kills this under memory pressure it is
        // recreated. Legitimate here -- unlike a BOOT_COMPLETED start, which
        // Android 15 forbids for camera/microphone types on the monitor side
        // (spec §3.2). connectedDevice carries no such restriction.
        return START_STICKY
    }

    private fun observe(hubSocket: HubSocket, repository: EventRepository, deviceId: String) {
        scope.launch {
            // Capture before consuming any queued live events, which may advance MAX(id).
            val recovery = getSharedPreferences("alert_history_recovery", MODE_PRIVATE)
            var historyCursor = if (recovery.contains(deviceId)) recovery.getLong(deviceId, 0) else repository.latestId()
            // Persist the captured cursor before consuming live arrivals. A process
            // restart during failed HTTP recovery must not skip that gap either.
            recovery.edit().putLong(deviceId, historyCursor).commit()
            hubSocket.events.collect { event ->
                when (event) {
                    is HubSocketEvent.Connected -> {
                        liveSession?.onConnectionChanged(ConnectionState.Connected)
                        hubSocket.send(ParentToHub.Subscribe(monitorIds = null))
                        scope.launch { monitorNames.refresh() }
                        historyRecovery?.cancel()
                        // History HTTP must never block stream progress or alert delivery.
                        // Keep the prior successful cursor if recovery fails while newer
                        // live events are recorded; MAX(id) would skip the missing gap.
                        historyRecovery = scope.launch {
                            try {
                                val recovered = repository.backfillFrom(historyCursor)
                                historyCursor = maxOf(historyCursor, recovered)
                                recovery.edit().putLong(deviceId, historyCursor).commit()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                Log.w("KidsMonitorParent", "alert history recovery failed", failure)
                            }
                        }
                    }

                    is HubSocketEvent.Disconnected -> {
                        liveSession?.onConnectionChanged(hubSocket.connectionState.value.takeUnless { it == ConnectionState.Connected } ?: ConnectionState.Reconnecting)
                    }

                    is HubSocketEvent.Message -> handle(event.message, repository, hubSocket)
                }
            }
        }
    }

    private suspend fun handle(
        message: HubToParent,
        repository: EventRepository,
        hubSocket: HubSocket,
    ) {
        when (message) {
            is HubToParent.CameraResult -> LiveLink.cameraReplies.tryEmit(message)
            is HubToParent.TalkState -> LiveLink.talkReplies.tryEmit(message)
            is HubToParent.Event -> {
                repository.record(message)
                notificationEvents.send(message.toEntity())
                hubSocket.send(ParentToHub.AckEvent(message.eventId))
            }
            is HubToParent.MonitorStatus -> monitorNames.remember(message.monitorId, message.name)
            is HubToParent.StreamWaiting -> liveSession?.onStreamWaiting(message)
            is HubToParent.StreamReady -> liveSession?.onStreamReady(message)
            is HubToParent.StreamEnded -> liveSession?.onStreamEnded(message)
            is HubToParent.ErrorMessage -> liveSession?.onError(message)
            else -> Unit
        }
    }

    private fun startForegroundCompat(connection: ConnectionState) {
        ServiceCompat.startForeground(
            this,
            Notifications.ONGOING_ID,
            Notifications.buildOngoing(this, connection),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        LiveLink.detach()
        liveSession = null
        socket?.disconnect()
        socket = null
        ServiceState.mutableConnected.value = false
        notificationEvents.close()
        scope.cancel()
        ServiceState.mutableConnection.value = ConnectionState.Stopped
        super.onDestroy()
    }

    companion object {
        const val APP_VERSION = "0.1.0"
        private const val ACTION_RETRY = "com.kidsmonitor.parent.RETRY_CONNECTION"

        /**
         * Test seam. Robolectric has no Android Keystore, so the encrypted
         * credential store throws when constructed in a JVM test. Production
         * always uses the real one; tests swap in plain preferences.
         */
        internal var credentialsFactory: (Context) -> Credentials = { Credentials(it) }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AlertService::class.java))
        }

        fun retry(context: Context) {
            context.startForegroundService(Intent(context, AlertService::class.java).setAction(ACTION_RETRY))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }
    }
}
