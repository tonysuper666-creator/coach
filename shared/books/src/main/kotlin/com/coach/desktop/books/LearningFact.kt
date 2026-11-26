package com.coach.desktop.books

import kotlinx.serialization.json.JsonObject
import java.sql.Connection

/** The optional Windows journal attaches the domain input to its projection in the same write transaction. */
internal fun withLearningFact(db: Connection, kind: String, fact: () -> JsonObject, write: () -> Unit) {
    if (!BookSchemaMigration.tableExists(db,"learning_sync_fact_context")) {
        write()
        return
    }
    db.createStatement().use { sql ->
        sql.execute("SAVEPOINT learning_fact")
        try {
            db.prepareStatement("INSERT INTO learning_sync_fact_context(kind,fact) VALUES(?,?)").use {
                it.setString(1,kind)
                it.setString(2,fact().toString())
                it.executeUpdate()
            }
            write()
            db.prepareStatement("DELETE FROM learning_sync_fact_context WHERE kind=?").use { it.setString(1,kind); it.executeUpdate() }
        } catch (failure: Throwable) {
            sql.execute("ROLLBACK TO learning_fact")
            throw failure
        } finally {
            sql.execute("RELEASE learning_fact")
        }
    }
}
