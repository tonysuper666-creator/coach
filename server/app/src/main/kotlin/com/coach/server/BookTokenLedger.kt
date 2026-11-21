package com.coach.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.math.ceil

/**
 * Route channel used by LearningCoach 2.0 single-book companion & realtime pipeline.
 * - [CODEBUDDY_DEEPSEEK_FLASH]: primary bulk batch channel (CodeBuddy CLI subscription, marginal cash $0).
 * - [HAIKU_BATCH]: overflow bulk batch channel (Anthropic Batch API 50% off Haiku 5.5).
 * - [HAIKU_REALTIME]: realtime interactive channel (`讲清` / `拆句` / `追问`, Haiku 5.5 streaming).
 */
enum class CompanionRouteChannel(val wireName: String, val defaultModel: String) {
    CODEBUDDY_DEEPSEEK_FLASH("codebuddy_deepseek_flash", "deepseek-v4.1-flash"),
    HAIKU_BATCH("haiku_batch", "claude-haiku-5-5"),
    HAIKU_REALTIME("haiku_realtime", "claude-haiku-5-5"),
}

data class TokenLedgerEntry(
    val entryId: String = UUID.randomUUID().toString(),
    val bookId: String,
    val chapterId: String,
    val sectionId: String,
    val task: String,
    val routeChannel: CompanionRouteChannel,
    val model: String = routeChannel.defaultModel,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val costMicrodollars: Long = TokenLedgerPricing.costMicrodollars(routeChannel, inputTokens, outputTokens, cachedTokens),
    val ttftMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val status: String = "complete",
    val createdAt: Long = System.currentTimeMillis(),
)

data class ChannelLedgerBreakdown(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val costMicrodollars: Long,
)

data class BookLedgerSummary(
    val bookId: String,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val totalCostMicrodollars: Long,
    val totalCostUsd: Double,
    val p50ElapsedMs: Long,
    val p95ElapsedMs: Long,
    val p50TtftMs: Long,
    val p95TtftMs: Long,
    val withinBudget: Boolean,
    val budgetCeilingUsd: Double = TokenLedgerPricing.BOOK_BUDGET_CEILING_USD,
    val byChannel: Map<String, ChannelLedgerBreakdown> = emptyMap(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("bookId", bookId)
        put("calls", calls)
        put("inputTokens", inputTokens)
        put("outputTokens", outputTokens)
        put("cachedTokens", cachedTokens)
        put("totalCostMicrodollars", totalCostMicrodollars)
        put("totalCostUsd", totalCostUsd)
        put("p50ElapsedMs", p50ElapsedMs)
        put("p95ElapsedMs", p95ElapsedMs)
        put("p50TtftMs", p50TtftMs)
        put("p95TtftMs", p95TtftMs)
        put("withinBudget", withinBudget)
        put("budgetCeilingUsd", budgetCeilingUsd)
        put("byChannel", buildJsonObject {
            for ((k, v) in byChannel) {
                put(k, buildJsonObject {
                    put("calls", v.calls)
                    put("inputTokens", v.inputTokens)
                    put("outputTokens", v.outputTokens)
                    put("cachedTokens", v.cachedTokens)
                    put("costMicrodollars", v.costMicrodollars)
                })
            }
        })
    }
}

/**
 * Pricing table aligned with `tools/bridge/model_routes.json` and `D-product/cost-model.json`.
 * Returns integer microdollars (1 USD = 1_000_000 microdollars), rounding up conservatively.
 */
object TokenLedgerPricing {
    const val BOOK_BUDGET_CEILING_USD: Double = 0.30
    const val BOOK_BUDGET_CEILING_MICRODOLLARS: Long = 300_000L

    private data class RatesPerMillion(val inputUsd: BigDecimal, val outputUsd: BigDecimal, val cachedUsd: BigDecimal)

    private val rates = mapOf(
        CompanionRouteChannel.CODEBUDDY_DEEPSEEK_FLASH to RatesPerMillion(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
        ),
        CompanionRouteChannel.HAIKU_BATCH to RatesPerMillion(
            BigDecimal("0.05"),
            BigDecimal("0.25"),
            BigDecimal("0.005"),
        ),
        CompanionRouteChannel.HAIKU_REALTIME to RatesPerMillion(
            BigDecimal("0.10"),
            BigDecimal("0.50"),
            BigDecimal("0.01"),
        ),
    )

    fun costMicrodollars(
        channel: CompanionRouteChannel,
        inputTokens: Int,
        outputTokens: Int,
        cachedTokens: Int = 0,
    ): Long {
        val rate = rates.getValue(channel)
        val safeIn = inputTokens.coerceAtLeast(0)
        val safeOut = outputTokens.coerceAtLeast(0)
        val safeCached = cachedTokens.coerceIn(0, safeIn)
        val billableIn = safeIn - safeCached
        // USD per 1M tokens == microdollars per 1 token directly!
        val micro = BigDecimal(billableIn).multiply(rate.inputUsd)
            .add(BigDecimal(safeCached).multiply(rate.cachedUsd))
            .add(BigDecimal(safeOut).multiply(rate.outputUsd))
        return micro.setScale(0, RoundingMode.CEILING).toLong()
    }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        val words = WORD_RE.findAll(text).count()
        val byWords = ceil(words * 1.33).toInt()
        val byChars = ceil(text.length / 4.0).toInt()
        return maxOf(byWords, byChars, 1)
    }

    private val WORD_RE = Regex("[\\p{L}\\p{N}_'-]+|[\\p{IsHan}]")
}

/**
 * Durable SQLite Token Ledger recording per-section batch and realtime interactive token usage & cost.
 * Stores metadata only (never book text or user answers).
 */
