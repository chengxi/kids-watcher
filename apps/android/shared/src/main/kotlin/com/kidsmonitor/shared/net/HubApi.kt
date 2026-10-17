package com.kidsmonitor.shared.net

import com.kidsmonitor.shared.protocol.EventMetadata
import com.kidsmonitor.shared.protocol.EventType
import com.kidsmonitor.shared.protocol.NoiseSample
import com.kidsmonitor.shared.store.HubCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HubApiException(val statusCode: Int?, message: String) : Exception(message)

@Serializable
data class StoredEvent(
    val id: Long,
    val monitorId: String,
    val type: EventType,
    val tsMs: Long,
    val metadata: EventMetadata = EventMetadata(),
)

@Serializable
data class MonitorStatusDto(
    val id: String,
    val name: String,
    val online: Boolean,
    val lastSeenMs: Long? = null,
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
    val batteryTemperatureC: Double? = null,
    val speakerVolumePct: Int? = null,
)

@Serializable private data class PairRequest(
    val code: String,
    val deviceName: String,
    val expectRole: String? = null,
)
@Serializable private data class PairResponse(val deviceId: String, val token: String)
@Serializable private data class EventsResponse(val events: List<StoredEvent>)
@Serializable private data class MonitorsResponse(val monitors: List<MonitorStatusDto>)
@Serializable
data class NoiseHistoryResponse(
    val windowStartMs: Long,
    val toMs: Long,
    val samples: List<NoiseSample>,
    val events: List<StoredEvent> = emptyList(),
    val eventsTruncated: Boolean = false,
)
@Serializable private data class ErrorResponse(val error: String? = null)

class HubApi(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String?,
) {
    // HubTrust deliberately disables read timeout for long-lived WebSockets.
    // HTTP must still finish on a half-open connection, including response bodies.
    private val httpClient = client.newBuilder()
        .callTimeout(client.callTimeoutMillis.takeIf { it in 1..10_000 }?.toLong() ?: 10_000L, TimeUnit.MILLISECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Host, port and fingerprint come from what was scanned or confirmed, never
     * from the response body -- a server must not be able to redirect us
     * elsewhere. [expectRole] is only what the caller believes it typed into;
     * the hub still takes the role from the code itself.
     */
    suspend fun pair(
        code: String,
        deviceName: String,
        expectRole: String,
        baseUrl: String,
        wsUrl: String,
        fingerprint: String,
    ): HubCredentials {
        val body = json.encodeToString(
            PairRequest.serializer(),
            PairRequest(code, deviceName, expectRole),
        )
        val response = try {
            execute(
                Request.Builder()
                    // this.baseUrl: where the request goes. The `baseUrl`
                    // parameter is what gets stored, and for the QR path those
                    // differ in tests.
                    .url("${this.baseUrl}/pair")
                    .post(body.toRequestBody(jsonMedia))
                    .build(),
            )
        } catch (e: HubApiException) {
            throw pairingException(e)
        }
        val parsed = json.decodeFromString(PairResponse.serializer(), response)
        return HubCredentials(
            baseUrl = baseUrl,
            wsUrl = wsUrl,
            fingerprint = fingerprint,
            deviceId = parsed.deviceId,
            token = parsed.token,
        )
    }

    suspend fun pair(payload: PairingPayload, deviceName: String): HubCredentials = pair(
        code = payload.code,
        deviceName = deviceName,
        expectRole = payload.role,
        baseUrl = payload.baseUrl,
        wsUrl = payload.wsUrl,
        fingerprint = payload.fingerprint,
    )

    // The generic status messages are wrong for pairing: 409 means "the monitor
    // is offline" everywhere else, and 429 does not occur elsewhere at all.
    private fun pairingException(e: HubApiException): HubApiException = when (e.statusCode) {
        409 -> HubApiException(409, "that code belongs to the other phone - type it there instead")
        429 -> HubApiException(429, "too many wrong codes; the hub is refusing codes for a while")
        else -> e
    }

    suspend fun events(since: Long, limit: Int = 200): List<StoredEvent> {
        val response = execute(authed("$baseUrl/events?since=$since&limit=$limit").build())
        return json.decodeFromString(EventsResponse.serializer(), response).events
    }

    suspend fun monitors(): List<MonitorStatusDto> {
        val response = execute(authed("$baseUrl/monitors").build())
        return json.decodeFromString(MonitorsResponse.serializer(), response).monitors
    }

    suspend fun noiseHistory(monitorId: String, fromMs: Long? = null): NoiseHistoryResponse {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("monitors")
            addPathSegment(monitorId)
            addPathSegment("noise-history")
            fromMs?.let { addQueryParameter("fromMs", it.toString()) }
        }.build()
        return json.decodeFromString(NoiseHistoryResponse.serializer(), execute(authed(url.toString()).build()))
    }

    private fun authed(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(HubApiException(null, "could not reach the hub: ${e.message}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            val text = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw HubApiException(it.code, describe(it.code, text))
                            text
                        }
                        continuation.resume(body)
                    } catch (e: Exception) {
                        continuation.resumeWithException(
                            if (e is HubApiException) e else HubApiException(null, "could not reach the hub: ${e.message}"),
                        )
                    }
                }
            })
        }
    }

    private fun describe(code: Int, body: String): String {
        val error = runCatching {
            json.decodeFromString(ErrorResponse.serializer(), body).error
        }.getOrNull()
        return when {
            code == 403 && error == "invalid_code" ->
                "that pairing code has expired or was already used - generate a new one on the hub"
            code == 401 -> "this device is not authorized; it may have been revoked on the hub"
            code == 404 -> "the hub does not know about that resource"
            code == 409 -> "the monitor is offline"
            else -> "hub returned HTTP $code${error?.let { " ($it)" }.orEmpty()}"
        }
    }
}
