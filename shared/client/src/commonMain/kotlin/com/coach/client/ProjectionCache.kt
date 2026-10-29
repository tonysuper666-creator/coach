package com.coach.client
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** A disposable view snapshot, guarded by projection version, ledger cursor and server epoch. */
class ProjectionCache(private val db: ClientDatabase) {
    fun get(course: String, epoch: String, lastSeq: Long): Projection? =
        db.ledgerQueries.getProjection(course,PROJECTION_VERSION.toLong(),lastSeq,epoch).executeAsOneOrNull()
            ?.let { LedgerJson.decodeFromString<Projection>(it) }
    fun put(course: String, epoch: String, lastSeq: Long, state: Projection) {
        require(state.lastSeq==lastSeq)
        db.ledgerQueries.putProjection(course,PROJECTION_VERSION.toLong(),lastSeq,epoch,LedgerJson.encodeToString(state))
    }
}
