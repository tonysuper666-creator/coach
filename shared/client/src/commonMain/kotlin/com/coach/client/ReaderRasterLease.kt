package com.coach.client

/** Cache eviction cannot release a raster that a composed image still uses. */
class ReaderRasterLease<T : Any>(value: T, private val dispose: (T, Boolean) -> Unit) {
    /** Logical references only. Clearing a value does not certify renderer/native deallocation. */
    data class Ownership(val cacheOwned: Boolean, val composedUses: Int, val valueCleared: Boolean, val everDrawn: Boolean)
    private val lock = Any()
    private var raster: T? = value
    private var retired = false
    private var owners = 0
    private var drawn = false
    private var observer: ((Ownership) -> Unit)? = null

    /** Optional measurement hook: sends metadata, never the raster or a lease reference. */
    fun observeOwnership(observer: (Ownership) -> Unit) = synchronized(lock) {
        this.observer = observer
        reportOwnership()
        if (raster == null) this.observer = null
    }

    inner class Use internal constructor(value: T) {
        private var held: T? = value
        val value: T get() = synchronized(lock) { checkNotNull(held) }
        private var closed = false
        fun drawn() = synchronized(lock) {
            if (!closed && !this@ReaderRasterLease.drawn) { this@ReaderRasterLease.drawn = true; reportOwnership() }
        }
        fun close() = synchronized(lock) {
            if (!closed) { closed = true; held = null; owners--; release() }
        }
    }

    fun acquire(): Use? = synchronized(lock) {
        if (retired) null else raster?.let { owners++; reportOwnership(); Use(it) }
    }

    fun retire() = synchronized(lock) { if (!retired) { retired = true; release() } }

    private fun release() {
        if (retired && owners == 0) {
            val value = raster ?: return
            raster = null
            reportOwnership()
            observer = null
            dispose(value, drawn)
        } else reportOwnership()
    }

    private fun reportOwnership() {
        observer?.invoke(Ownership(!retired && raster != null, owners, raster == null, drawn))
    }
}
