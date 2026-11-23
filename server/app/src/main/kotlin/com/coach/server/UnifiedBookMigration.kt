package com.coach.server

import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.domain.num
import com.coach.domain.str
import com.coach.store.PairedDevice
import com.coach.store.sha256
import com.coach.store.tutorSourceAllowed
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class LegacyUnmatchedItem(
    val recordId: String,
    val sourceKind: String,
    val reason: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("recordId", recordId)
        put("sourceKind", sourceKind)
        put("reason", reason)
    }
}

internal data class UnifiedBookInventoryCounts(
    val catalogTotal: Int = 0,
    val catalogReady: Int = 0,
    val catalogAlreadyMigrated: Int = 0,
    val catalogUnmatched: Int = 0,
    val localBindingsTotal: Int = 0,
    val localBindingsReady: Int = 0,
    val localBindingsAlreadyMigrated: Int = 0,
    val localBindingsUnmatched: Int = 0,
    val ownerReceiptsTotal: Int = 0,
    val ownerReceiptsAlignable: Int = 0,
    val ownerReceiptsAlreadyAligned: Int = 0,
    val ownerReceiptsAwaitingFullUpload: Int = 0,
    val ownerReceiptsUnmatched: Int = 0,
    val friendReceiptsTotal: Int = 0,
    val friendReceiptsAlignable: Int = 0,
    val friendReceiptsAlreadyAligned: Int = 0,
    val friendReceiptsAwaitingFullUpload: Int = 0,
    val friendReceiptsUnmatched: Int = 0,
    val legacyFriendExplanationsTotal: Int = 0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("catalogTotal", catalogTotal)
        put("catalogReady", catalogReady)
        put("catalogAlreadyMigrated", catalogAlreadyMigrated)
        put("catalogUnmatched", catalogUnmatched)
        put("localBindingsTotal", localBindingsTotal)
        put("localBindingsReady", localBindingsReady)
        put("localBindingsAlreadyMigrated", localBindingsAlreadyMigrated)
        put("localBindingsUnmatched", localBindingsUnmatched)
        put("ownerReceiptsTotal", ownerReceiptsTotal)
        put("ownerReceiptsAlignable", ownerReceiptsAlignable)
        put("ownerReceiptsAlreadyAligned", ownerReceiptsAlreadyAligned)
        put("ownerReceiptsAwaitingFullUpload", ownerReceiptsAwaitingFullUpload)
        put("ownerReceiptsUnmatched", ownerReceiptsUnmatched)
        put("friendReceiptsTotal", friendReceiptsTotal)
        put("friendReceiptsAlignable", friendReceiptsAlignable)
        put("friendReceiptsAlreadyAligned", friendReceiptsAlreadyAligned)
        put("friendReceiptsAwaitingFullUpload", friendReceiptsAwaitingFullUpload)
        put("friendReceiptsUnmatched", friendReceiptsUnmatched)
        put("legacyFriendExplanationsTotal", legacyFriendExplanationsTotal)
    }
}

internal data class UnifiedBookInventoryReport(
    val counts: UnifiedBookInventoryCounts,
    val unmatched: List<LegacyUnmatchedItem>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("mode", "inventory")
        put("counts", counts.toJson())
        put("unmatched", JsonArray(unmatched.map { it.toJson() }))
    }
}

internal data class UnifiedBookAccountVerification(
    val accountRef: String,
    val shelfBooks: Int,
    val notes: Int,
    val qa: Int,
    val overrides: Int,
    val legacyMappings: Int,
    val alignedReceipts: Int,
    val sharedRefs: Int,
    val missingBlobs: Int,
    val corruptedBlobs: Int,
    val orphanAccountRecords: Int,
    val refMismatches: Int,
) {
    val ok: Boolean
        get() = missingBlobs == 0 && corruptedBlobs == 0 && orphanAccountRecords == 0 && refMismatches == 0

    fun toJson(): JsonObject = buildJsonObject {
        put("accountRef", accountRef)
        put("shelfBooks", shelfBooks)
        put("notes", notes)
        put("qa", qa)
        put("overrides", overrides)
        put("legacyMappings", legacyMappings)
        put("alignedReceipts", alignedReceipts)
        put("sharedRefs", sharedRefs)
        put("missingBlobs", missingBlobs)
        put("corruptedBlobs", corruptedBlobs)
        put("orphanAccountRecords", orphanAccountRecords)
        put("refMismatches", refMismatches)
        put("ok", ok)
    }
}

internal data class UnifiedBookVerificationReport(
    val globalSharedBlobs: Int,
    val globalSharedRefs: Int,
    val globalRefCountMismatches: Int,
    val globalDanglingRefs: Int,
    val accounts: List<UnifiedBookAccountVerification>,
) {
    val ok: Boolean
        get() = globalRefCountMismatches == 0 && globalDanglingRefs == 0 && accounts.all { it.ok }

    fun toJson(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("mode", "verify")
        put("ok", ok)
        put("globalSharedBlobs", globalSharedBlobs)
        put("globalSharedRefs", globalSharedRefs)
        put("globalRefCountMismatches", globalRefCountMismatches)
        put("globalDanglingRefs", globalDanglingRefs)
        put("accounts", JsonArray(accounts.map { it.toJson() }))
    }
}

internal data class UnifiedBookMigrationReport(
    val inventoryBefore: UnifiedBookInventoryReport,
    val migratedBooks: Int,
    val alignedReceipts: Int,
    val alignedBlocks: Int,
    val deduplicatedSkipped: Int,
    val unmatched: List<LegacyUnmatchedItem>,
    val verification: UnifiedBookVerificationReport,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("mode", "migrate")
        put("migratedBooks", migratedBooks)
        put("alignedReceipts", alignedReceipts)
        put("alignedBlocks", alignedBlocks)
        put("deduplicatedSkipped", deduplicatedSkipped)
        put("inventoryBefore", inventoryBefore.toJson())
        put("unmatched", JsonArray(unmatched.map { it.toJson() }))
        put("verification", verification.toJson())
    }
}

