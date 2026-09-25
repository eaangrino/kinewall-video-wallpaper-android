package com.eaangrino.kinewall

internal object WallpaperMediaResourceCoordinator {

    private val lock = Any()
    private val listeners = LinkedHashSet<(Boolean) -> Unit>()

    @Volatile
    private var processingActive = false

    fun isProcessingActive(): Boolean = processingActive

    fun addListener(listener: (Boolean) -> Unit) {
        val active = synchronized(lock) {
            listeners.add(listener)
            processingActive
        }
        if (active) {
            listener(true)
        }
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    fun setProcessingActive(active: Boolean) {
        val callbacks = synchronized(lock) {
            if (processingActive == active) {
                return
            }
            processingActive = active
            listeners.toList()
        }
        callbacks.forEach { listener -> listener(active) }
    }
}
