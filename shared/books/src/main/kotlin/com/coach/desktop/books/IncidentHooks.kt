package com.coach.desktop.books

/**
 * Failure hook for import and sync. TON-1906 calls [importFailed] when a book
 * cannot be opened; the desktop process installs the writer. No-op until then.
 */
object IncidentHooks {
    private var importFailure: ((String) -> Unit)? = null
    private var syncFailure: ((String) -> Unit)? = null

    fun install(onImportFailure: (String) -> Unit, onSyncFailure: (String) -> Unit) {
        importFailure = onImportFailure
        syncFailure = onSyncFailure
    }

    fun importFailed(detail: String) {
        importFailure?.invoke(detail)
    }

    fun syncFailed(detail: String) {
        syncFailure?.invoke(detail)
    }
}