/**
 * Legacy-to-unified book inventory, idempotent migration, and per-account verification (TON-1823 PR B).
 *
 * Guarantees:
 * - [inventory] and [verify] are strictly read-only: they never create `unified-books/` or mutate legacy stores.
 * - Reports emit only counts and opaque record IDs; book titles, filenames, usernames, raw device IDs, and book prose
 *   never appear in any output.
 * - [migrate] preserves all legacy catalogs, bindings, personal-imports, and friend directories intact for rollback.
 * - Account mappings are established only when BOTH full original bytes (verified SHA-256) AND ownership are verified;
 *   fragment-only receipts remain `awaiting_full_upload` until the owning account uploads the full file.
 * - Repeated runs deduplicate cleanly by mapping and verify per-account books, notes, Q&A, overrides, and shared refs.
 */
class UnifiedBookMigration internal constructor(
    private val dataDir: Path,
    private val readerRoot: Path?,
    private val unifiedBooks: UnifiedBookPipeline,
    private val adminDevice: String = "desktop",
    private val listDevices: suspend () -> List<PairedDevice>,
) {
    private val migrationLock = Mutex()
    private val ownerScope: UnifiedBookScope get() = UnifiedBookScope.fromCall(adminDevice, null)

    private data class VerifiedCatalogCandidate(
        val recordId: String,
        val sourceKind: String,
        val legacyDocId: String,
        val courseId: String,
        val title: String,
        val file: Path,
        val format: String,
        val sha256: String,
        val pages: Int,
        val printedPageOffset: Int?,
        val explainSourceId: String?,
        val pagesSha256: String?,
        val alreadyMigrated: Boolean,
    )

    private data class VerifiedReceiptCandidate(
        val recordId: String,
        val sourceKind: String,
        val targetScope: UnifiedBookScope,
        val bookSha256: String,
        val shelfBookId: String,
        val rows: List<UnifiedBookPipeline.LegacyReceiptAlignmentRow>,
        val alreadyAligned: Boolean,
    )

    private data class ScanResult(
        val counts: UnifiedBookInventoryCounts,
        val unmatched: List<LegacyUnmatchedItem>,
        val catalogCandidates: List<VerifiedCatalogCandidate>,
        val receiptCandidates: List<VerifiedReceiptCandidate>,
    )

    /** Strictly read-only inventory of legacy course catalogs, local bindings, and personal/friend receipts. */
    internal suspend fun inventory(): UnifiedBookInventoryReport {
        val devices = listDevices()
        return withContext(Dispatchers.IO) {
            val scan = scanReadOnly(devices)
            UnifiedBookInventoryReport(scan.counts, scan.unmatched)
        }
    }

    /**
     * Runs idempotent migration after a read-only inventory snapshot, followed by per-account verification.
     * Requires `unifiedBooks.enabled == true`; leaves all legacy files/tables untouched for rollback.
     */
    internal suspend fun migrate(): UnifiedBookMigrationReport = migrationLock.withLock {
        check(unifiedBooks.enabled) { "Unified books feature flag must be enabled before migration" }
        val devices = listDevices()
        val beforeScan = withContext(Dispatchers.IO) { scanReadOnly(devices) }
        val unmatched = beforeScan.unmatched.toMutableList()
        var migratedBooks = 0
        var deduplicatedSkipped = 0

        for (candidate in beforeScan.catalogCandidates) {
            try {
                val (shelfBook, alreadyExistedOnScope) = unifiedBooks.importVerifiedLocalFile(
                    scope = ownerScope,
                    file = candidate.file,
                    formatRaw = candidate.format,
                    titleRaw = candidate.title,
                    expectedSha256 = candidate.sha256,
                )
                val mappingInserted = withContext(Dispatchers.IO) {
                    unifiedBooks.upsertLegacyBookMapping(
                        scope = ownerScope,
                        legacyRecordId = candidate.recordId,
                        sourceKind = candidate.sourceKind,
                        legacyDocId = candidate.legacyDocId,
                        courseId = candidate.courseId,
                        explainSourceId = candidate.explainSourceId,
                        pages = candidate.pages,
                        printedPageOffset = candidate.printedPageOffset,
                        pagesSha256 = candidate.pagesSha256,
                        sha256 = candidate.sha256,
                        shelfBookId = shelfBook.shelfBookId,
                    )
                }
                if (alreadyExistedOnScope && !mappingInserted) {
                    deduplicatedSkipped++
                } else {
                    migratedBooks++
                }
            } catch (e: UnifiedBookError) {
                unmatched += LegacyUnmatchedItem(candidate.recordId, candidate.sourceKind, mapErrorReason(e.code))
            }
        }

        // Re-scan receipts after catalog/local-binding books have been migrated so any owner receipts
        // matching newly migrated owner books can be aligned in the same pass.
        val afterBooksScan = withContext(Dispatchers.IO) { scanReceiptsOnlyReadOnly(devices) }
        val finalUnmatched = buildList {
            // Keep catalog/local-binding unmatched items (including any quota/disk failures during migrate).
            addAll(unmatched.filter { it.sourceKind == "catalog" || it.sourceKind == "local_binding" })
            addAll(afterBooksScan.unmatched)
        }

        var alignedReceipts = 0
        var alignedBlocks = 0
        withContext(Dispatchers.IO) {
            for (receipt in afterBooksScan.receiptCandidates) {
                val insertedBlocks = unifiedBooks.upsertLegacyReceiptAlignments(
                    scope = receipt.targetScope,
                    legacyReceiptId = receipt.recordId,
                    sourceKind = receipt.sourceKind,
                    shelfBookId = receipt.shelfBookId,
                    bookSha256 = receipt.bookSha256,
                    rows = receipt.rows,
                )
                if (insertedBlocks > 0) {
                    alignedReceipts++
                    alignedBlocks += insertedBlocks
                } else {
                    deduplicatedSkipped++
                }
            }
        }

        val verification = verify()
        UnifiedBookMigrationReport(
            inventoryBefore = UnifiedBookInventoryReport(beforeScan.counts, beforeScan.unmatched),
            migratedBooks = migratedBooks,
            alignedReceipts = alignedReceipts,
            alignedBlocks = alignedBlocks,
            deduplicatedSkipped = deduplicatedSkipped,
            unmatched = finalUnmatched,
            verification = verification,
        )
    }

    /** Strictly read-only per-account integrity verification across `unified-books/`. */
    internal suspend fun verify(): UnifiedBookVerificationReport = withContext(Dispatchers.IO) {
        val registryFile = unifiedBooks.registryFilePath
        val accountsRoot = unifiedBooks.accountsDirPath
        if (!Files.isRegularFile(registryFile)) {
            return@withContext UnifiedBookVerificationReport(
                globalSharedBlobs = 0,
                globalSharedRefs = 0,
                globalRefCountMismatches = 0,
                globalDanglingRefs = 0,
                accounts = emptyList(),
            )
        }

        val activeBlobs = linkedMapOf<String, Int>()
        val refsByScope = linkedMapOf<String, MutableMap<String, String>>() // scopeKey -> (sha256 -> shelfBookId)
        val actualRefCountBySha = hashMapOf<String, Int>()
        UnifiedBookPipeline.openReadOnly(registryFile).use { db ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT sha256, ref_count FROM shared_blobs WHERE state='ACTIVE' ORDER BY sha256").use { rs ->
                    while (rs.next()) activeBlobs[rs.getString(1)] = rs.getInt(2)
                }
                sql.executeQuery("SELECT sha256, scope_key, shelf_book_id FROM shared_blob_refs ORDER BY scope_key, sha256").use { rs ->
                    while (rs.next()) {
                        val sha = rs.getString(1)
                        val scopeKey = rs.getString(2)
                        val shelfId = rs.getString(3)
                        actualRefCountBySha[sha] = (actualRefCountBySha[sha] ?: 0) + 1
                        refsByScope.computeIfAbsent(scopeKey) { linkedMapOf() }[sha] = shelfId
                    }
                }
            }
        }

        var globalRefCountMismatches = 0
        for ((sha, recordedCount) in activeBlobs) {
            if ((actualRefCountBySha[sha] ?: 0) != recordedCount) {
                globalRefCountMismatches++
            }
        }
        for (sha in actualRefCountBySha.keys) {
            if (sha !in activeBlobs) globalRefCountMismatches++
        }

        // Cache on-disk SHA-256 verification per blob so multi-account shared blobs are hashed once per verify run.
        val blobIntegrityCache = hashMapOf<String, String>() // sha -> "ok" | "missing" | "corrupted"
        fun checkBlob(sha: String): String = blobIntegrityCache.computeIfAbsent(sha) {
            val path = unifiedBooks.blobPathForVerify(sha)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "missing"
            } else {
                val actual = runCatching { UnifiedBookPipeline.sha256File(path) }.getOrNull()
                if (actual == sha) "ok" else "corrupted"
            }
        }

        val scopeDirs = if (Files.isDirectory(accountsRoot)) {
            Files.list(accountsRoot).use { stream ->
                stream
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        } else emptyList()

        val allScopeKeys = (scopeDirs + refsByScope.keys).toSortedSet()
        val accounts = mutableListOf<UnifiedBookAccountVerification>()
        var globalDanglingRefs = 0

        for (scopeKey in allScopeKeys) {
            val shelfFile = accountsRoot.resolve(scopeKey).resolve("shelf.sqlite")
            val expectedRefs = refsByScope[scopeKey].orEmpty()
            if (!Files.isRegularFile(shelfFile, LinkOption.NOFOLLOW_LINKS)) {
                if (expectedRefs.isNotEmpty()) {
                    globalDanglingRefs += expectedRefs.size
                }
                continue
            }

            val shelfBooks = linkedMapOf<String, String>() // shelfBookId -> sha256
            var notes = 0
            var qa = 0
            var overrides = 0
            var legacyMappings = 0
            var alignedReceipts = 0
            var orphans = 0

            UnifiedBookPipeline.openReadOnly(shelfFile).use { db ->
                fun tableExists(name: String): Boolean = db.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                ).use { sql ->
                    sql.setString(1, name)
                    sql.executeQuery().use { it.next() }
                }

                if (tableExists("shelf_books")) {
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT shelf_book_id, sha256 FROM shelf_books").use { rs ->
                            while (rs.next()) shelfBooks[rs.getString(1)] = rs.getString(2)
                        }
                    }
                }
                if (tableExists("shelf_book_notes")) {
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT shelf_book_id FROM shelf_book_notes").use { rs ->
                            while (rs.next()) {
                                notes++
                                if (rs.getString(1) !in shelfBooks) orphans++
                            }
                        }
                    }
                }
                if (tableExists("shelf_book_qa")) {
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT shelf_book_id FROM shelf_book_qa").use { rs ->
                            while (rs.next()) {
                                qa++
                                if (rs.getString(1) !in shelfBooks) orphans++
                            }
                        }
                    }
                }
                if (tableExists("shelf_book_overrides")) {
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT shelf_book_id FROM shelf_book_overrides").use { rs ->
                            while (rs.next()) {
                                overrides++
                                if (rs.getString(1) !in shelfBooks) orphans++
                            }
                        }
                    }
                }
                if (tableExists("legacy_book_mappings")) {
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT shelf_book_id, sha256 FROM legacy_book_mappings").use { rs ->
                            while (rs.next()) {
                                legacyMappings++
                                val mappedId = rs.getString(1)
                                val mappedSha = rs.getString(2)
                                if (shelfBooks[mappedId] != mappedSha) orphans++
                            }
                        }
                    }
                }
                if (tableExists("legacy_receipt_alignments")) {
                    db.createStatement().use { sql ->
                        sql.executeQuery("SELECT DISTINCT legacy_receipt_id, shelf_book_id, book_sha256 FROM legacy_receipt_alignments").use { rs ->
                            while (rs.next()) {
                                alignedReceipts++
                                val mappedId = rs.getString(2)
                                val mappedSha = rs.getString(3)
                                if (shelfBooks[mappedId] != mappedSha) orphans++
                            }
                        }
                    }
                }
            }

            var missingBlobs = 0
            var corruptedBlobs = 0
            var refMismatches = 0
            for ((shelfId, sha) in shelfBooks) {
                when (checkBlob(sha)) {
                    "missing" -> missingBlobs++
                    "corrupted" -> corruptedBlobs++
                }
                if (sha !in activeBlobs || expectedRefs[sha] != shelfId) {
                    refMismatches++
                }
            }
            for ((sha, refShelfId) in expectedRefs) {
                if (shelfBooks[refShelfId] != sha) {
                    globalDanglingRefs++
                    refMismatches++
                }
            }

            accounts += UnifiedBookAccountVerification(
                accountRef = scopeKey,
                shelfBooks = shelfBooks.size,
                notes = notes,
                qa = qa,
                overrides = overrides,
                legacyMappings = legacyMappings,
                alignedReceipts = alignedReceipts,
                sharedRefs = expectedRefs.size,
                missingBlobs = missingBlobs,
                corruptedBlobs = corruptedBlobs,
                orphanAccountRecords = orphans,
                refMismatches = refMismatches,
            )
        }

        UnifiedBookVerificationReport(
            globalSharedBlobs = activeBlobs.size,
            globalSharedRefs = actualRefCountBySha.values.sum(),
            globalRefCountMismatches = globalRefCountMismatches,
            globalDanglingRefs = globalDanglingRefs,
            accounts = accounts,
        )
    }

    private fun scanReadOnly(devices: List<PairedDevice>): ScanResult {
        val unmatched = mutableListOf<LegacyUnmatchedItem>()
        val catalogCandidates = mutableListOf<VerifiedCatalogCandidate>()

        var catalogTotal = 0
        var catalogReady = 0
        var catalogAlreadyMigrated = 0
        var catalogUnmatched = 0
        var localBindingsTotal = 0
        var localBindingsReady = 0
        var localBindingsAlreadyMigrated = 0
        var localBindingsUnmatched = 0

        if (readerRoot != null && Files.isDirectory(readerRoot)) {
            val realRoot = readerRoot.toRealPath()
            val catalogFile = realRoot.resolve("catalog.json")
            if (Files.isRegularFile(catalogFile)) {
                val parsed = runCatching {
                    require(Files.size(catalogFile) <= 1_048_576)
                    val raw = LedgerJson.parseToJsonElement(Files.readString(catalogFile)).jsonObject
                    require(raw.num("schema") == 1L)
                    raw.getValue("documents").jsonArray.map { it.jsonObject }
                }
                if (parsed.isFailure) {
                    catalogTotal++
                    catalogUnmatched++
                    unmatched += LegacyUnmatchedItem("cat:manifest", "catalog", "invalid_metadata")
                } else {
                    val seenCatalogKeys = hashMapOf<String, Int>()
                    for (doc in parsed.getOrThrow()) {
                        catalogTotal++
                        val docId = doc["id"]?.jsonPrimitive?.content.orEmpty()
                        val course = doc["course"]?.jsonPrimitive?.content.orEmpty()
                        val sha = doc["sha256"]?.jsonPrimitive?.content.orEmpty().lowercase()
                        val baseKey = "$course:$docId:$sha"
                        val occ = seenCatalogKeys.merge(baseKey, 1, Int::plus)!!
                        val recordId = opaqueId("cat", if (occ == 1) baseKey else "$baseKey#$occ")
                        when (val check = verifyCatalogDoc(realRoot, doc, recordId)) {
                            is CandidateCheck.Valid -> {
                                if (check.candidate.alreadyMigrated) catalogAlreadyMigrated++ else catalogReady++
                                catalogCandidates += check.candidate
                            }
                            is CandidateCheck.Invalid -> {
                                catalogUnmatched++
                                unmatched += LegacyUnmatchedItem(recordId, "catalog", check.reason)
                            }
                        }
                    }
                }
            }

            val localBooksFile = realRoot.resolve("local-books.json")
            if (Files.isRegularFile(localBooksFile)) {
                val parsed = runCatching {
                    require(Files.size(localBooksFile) <= 1_048_576)
                    val raw = LedgerJson.parseToJsonElement(Files.readString(localBooksFile)).jsonObject
                    require(raw["schema"]?.jsonPrimitive?.intOrNull == 1)
                    raw.getValue("books").jsonArray.map { it.jsonObject }
                }
                if (parsed.isFailure) {
                    localBindingsTotal++
                    localBindingsUnmatched++
                    unmatched += LegacyUnmatchedItem("lb:manifest", "local_binding", "invalid_metadata")
                } else {
                    val seenBindingKeys = hashMapOf<String, Int>()
                    for (entry in parsed.getOrThrow()) {
                        localBindingsTotal++
                        val docId = entry["id"]?.jsonPrimitive?.content.orEmpty()
                        val course = entry["course"]?.jsonPrimitive?.content.orEmpty()
                        val sha = entry["sha256"]?.jsonPrimitive?.content.orEmpty().lowercase()
                        val baseKey = "$course:$docId:$sha"
                        val occ = seenBindingKeys.merge(baseKey, 1, Int::plus)!!
                        val recordId = opaqueId("lb", if (occ == 1) baseKey else "$baseKey#$occ")
                        when (val check = verifyLocalBinding(realRoot, entry, recordId)) {
                            is CandidateCheck.Valid -> {
                                if (check.candidate.alreadyMigrated) localBindingsAlreadyMigrated++ else localBindingsReady++
                                catalogCandidates += check.candidate
                            }
                            is CandidateCheck.Invalid -> {
                                localBindingsUnmatched++
                                unmatched += LegacyUnmatchedItem(recordId, "local_binding", check.reason)
                            }
                        }
                    }
                }
            }
        }

        val receiptsScan = scanReceiptsOnlyReadOnly(devices)
        unmatched += receiptsScan.unmatched

        return ScanResult(
            counts = receiptsScan.counts.copy(
                catalogTotal = catalogTotal,
                catalogReady = catalogReady,
                catalogAlreadyMigrated = catalogAlreadyMigrated,
                catalogUnmatched = catalogUnmatched,
                localBindingsTotal = localBindingsTotal,
                localBindingsReady = localBindingsReady,
                localBindingsAlreadyMigrated = localBindingsAlreadyMigrated,
                localBindingsUnmatched = localBindingsUnmatched,
            ),
            unmatched = unmatched,
            catalogCandidates = catalogCandidates,
            receiptCandidates = receiptsScan.receiptCandidates,
        )
    }

    private fun scanReceiptsOnlyReadOnly(devices: List<PairedDevice>): ScanResult {
        val devicesById = devices.associateBy { it.id }
        val unmatched = mutableListOf<LegacyUnmatchedItem>()
        val receiptCandidates = mutableListOf<VerifiedReceiptCandidate>()

        var ownerTotal = 0
        var ownerAlignable = 0
        var ownerAlreadyAligned = 0
        var ownerAwaiting = 0
        var ownerUnmatched = 0
        var friendTotal = 0
        var friendAlignable = 0
        var friendAlreadyAligned = 0
        var friendAwaiting = 0
        var friendUnmatched = 0
        var friendExplanations = 0

        // 1. Owner personal-imports/sources.sqlite
        val ownerSources = dataDir.resolve("personal-imports").resolve("sources.sqlite")
        if (Files.isRegularFile(ownerSources, LinkOption.NOFOLLOW_LINKS)) {
            val grouped = readImportBlocksReadOnly(ownerSources)
            if (grouped == null) {
                ownerTotal++
                ownerUnmatched++
                unmatched += LegacyUnmatchedItem("pi:store", "owner_import_receipt", "invalid_metadata")
            } else {
                for ((key, rawRows) in grouped) {
                    val (deviceId, bookSha) = key
                    ownerTotal++
                    val recordId = opaqueId("pi", "$deviceId:$bookSha")
                    if (!bookSha.matches(UnifiedBookPipeline.SHA256_PATTERN)) {
                        ownerUnmatched++
                        unmatched += LegacyUnmatchedItem(recordId, "owner_import_receipt", "invalid_metadata")
                        continue
                    }
                    val dev = devicesById[deviceId]
                    val ownershipError = when {
                        dev == null -> "owner_unverified"
                        dev.friendName != null -> "scope_mismatch"
                        dev.revokedAt != null -> "revoked_device"
                        else -> null
                    }
                    if (ownershipError != null) {
                        ownerUnmatched++
                        unmatched += LegacyUnmatchedItem(recordId, "owner_import_receipt", ownershipError)
                        continue
                    }
                    val validatedRows = validateBlockRows(rawRows)
                    if (validatedRows == null) {
                        ownerUnmatched++
                        unmatched += LegacyUnmatchedItem(recordId, "owner_import_receipt", "invalid_snapshot")
                        continue
                    }
                    val scope = UnifiedBookScope.fromCall(checkNotNull(dev).id, null)
                    val shelfBookId = unifiedBooks.existingShelfBookIdByShaReadOnly(scope, bookSha)
                    if (shelfBookId == null) {
                        ownerAwaiting++
                        unmatched += LegacyUnmatchedItem(recordId, "owner_import_receipt", "awaiting_full_upload")
                        continue
                    }
                    val existingKeys = unifiedBooks.alignedReceiptBlockKeysReadOnly(scope, recordId, shelfBookId)
                    val alreadyAligned = validatedRows.all { Triple(it.page, it.startUtf16, it.endUtf16) in existingKeys }
                    if (alreadyAligned) ownerAlreadyAligned++ else ownerAlignable++
                    receiptCandidates += VerifiedReceiptCandidate(
                        recordId = recordId,
                        sourceKind = "owner_import_receipt",
                        targetScope = scope,
                        bookSha256 = bookSha,
                        shelfBookId = shelfBookId,
                        rows = validatedRows,
                        alreadyAligned = alreadyAligned,
                    )
                }
            }
        }

        // 2. Friend directories under dataDir/friends/<friendId>/
        val friendsDir = dataDir.resolve("friends")
        if (Files.isDirectory(friendsDir, LinkOption.NOFOLLOW_LINKS)) {
            val friendDirs = Files.list(friendsDir).use { stream ->
                stream
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { it.fileName.toString() }
                    .filter { it.matches(UnifiedBookPipeline.SHA256_PATTERN) }
                    .sorted()
                    .toList()
            }
            for (dirFriendId in friendDirs) {
                val dir = friendsDir.resolve(dirFriendId)
                val explDb = dir.resolve("explanations.sqlite")
                if (Files.isRegularFile(explDb, LinkOption.NOFOLLOW_LINKS)) {
                    friendExplanations += countRowsReadOnly(explDb, "explanations")
                }
                val sources = dir.resolve("imports").resolve("sources.sqlite")
                if (!Files.isRegularFile(sources, LinkOption.NOFOLLOW_LINKS)) continue
                val grouped = readImportBlocksReadOnly(sources)
                if (grouped == null) {
                    friendTotal++
                    friendUnmatched++
                    unmatched += LegacyUnmatchedItem("fi:${dirFriendId.take(16)}", "friend_import_receipt", "invalid_metadata")
                    continue
                }
                for ((key, rawRows) in grouped) {
                    val (deviceId, bookSha) = key
                    friendTotal++
                    val recordId = opaqueId("fi", "$dirFriendId:$deviceId:$bookSha")
                    if (!bookSha.matches(UnifiedBookPipeline.SHA256_PATTERN)) {
                        friendUnmatched++
                        unmatched += LegacyUnmatchedItem(recordId, "friend_import_receipt", "invalid_metadata")
                        continue
                    }
                    val dev = devicesById[deviceId]
                    val ownershipError = when {
                        dev == null -> "owner_unverified"
                        dev.friendName == null || friendId(dev.friendName!!) != dirFriendId -> "scope_mismatch"
                        dev.revokedAt != null -> "revoked_device"
                        else -> null
                    }
                    if (ownershipError != null) {
                        friendUnmatched++
                        unmatched += LegacyUnmatchedItem(recordId, "friend_import_receipt", ownershipError)
                        continue
                    }
                    val validatedRows = validateBlockRows(rawRows)
                    if (validatedRows == null) {
                        friendUnmatched++
                        unmatched += LegacyUnmatchedItem(recordId, "friend_import_receipt", "invalid_snapshot")
                        continue
                    }
                    val scope = UnifiedBookScope.fromCall(checkNotNull(dev).id, dev.friendName)
                    val shelfBookId = unifiedBooks.existingShelfBookIdByShaReadOnly(scope, bookSha)
                    if (shelfBookId == null) {
                        friendAwaiting++
                        unmatched += LegacyUnmatchedItem(recordId, "friend_import_receipt", "awaiting_full_upload")
                        continue
                    }
                    val existingKeys = unifiedBooks.alignedReceiptBlockKeysReadOnly(scope, recordId, shelfBookId)
                    val alreadyAligned = validatedRows.all { Triple(it.page, it.startUtf16, it.endUtf16) in existingKeys }
                    if (alreadyAligned) friendAlreadyAligned++ else friendAlignable++
                    receiptCandidates += VerifiedReceiptCandidate(
                        recordId = recordId,
                        sourceKind = "friend_import_receipt",
                        targetScope = scope,
                        bookSha256 = bookSha,
                        shelfBookId = shelfBookId,
                        rows = validatedRows,
                        alreadyAligned = alreadyAligned,
                    )
                }
            }
        }

        return ScanResult(
            counts = UnifiedBookInventoryCounts(
                ownerReceiptsTotal = ownerTotal,
                ownerReceiptsAlignable = ownerAlignable,
                ownerReceiptsAlreadyAligned = ownerAlreadyAligned,
                ownerReceiptsAwaitingFullUpload = ownerAwaiting,
                ownerReceiptsUnmatched = ownerUnmatched,
                friendReceiptsTotal = friendTotal,
                friendReceiptsAlignable = friendAlignable,
                friendReceiptsAlreadyAligned = friendAlreadyAligned,
                friendReceiptsAwaitingFullUpload = friendAwaiting,
                friendReceiptsUnmatched = friendUnmatched,
                legacyFriendExplanationsTotal = friendExplanations,
            ),
            unmatched = unmatched,
            catalogCandidates = emptyList(),
            receiptCandidates = receiptCandidates,
        )
    }

    private sealed interface CandidateCheck {
        data class Valid(val candidate: VerifiedCatalogCandidate) : CandidateCheck
        data class Invalid(val reason: String) : CandidateCheck
    }

    private fun verifyCatalogDoc(realRoot: Path, doc: JsonObject, recordId: String): CandidateCheck {
        val docId = doc["id"]?.jsonPrimitive?.content.orEmpty()
        val course = doc["course"]?.jsonPrimitive?.content.orEmpty()
        val title = doc["title"]?.jsonPrimitive?.content.orEmpty().trim()
        val relFile = doc["file"]?.jsonPrimitive?.content.orEmpty()
        val declaredSha = doc["sha256"]?.jsonPrimitive?.content.orEmpty().lowercase()
        val pages = doc["pages"]?.jsonPrimitive?.intOrNull
        val offset = doc["printedPageOffset"]?.jsonPrimitive?.intOrNull
        val explainSourceId = doc["explainSourceId"]?.jsonPrimitive?.takeIf { it.isString }?.content
        if (!Regex("[a-zA-Z0-9_-]{1,80}").matches(docId) ||
            course.length !in 1..128 ||
            title.length !in 1..300 || title.any(Char::isISOControl) || !tutorSourceAllowed(title) ||
            !declaredSha.matches(UnifiedBookPipeline.SHA256_PATTERN) ||
            pages == null || pages !in 1..100_000 ||
            (offset != null && offset !in 0 until pages) ||
            (explainSourceId != null && !Regex("[A-Za-z0-9._:-]{1,80}").matches(explainSourceId))
        ) {
            return CandidateCheck.Invalid("invalid_metadata")
        }
        val resolved = resolveContainedRegularFile(realRoot, relFile)
            ?: return CandidateCheck.Invalid(if (relFile.isBlank() || Path.of(relFile).isAbsolute) "invalid_path" else "missing_file")
        val format = inferFormat(resolved) ?: return CandidateCheck.Invalid("unsupported_format")
        val size = runCatching { Files.size(resolved) }.getOrNull() ?: return CandidateCheck.Invalid("missing_file")
        if (size <= 0L) return CandidateCheck.Invalid("invalid_metadata")
        if (size > unifiedBooks.config.maxFileBytes) return CandidateCheck.Invalid("file_too_large")
        val actualSha = runCatching { UnifiedBookPipeline.sha256File(resolved) }.getOrNull()
            ?: return CandidateCheck.Invalid("missing_file")
        if (actualSha != declaredSha) return CandidateCheck.Invalid("sha256_mismatch")

        val alreadyMigrated = unifiedBooks.hasLegacyBookMappingReadOnly(ownerScope, recordId) &&
            unifiedBooks.existingShelfBookIdByShaReadOnly(ownerScope, declaredSha) != null
        return CandidateCheck.Valid(
            VerifiedCatalogCandidate(
                recordId = recordId,
                sourceKind = "catalog",
                legacyDocId = docId,
                courseId = course,
                title = title,
                file = resolved,
                format = format,
                sha256 = declaredSha,
                pages = pages,
                printedPageOffset = offset,
                explainSourceId = explainSourceId,
                pagesSha256 = null,
                alreadyMigrated = alreadyMigrated,
            ),
        )
    }

    private fun verifyLocalBinding(realRoot: Path, entry: JsonObject, recordId: String): CandidateCheck {
        val docId = entry["id"]?.jsonPrimitive?.content.orEmpty()
        val course = entry["course"]?.jsonPrimitive?.content.orEmpty()
        val title = entry["title"]?.jsonPrimitive?.content.orEmpty().trim()
        val relFile = entry["file"]?.jsonPrimitive?.content.orEmpty()
        val relPages = entry["pagesFile"]?.jsonPrimitive?.content.orEmpty()
        val declaredSha = entry["sha256"]?.jsonPrimitive?.content.orEmpty().lowercase()
        val pagesSha = entry["pagesSha256"]?.jsonPrimitive?.content.orEmpty().lowercase()
        val explainSourceId = entry["explainSourceId"]?.jsonPrimitive?.content.orEmpty()
        if (!Regex("[A-Za-z0-9_-]{1,80}").matches(docId) ||
            course.length !in 1..128 ||
            title.length !in 1..300 || title.any(Char::isISOControl) || !tutorSourceAllowed(title) ||
            !declaredSha.matches(UnifiedBookPipeline.SHA256_PATTERN) ||
            !pagesSha.matches(UnifiedBookPipeline.SHA256_PATTERN) ||
            !Regex("[A-Za-z0-9._:-]{1,80}").matches(explainSourceId)
        ) {
            return CandidateCheck.Invalid("invalid_metadata")
        }
        val file = resolveContainedRegularFile(realRoot, relFile)
            ?: return CandidateCheck.Invalid(if (relFile.isBlank() || Path.of(relFile).isAbsolute) "invalid_path" else "missing_file")
        val pagesFile = resolveContainedRegularFile(realRoot, relPages)
            ?: return CandidateCheck.Invalid("invalid_snapshot")
        val format = inferFormat(file) ?: return CandidateCheck.Invalid("unsupported_format")
        val fileSize = runCatching { Files.size(file) }.getOrNull() ?: return CandidateCheck.Invalid("missing_file")
        if (fileSize <= 0L) return CandidateCheck.Invalid("invalid_metadata")
        if (fileSize > unifiedBooks.config.maxFileBytes) return CandidateCheck.Invalid("file_too_large")
        val actualSha = runCatching { UnifiedBookPipeline.sha256File(file) }.getOrNull()
            ?: return CandidateCheck.Invalid("missing_file")
        if (actualSha != declaredSha) return CandidateCheck.Invalid("sha256_mismatch")

        val maxPage = runCatching {
            require(Files.size(pagesFile) <= 33_554_432)
            val bytes = Files.readAllBytes(pagesFile)
            require(ReaderLibrary.digest(bytes) == pagesSha)
            val saved = LedgerJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(saved["schema"]?.jsonPrimitive?.intOrNull == 1 && saved.str("book") == declaredSha)
            val numbers = saved.getValue("pages").jsonArray.map { it.jsonObject.getValue("number").jsonPrimitive.intOrNull!! }
            require(numbers.isNotEmpty() && numbers.all { it in 1..100_000 } && numbers.distinct().size == numbers.size)
            numbers.max()
        }.getOrNull() ?: return CandidateCheck.Invalid("invalid_snapshot")

        val alreadyMigrated = unifiedBooks.hasLegacyBookMappingReadOnly(ownerScope, recordId) &&
            unifiedBooks.existingShelfBookIdByShaReadOnly(ownerScope, declaredSha) != null
        return CandidateCheck.Valid(
            VerifiedCatalogCandidate(
                recordId = recordId,
                sourceKind = "local_binding",
                legacyDocId = docId,
                courseId = course,
                title = title,
                file = file,
                format = format,
                sha256 = declaredSha,
                pages = maxPage,
                printedPageOffset = null,
                explainSourceId = explainSourceId,
                pagesSha256 = pagesSha,
                alreadyMigrated = alreadyMigrated,
            ),
        )
    }

    private data class RawImportBlock(
        val page: Int,
        val start: Int,
        val end: Int,
        val textLength: Int,
        val snapshot: String,
    )

    private fun readImportBlocksReadOnly(sqliteFile: Path): Map<Pair<String, String>, List<RawImportBlock>>? = runCatching {
        UnifiedBookPipeline.openReadOnly(sqliteFile).use { db ->
            val hasBlocks = db.createStatement().use { s ->
                s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='blocks'").use { it.next() }
            }
            if (!hasBlocks) return@use emptyMap()
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT device, book, page, start, end, text, snapshot FROM blocks ORDER BY device, book, page, start, end").use { rs ->
                    val map = linkedMapOf<Pair<String, String>, MutableList<RawImportBlock>>()
                    while (rs.next()) {
                        val key = rs.getString(1) to rs.getString(2)
                        map.computeIfAbsent(key) { mutableListOf() }.add(
                            RawImportBlock(
                                page = rs.getInt(3),
                                start = rs.getInt(4),
                                end = rs.getInt(5),
                                textLength = rs.getString(6).length,
                                snapshot = rs.getString(7),
                            ),
                        )
                    }
                    map
                }
            }
        }
    }.getOrNull()

    private fun validateBlockRows(rawRows: List<RawImportBlock>): List<UnifiedBookPipeline.LegacyReceiptAlignmentRow>? {
        if (rawRows.isEmpty()) return null
        val out = ArrayList<UnifiedBookPipeline.LegacyReceiptAlignmentRow>(rawRows.size)
        for (row in rawRows) {
            val snap = runCatching { LedgerJson.parseToJsonElement(row.snapshot).jsonObject }.getOrNull() ?: return null
            val snapSha = snap["sha256"]?.jsonPrimitive?.content.orEmpty()
            val snapLen = snap["length"]?.jsonPrimitive?.intOrNull ?: return null
            val snapOcr = (snap["ocr"] as? JsonPrimitive)?.booleanOrNull ?: return null
            if (!snapSha.matches(UnifiedBookPipeline.SHA256_PATTERN) ||
                snapLen !in 1..2_000_000 ||
                row.page !in 1..100_000 ||
                row.start < 0 ||
                row.end <= row.start ||
                row.end > snapLen ||
                row.textLength != (row.end - row.start)
            ) {
                return null
            }
            out += UnifiedBookPipeline.LegacyReceiptAlignmentRow(
                page = row.page,
                startUtf16 = row.start,
                endUtf16 = row.end,
                snapshotSha256 = snapSha,
                snapshotLength = snapLen,
                snapshotOcr = snapOcr,
            )
        }
        return out
    }

    private fun countRowsReadOnly(sqliteFile: Path, table: String): Int = runCatching {
        UnifiedBookPipeline.openReadOnly(sqliteFile).use { db ->
            val exists = db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { sql ->
                sql.setString(1, table)
                sql.executeQuery().use { it.next() }
            }
            if (!exists) return@use 0
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }.getOrDefault(0)

    private fun resolveContainedRegularFile(realRoot: Path, relativeStr: String): Path? {
        if (relativeStr.isBlank()) return null
        return runCatching {
            val relative = Path.of(relativeStr)
            if (relative.isAbsolute) return@runCatching null
            val candidate = realRoot.resolve(relative).normalize()
            if (!Files.exists(candidate)) return@runCatching null
            val real = candidate.toRealPath()
            if (real.startsWith(realRoot) && Files.isRegularFile(real)) real else null
        }.getOrNull()
    }

    private fun inferFormat(path: Path): String? {
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        val normalized = when (ext) {
            "jpeg" -> "jpg"
            "markdown" -> "md"
            else -> ext
        }
        return normalized.takeIf { it in UnifiedBookPipeline.SUPPORTED_FORMATS }
    }

    private fun mapErrorReason(code: String): String = when (code) {
        "account_quota_exceeded" -> "quota_exceeded"
        "disk_space_insufficient" -> "disk_space_insufficient"
        "file_too_large" -> "file_too_large"
        "unsupported_format" -> "unsupported_format"
        "sha256_mismatch" -> "sha256_mismatch"
        "missing_file" -> "missing_file"
        else -> "invalid_metadata"
    }

    private fun opaqueId(prefix: String, material: String): String =
        "$prefix:" + sha256(material.toByteArray(Charsets.UTF_8)).take(16)
}

