package com.kidsmonitor.parent.ui.alerts

import com.kidsmonitor.shared.net.MonitorStatusDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.kidsmonitor.shared.net.HubApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class MonitorListState(
    val monitors: List<MonitorStatusDto> = emptyList(),
    val current: Boolean = false,
    val refreshing: Boolean = false,
    val failed: Boolean = false,
    val attempt: Int = 0,
    val retryDelayMs: Long? = null,
    val failureText: String? = null,
)

/** Failed requests mean unknown availability, never evidence of a monitor going offline. */
class MonitorStatusStore(private val maxAttempts: Int = 3, private val load: suspend () -> List<MonitorStatusDto>) {
    private val mutableState = MutableStateFlow(MonitorListState())
    val state = mutableState.asStateFlow()
    private val refreshLock = Mutex()
    private var generation = 0

    fun invalidate() {
        generation++
        mutableState.value = mutableState.value.copy(current = false)
    }

    suspend fun refresh() {
        if (!refreshLock.tryLock()) return
        val startedGeneration = generation
        mutableState.value = mutableState.value.copy(refreshing = true, failed = false, attempt = 1, retryDelayMs = null, failureText = null)
        try {
            for (attempt in 1..maxAttempts) {
                mutableState.value = mutableState.value.copy(attempt = attempt, retryDelayMs = null)
                try {
                    val monitors = load()
                    mutableState.value = MonitorListState(monitors, current = generation == startedGeneration)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val code = (failure as? HubApiException)?.statusCode
                    val retryable = code == null || code >= 500
                    val waitMs = if (attempt < maxAttempts && retryable && generation == startedGeneration) attempt * 1_000L else null
                    val message = when {
                        code == 401 || code == 403 -> "The hub refused this phone's authorization."
                        code != null -> "The hub could not return monitor status (HTTP $code)."
                        (failure.message.orEmpty().contains("timeout", ignoreCase = true) || failure.message.orEmpty().contains("timed out", ignoreCase = true)) -> "The hub status request timed out. Check this phone's network and the home hub."
                        else -> "Could not reach the hub for monitor status. Check this phone's network and the home hub."
                    }
                    mutableState.value = mutableState.value.copy(current = false, failed = true, retryDelayMs = waitMs, failureText = message)
                    if (waitMs == null) return
                    delay(waitMs)
                    if (generation != startedGeneration) return
                }
            }
        } finally {
            mutableState.value = mutableState.value.copy(refreshing = false, retryDelayMs = null)
            refreshLock.unlock()
        }
    }
}
