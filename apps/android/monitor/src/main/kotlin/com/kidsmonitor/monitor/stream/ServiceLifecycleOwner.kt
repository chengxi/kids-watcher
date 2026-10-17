package com.kidsmonitor.monitor.stream

import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * CameraX binds use cases to a LifecycleOwner, and a Service is not one. This
 * stays RESUMED for the service's life; individual streams bind and unbind use
 * cases rather than moving the lifecycle.
 */
class ServiceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    @MainThread fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    @MainThread fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
