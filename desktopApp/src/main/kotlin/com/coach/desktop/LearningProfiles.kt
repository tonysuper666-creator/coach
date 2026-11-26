package com.coach.desktop

import com.coach.client.CourseHttp
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

internal data class LearningProfile(val directory: Path, val scope: String?, val epoch: String? = null)

/** A cached authenticated session can reopen its own library offline. New sessions start isolated. */
internal class LearningProfiles(private val legacy: Path) {
    private val profiles = legacy.resolveSibling("${legacy.fileName}-learning-profiles")
    val hasProfiles get() = Files.isRegularFile(profiles.resolve("profiles.sqlite"))

    suspend fun open(identity: DesktopIdentity, online: Boolean): LearningProfile = withContext(Dispatchers.IO) {
        val credential = identity.credentials
        val session = "local-" + MessageDigest.getInstance("SHA-256").digest(
            "${identity.serverUrl}\u0000${credential.role}\u0000${credential.deviceId}".encodeToByteArray()
        ).let(java.util.HexFormat.of()::formatHex)
        if (!online) return@withContext select(session,null,true)
        val http = CourseHttp(identity.serverUrl,credential.deviceId,credential.token,"library")
        try {
            val profile = http.learningProfile() ?: return@withContext select(session,null,false)
            select(session,profile.getValue("scope").jsonPrimitive.content,false,profile.getValue("epoch").jsonPrimitive.content)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: java.io.IOException) {
            select(session,null,true)
        } finally { http.close() }
    }

