package com.coach.store.reader

import com.coach.domain.reader.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

class ReaderAnchorConflict : IllegalStateException("Reader anchor revision conflict")
class ReaderVersionConflict : IllegalStateException("Reader document index is immutable for this version")

/**
 * Independent local service module; does not migrate the study ledger or install HTTP routes.
 * The caller supplies a private directory scoped to the authenticated library/account.
 */
class ReaderAnchorStore(directory: Path) : AutoCloseable {
    private val db: Connection
    init {
        Files.createDirectories(directory)
        db = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("reader-anchors.sqlite")}")
        db.createStatement().use { s ->
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("""CREATE TABLE IF NOT EXISTS reader_indexes(
                document_id TEXT NOT NULL, sha256 TEXT NOT NULL, body TEXT NOT NULL,
                PRIMARY KEY(document_id,sha256))""")
            s.execute("""CREATE TABLE IF NOT EXISTS reader_anchors(
                id TEXT PRIMARY KEY, revision INTEGER NOT NULL, body TEXT NOT NULL)""")
            s.execute("""CREATE TABLE IF NOT EXISTS reader_resolutions(
                anchor_id TEXT NOT NULL REFERENCES reader_anchors(id),
                document_id TEXT NOT NULL, sha256 TEXT NOT NULL, body TEXT NOT NULL,
                PRIMARY KEY(anchor_id,document_id,sha256))""")
            s.execute("PRAGMA foreign_keys=ON")
        }
    }

    @Synchronized fun register(index: ReaderDocumentIndex) {
        val encoded = ReaderAnchorJson.encodeToString(index)
        db.prepareStatement("INSERT OR IGNORE INTO reader_indexes(document_id,sha256,body) VALUES(?,?,?)").use { s ->
            s.setString(1, index.version.documentId); s.setString(2, index.version.sha256); s.setString(3, encoded); s.executeUpdate()
        }
        if (document(index.version) != index) throw ReaderVersionConflict()
    }

    @Synchronized fun document(version: DocumentVersion): ReaderDocumentIndex? {
        val raw = row("SELECT body FROM reader_indexes WHERE document_id=? AND sha256=?", version.documentId, version.sha256) ?: return null
        return ReaderAnchorJson.decodeFromString<ReaderDocumentIndex>(raw).takeIf { it.version == version }
    }

    /** expectedRevision=null creates; updates require CAS and may change only note/progress. */
    @Synchronized fun save(entry: ReaderAnchorEntry, expectedRevision: Long? = null): ReaderAnchorEntry {
        if (expectedRevision == null) {
            require(entry.revision == 0L)
            val capturedIndex = requireNotNull(document(entry.anchor.version)) { "Capture index must be registered first" }
            val check = ReaderAnchorResolver.resolve(entry.anchor, capturedIndex)
            require(entry.anchor.originalRange != null && check.reason == AnchorReason.SAME_VERSION_RANGE &&
                check.target?.resource == entry.anchor.resource && check.target?.range == entry.anchor.originalRange) {
                "Anchor must resolve to its captured index resource and range"
            }
            val saved = entry.copy(revision = 1)
            val changed = db.prepareStatement("INSERT OR IGNORE INTO reader_anchors(id,revision,body) VALUES(?,1,?)").use { s ->
                s.setString(1, entry.anchor.id); s.setString(2, ReaderAnchorJson.encodeToString(saved)); s.executeUpdate()
            }
            if (changed != 1) throw ReaderAnchorConflict()
            return saved
        }
        require(expectedRevision in 1 until Long.MAX_VALUE && entry.revision == expectedRevision)
        val old = get(entry.anchor.id) ?: throw ReaderAnchorConflict()
        if (old.revision != expectedRevision || old.anchor != entry.anchor) throw ReaderAnchorConflict()
        val saved = entry.copy(revision = expectedRevision + 1)
        val changed = db.prepareStatement("UPDATE reader_anchors SET revision=?,body=? WHERE id=? AND revision=?").use { s ->
            s.setLong(1, saved.revision); s.setString(2, ReaderAnchorJson.encodeToString(saved))
            s.setString(3, entry.anchor.id); s.setLong(4, expectedRevision); s.executeUpdate()
        }
        if (changed != 1) throw ReaderAnchorConflict()
        return saved
    }

    @Synchronized fun get(id: String): ReaderAnchorEntry? = row("SELECT body FROM reader_anchors WHERE id=?", id)
        ?.let { ReaderAnchorJson.decodeFromString<ReaderAnchorEntry>(it) }

    /** Resolve from the local index, never trust a client-provided resolved target. Original entry is untouched. */
    @Synchronized fun resolve(id: String, targetVersion: DocumentVersion): AnchorResolution {
        val entry = requireNotNull(get(id)) { "Unknown reader anchor" }
        val index = requireNotNull(document(targetVersion)) { "Unknown reader document version" }
        val result = ReaderAnchorResolver.resolve(entry.anchor, index)
        db.prepareStatement("""INSERT INTO reader_resolutions(anchor_id,document_id,sha256,body) VALUES(?,?,?,?)
            ON CONFLICT(anchor_id,document_id,sha256) DO UPDATE SET body=excluded.body""").use { s ->
            s.setString(1, id); s.setString(2, targetVersion.documentId); s.setString(3, targetVersion.sha256)
            s.setString(4, ReaderAnchorJson.encodeToString(result)); s.executeUpdate()
        }
        return result
    }

    @Synchronized fun resolution(id: String, targetVersion: DocumentVersion): AnchorResolution? =
        row("SELECT body FROM reader_resolutions WHERE anchor_id=? AND document_id=? AND sha256=?",
            id, targetVersion.documentId, targetVersion.sha256)
            ?.let { ReaderAnchorJson.decodeFromString<AnchorResolution>(it) }?.takeIf { it.targetVersion == targetVersion }

    private fun row(sql: String, vararg values: String): String? = db.prepareStatement(sql).use { s ->
        values.forEachIndexed { i, value -> s.setString(i + 1, value) }
        s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
    }

    @Synchronized override fun close() { db.close() }

    companion object {
        fun version(documentId: String, bytes: ByteArray, format: ReaderFormat): DocumentVersion =
            DocumentVersion(documentId, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, format)
    }
}
