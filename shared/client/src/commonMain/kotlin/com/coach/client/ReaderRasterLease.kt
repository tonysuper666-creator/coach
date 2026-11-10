package com.coach.client

/** Cache eviction cannot release a raster that a composed image still uses. */
class ReaderRasterLease<T : Any>(value: T, private val dispose: (T, Boolean) -> Unit) {
    private val lock = Any()
    private var raster: T? = value
    private var retired = false
    private var owners = 0
    private var drawn = false

    inner class Use internal constructor(value: T) {
        private var held: T? = value
        val value: T get() = synchronized(lock) { checkNotNull(held) }
        private var closed = false
        fun drawn() = synchronized(lock) { if (!closed) this@ReaderRasterLease.drawn = true }
        fun close() = synchronized(lock) {
            if (!closed) { closed = true; held = null; owners--; release() }
        }
    }

    fun acquire(): Use? = synchronized(lock) {
        if (retired) null else raster?.let { owners++; Use(it) }
    }

    fun retire() = synchronized(lock) { retired = true; release() }

    private fun release() {
        if (retired && owners == 0) {
            val value = raster ?: return
            raster = null
            dispose(value, drawn)
        }
    }
}