    internal fun select(session: String, verified: String?, allowCached: Boolean, epoch: String? = null): LearningProfile {
        require(session.matches(Regex("local-[0-9a-f]{64}")))
        require(verified == null || verified.matches(Regex("sable-[0-9a-f]{64}")))
        require(epoch == null || java.util.UUID.fromString(epoch).toString() == epoch)
        Files.createDirectories(profiles)
        return DriverManager.getConnection("jdbc:sqlite:${profiles.resolve("profiles.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.execute("PRAGMA busy_timeout=5000")
                sql.execute("BEGIN IMMEDIATE")
                try {
                    sql.execute("CREATE TABLE IF NOT EXISTS sessions(session TEXT PRIMARY KEY,scope TEXT NOT NULL)")
                    sql.execute("CREATE TABLE IF NOT EXISTS profiles(scope TEXT PRIMARY KEY,original INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE IF NOT EXISTS profile_directories(scope TEXT PRIMARY KEY,session TEXT NOT NULL UNIQUE)")
                    sql.execute("CREATE TABLE IF NOT EXISTS generations(scope TEXT PRIMARY KEY,epoch TEXT NOT NULL,rotated INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE IF NOT EXISTS retired_epochs(scope TEXT NOT NULL,epoch TEXT NOT NULL,PRIMARY KEY(scope,epoch))")
                    if (verified != null && epoch != null) {
                        val previous = db.prepareStatement("SELECT epoch FROM generations WHERE scope=?").use { q ->
                            q.setString(1,verified)
                            q.executeQuery().use { if (it.next()) it.getString(1) else null }
                        }
                        if (previous != epoch) {
                            val retired = db.prepareStatement("SELECT 1 FROM retired_epochs WHERE scope=? AND epoch=?").use { q ->
                                q.setString(1,verified); q.setString(2,epoch)
                                q.executeQuery().use { it.next() }
                            }
                            if (retired) {
                                sql.execute("ROLLBACK")
                                return LearningProfile(profiles.resolve("unverified-$session"),null)
                            }
                            if (previous != null) {
                                db.prepareStatement("INSERT INTO retired_epochs VALUES(?,?)").use {
                                    it.setString(1,verified); it.setString(2,previous); it.executeUpdate()
                                }
                                for (statement in listOf("DELETE FROM sessions WHERE scope=?",
                                    "DELETE FROM profile_directories WHERE scope=?", "UPDATE profiles SET original=0 WHERE scope=?")) {
                                    db.prepareStatement(statement).use { it.setString(1,verified); it.executeUpdate() }
                                }
                            }
                            db.prepareStatement("INSERT OR REPLACE INTO generations VALUES(?,?,?)").use {
                                it.setString(1,verified); it.setString(2,epoch); it.setInt(3,if (previous == null) 0 else 1); it.executeUpdate()
                            }
                        }
                    }
                    val cached = db.prepareStatement("SELECT scope FROM sessions WHERE session=?").use { q ->
                        q.setString(1,session)
                        q.executeQuery().use { if (it.next()) it.getString(1) else null }
                    }
                    val scope = verified ?: cached?.takeIf { allowCached }
                    val result = if (scope == null) {
                        val folder = if (cached == null) session else "unverified-$session"
                        LearningProfile(profiles.resolve(folder),null)
                    } else {
                        if (verified != null) db.prepareStatement("INSERT OR REPLACE INTO sessions VALUES(?,?)").use {
                            it.setString(1,session); it.setString(2,scope); it.executeUpdate()
                        }
                        val original = db.prepareStatement("SELECT original FROM profiles WHERE scope=?").use { q ->
                            q.setString(1,scope)
                            q.executeQuery().use { if (it.next()) it.getInt(1) == 1 else null }
                        } ?: run {
                            val first = sql.executeQuery("SELECT 1 FROM profiles LIMIT 1").use { !it.next() }
                            val localEdits = Files.exists(profiles.resolve(session).resolve("library.sqlite"))
                            val claim = first && verified != null && !localEdits && canClaim(scope)
                            if (localEdits) db.prepareStatement("INSERT OR IGNORE INTO profile_directories VALUES(?,?)").use {
                                it.setString(1,scope)
                                it.setString(2,session)
                                it.executeUpdate()
                            }
                            db.prepareStatement("INSERT INTO profiles VALUES(?,?)").use {
                                it.setString(1,scope); it.setInt(2,if (claim) 1 else 0); it.executeUpdate()
                            }
                            claim
                        }
                        val generation = db.prepareStatement("SELECT epoch,rotated FROM generations WHERE scope=?").use { q ->
                            q.setString(1,scope)
                            q.executeQuery().use { if (it.next()) it.getString(1) to (it.getInt(2) != 0) else null }
                        }
                        val rotated = generation?.takeIf { it.second }?.first
                        val folder = rotated?.let { "$scope-$it" } ?: db.prepareStatement("SELECT session FROM profile_directories WHERE scope=?").use { q ->
                            q.setString(1,scope)
                            q.executeQuery().use { if (it.next()) it.getString(1) else scope }
                        }
                        LearningProfile(if (original) legacy else profiles.resolve(folder),scope,generation?.first)
                    }
                    sql.execute("COMMIT")
                    result
                } catch (e: Throwable) {
                    sql.execute("ROLLBACK")
                    throw e
                }
            }
        }
    }

    private fun canClaim(scope: String): Boolean {
        val file = legacy.resolve("library.sqlite")
        if (!Files.exists(file)) return true
        return DriverManager.getConnection("jdbc:sqlite:${file.toUri()}?mode=ro").use { db ->
            db.createStatement().use { sql ->
                val tables = sql.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { r ->
                    buildSet { while (r.next()) add(r.getString(1)) }
                }
                val bindings = mutableMapOf<String,String>()
                if ("book_upload_account_bindings" in tables) {
                    sql.executeQuery("SELECT session_key,account_key FROM book_upload_account_bindings").use { r ->
                        while (r.next()) {
                            if (r.getString(2) != scope) return false
                            bindings[r.getString(1)] = scope
                        }
                    }
                }
                val hasKeys = sql.executeQuery("PRAGMA table_info(books)").use { r ->
                    generateSequence { if (r.next()) r.getString("name") else null }.any { it == "import_account_key" }
                }
                if (hasKeys) {
                    sql.executeQuery("SELECT DISTINCT import_account_key FROM books WHERE import_account_key IS NOT NULL").use { r ->
                        while (r.next()) {
                            val key = r.getString(1)
                            if (key != scope && bindings[key] != scope) return false
                        }
                    }
                }
                true
            }
        }
    }
}
