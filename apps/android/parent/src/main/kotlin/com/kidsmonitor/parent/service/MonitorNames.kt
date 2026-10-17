package com.kidsmonitor.parent.service

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.kidsmonitor.shared.net.MonitorStatusDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device names remain available when the service restarts or HTTP is unavailable. */
internal class MonitorNames(
    private val preferences: SharedPreferences,
    private val load: suspend () -> List<MonitorStatusDto>,
) {
    private val refreshLock = Mutex()

    fun remember(id: String, name: String) {
        if (name.isNotBlank()) preferences.edit { putString(id, name) }
    }

    private fun cached(id: String): String? = preferences.getString(id, null)

    suspend fun refresh() = refreshLock.withLock { fetch() }

    suspend fun nameFor(id: String): String {
        cached(id)?.let { return it }
        // A newly paired monitor may not have been present on the last reconnect.
        refreshLock.withLock {
            if (cached(id) == null) fetch()
        }
        return cached(id) ?: "Monitor ${id.take(8)}"
    }

    private suspend fun fetch() {
        try {
            load().forEach { remember(it.id, it.name) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("KidsMonitorParent", "monitor names unavailable", failure)
        }
    }
}