class BookTokenLedger(directory: Path) {
    private val file: Path = privateFiles.file(privateFiles.directory(directory).resolve("book-token-ledger.sqlite"))

    init {
        database().use { db ->
            db.createStatement().use { sql ->
                sql.execute("PRAGMA journal_mode=WAL")
                sql.execute("PRAGMA busy_timeout=5000")
                sql.execute(
                    """CREATE TABLE IF NOT EXISTS book_token_ledger(
                        entry_id TEXT PRIMARY KEY,
                        book_id TEXT NOT NULL,
                        chapter_id TEXT NOT NULL,
                        section_id TEXT NOT NULL,
                        task TEXT NOT NULL,
                        route_channel TEXT NOT NULL,
                        model TEXT NOT NULL,
                        input_tokens INTEGER NOT NULL,
                        output_tokens INTEGER NOT NULL,
                        cached_tokens INTEGER NOT NULL DEFAULT 0,
                        cost_microdollars INTEGER NOT NULL,
                        ttft_ms INTEGER NOT NULL DEFAULT 0,
                        elapsed_ms INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""".trimIndent(),
                )
                sql.execute("CREATE INDEX IF NOT EXISTS idx_book_token_ledger_book ON book_token_ledger(book_id, created_at)")
                sql.execute("CREATE INDEX IF NOT EXISTS idx_book_token_ledger_chapter ON book_token_ledger(book_id, chapter_id)")
            }
        }
    }

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    @Synchronized
    fun record(entry: TokenLedgerEntry) {
        database().use { db ->
            db.prepareStatement(
                """INSERT OR REPLACE INTO book_token_ledger(
                    entry_id, book_id, chapter_id, section_id, task, route_channel, model,
                    input_tokens, output_tokens, cached_tokens, cost_microdollars, ttft_ms, elapsed_ms, status, created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            ).use { s ->
                s.setString(1, entry.entryId)
                s.setString(2, entry.bookId)
                s.setString(3, entry.chapterId)
                s.setString(4, entry.sectionId)
                s.setString(5, entry.task)
                s.setString(6, entry.routeChannel.name)
                s.setString(7, entry.model)
                s.setInt(8, entry.inputTokens)
                s.setInt(9, entry.outputTokens)
                s.setInt(10, entry.cachedTokens)
                s.setLong(11, entry.costMicrodollars)
                s.setLong(12, entry.ttftMs)
                s.setLong(13, entry.elapsedMs)
                s.setString(14, entry.status)
                s.setLong(15, entry.createdAt)
                s.executeUpdate()
            }
        }
    }

    @Synchronized
    fun entriesForBook(bookId: String): List<TokenLedgerEntry> = database().use { db ->
        db.prepareStatement(
            "SELECT * FROM book_token_ledger WHERE book_id=? ORDER BY created_at ASC, rowid ASC",
        ).use { s ->
            s.setString(1, bookId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        add(
                            TokenLedgerEntry(
                                entryId = r.getString("entry_id"),
                                bookId = r.getString("book_id"),
                                chapterId = r.getString("chapter_id"),
                                sectionId = r.getString("section_id"),
                                task = r.getString("task"),
                                routeChannel = CompanionRouteChannel.valueOf(r.getString("route_channel")),
                                model = r.getString("model"),
                                inputTokens = r.getInt("input_tokens"),
                                outputTokens = r.getInt("output_tokens"),
                                cachedTokens = r.getInt("cached_tokens"),
                                costMicrodollars = r.getLong("cost_microdollars"),
                                ttftMs = r.getLong("ttft_ms"),
                                elapsedMs = r.getLong("elapsed_ms"),
                                status = r.getString("status"),
                                createdAt = r.getLong("created_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    fun bookSummary(bookId: String): BookLedgerSummary {
        val entries = entriesForBook(bookId)
        val totalIn = entries.sumOf { it.inputTokens.toLong() }
        val totalOut = entries.sumOf { it.outputTokens.toLong() }
        val totalCached = entries.sumOf { it.cachedTokens.toLong() }
        val totalMicro = entries.sumOf { it.costMicrodollars }
        val totalUsd = totalMicro / 1_000_000.0
        val elapsedSorted = entries.filter { it.elapsedMs > 0 }.map { it.elapsedMs }.sorted()
        val ttftSorted = entries.filter { it.ttftMs > 0 }.map { it.ttftMs }.sorted()
        val byChannel = entries.groupBy { it.routeChannel.wireName }.mapValues { (_, list) ->
            ChannelLedgerBreakdown(
                calls = list.size,
                inputTokens = list.sumOf { it.inputTokens.toLong() },
                outputTokens = list.sumOf { it.outputTokens.toLong() },
                cachedTokens = list.sumOf { it.cachedTokens.toLong() },
                costMicrodollars = list.sumOf { it.costMicrodollars },
            )
        }
        return BookLedgerSummary(
            bookId = bookId,
            calls = entries.size,
            inputTokens = totalIn,
            outputTokens = totalOut,
            cachedTokens = totalCached,
            totalCostMicrodollars = totalMicro,
            totalCostUsd = totalUsd,
            p50ElapsedMs = percentile(elapsedSorted, 0.50),
            p95ElapsedMs = percentile(elapsedSorted, 0.95),
            p50TtftMs = percentile(ttftSorted, 0.50),
            p95TtftMs = percentile(ttftSorted, 0.95),
            withinBudget = totalMicro <= TokenLedgerPricing.BOOK_BUDGET_CEILING_MICRODOLLARS,
            byChannel = byChannel,
        )
    }

    companion object {
        internal fun percentile(sorted: List<Long>, q: Double): Long {
            if (sorted.isEmpty()) return 0L
            val idx = (ceil(sorted.size * q).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }
    }
}
