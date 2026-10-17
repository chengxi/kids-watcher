package com.kidsmonitor.monitor.stream

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Keeps the last known orientation when flat; a 15 degree margin prevents boundary jitter. */
internal class OrientationTracker {
    private var quadrant = 0

    fun update(degrees: Int): Int {
        if (degrees in 0..359) {
            val distance = abs((degrees - quadrant * 90 + 540) % 360 - 180)
            if (distance >= 60) quadrant = ((degrees + 45) / 90) % 4
        }
        // Sensor angles run in the opposite direction to Surface rotations.
        return when (quadrant) {
            1 -> Surface.ROTATION_270
            2 -> Surface.ROTATION_180
            3 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }
}

/** Service-owned: learns the mount while the camera is off, independent of display rotation. */
class GravityOrientation(context: Context) {
    private val tracker = OrientationTracker()
    private val mutableRotation = MutableStateFlow(Surface.ROTATION_0)
    val rotation: StateFlow<Int> = mutableRotation.asStateFlow()

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            mutableRotation.value = tracker.update(orientation)
        }
    }

    fun start() { if (listener.canDetectOrientation()) listener.enable() }
    fun stop() = listener.disable()
}
