package com.kidsmonitor.monitor.store

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

const val MAX_MONITOR_NAME_LENGTH = 64

fun defaultMonitorName(model: String? = Build.MODEL): String =
    model?.trim()?.take(MAX_MONITOR_NAME_LENGTH)?.takeIf { it.isNotEmpty() } ?: "Monitor"

fun monitorNameError(name: String): String? = when {
    name.trim().isEmpty() -> "Enter a name for this monitor."
    name.trim().length > MAX_MONITOR_NAME_LENGTH -> "Use 64 characters or fewer."
    else -> null
}

/** Uses the existing UI preference so previously named monitors keep their name. */
class MonitorName(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("monitor_ui", Context.MODE_PRIVATE))

    fun load(): String = preferences.getString("name", null)?.trim()?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL ?: "Room monitor"

    fun save(name: String) {
        require(monitorNameError(name) == null)
        check(preferences.edit().putString("name", name.trim()).commit())
    }
}
