package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.ProjectionCache
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Runs the client cache against real SQLite, using the UI JVM driver's existing dependency. */
class PendingClientCacheUpgradeTest {
    @Test fun matchingCursorAndEpochCannotMakeAVersionTenScoreCacheReusable() {
        verifyUpgrade(10)
    }

    @Test fun matchingCursorAndEpochCannotReuseEitherPreMergeProjectionVersion() {
        verifyUpgrade(11)
        verifyUpgrade(12)
    }

    private fun verifyUpgrade(oldVersion: Int) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            ClientDatabase.Schema.create(driver)
            val db = ClientDatabase(driver)
            val cache = ProjectionCache(db)
            val old = Projection(version = oldVersion, lastSeq = 3, journal = mapOf(1L to Journal(answers = 1, correct = 1)))
            db.ledgerQueries.putProjection("C", oldVersion.toLong(), 3, "epoch", LedgerJson.encodeToString(old))
            assertNull(cache.get("C", "epoch", 3))
            val fresh = old.copy(version = PROJECTION_VERSION, journal = mapOf(1L to Journal(answers = 1)))
            cache.put("C", "epoch", 3, fresh)
            assertEquals(fresh, cache.get("C", "epoch", 3))
            assertEquals(13, assertNotNull(cache.get("C", "epoch", 3)).version)
        }
    }
}