/**
 * Private admin-only routes for PR B read-only inventory, idempotent migration, and per-account verification.
 * Never mounted on the public listener and never listed in [friendPath].
 */
fun Application.unifiedBookMigrationRoutes(
    migration: UnifiedBookMigration,
    unifiedBooks: UnifiedBookPipeline,
    adminDevice: String = "desktop",
) {
    routing {
        get("/v1/admin/books/migration/inventory") {
            if (call.attributes[observedDevice] != adminDevice) {
                return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            }
            val report = migration.inventory()
            call.respondText(report.toJson().toString(), ContentType.Application.Json)
        }

        post("/v1/admin/books/migration/run") {
            if (call.attributes[observedDevice] != adminDevice) {
                return@post call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            }
            if (!unifiedBooks.enabled) {
                return@post call.respondText(
                    json("code" to "unified_books_disabled", "error" to "请先开启 COACH_UNIFIED_BOOKS=1 后再执行迁移。").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
            }
            val report = migration.migrate()
            call.respondText(report.toJson().toString(), ContentType.Application.Json)
        }

        get("/v1/admin/books/migration/verify") {
            if (call.attributes[observedDevice] != adminDevice) {
                return@get call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            }
            val report = migration.verify()
            call.respondText(report.toJson().toString(), ContentType.Application.Json)
        }
    }
}
