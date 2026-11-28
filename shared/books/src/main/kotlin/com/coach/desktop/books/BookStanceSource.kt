package com.coach.desktop.books

import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * 书的回答立场来源接口（TON-1976 §7 / TON-1977 #1）：
 * 只有「已判定（立场 + 两个限制标记，一起给）」和「未判定」两种结果。
 * 「未判定」时走旧路径，绝不兜底成 `knowledge`，确保上线边界不出现「有立场、没限制」的书。
 * 阶段 2（TON-1979）将替换默认实现为自动判类型 + 读者锁定，同时保留测试文件实现。
 */
internal sealed interface BookStanceResolution {
    data class Determined(
        val defaultStance: BookStance,
        val secondaryStance: BookStance? = null,
        val guards: Set<BookStanceGuard> = emptySet(),
        val source: String = "forced",
        val reason: String = "",
    ) : BookStanceResolution

    data object Undetermined : BookStanceResolution
}

internal interface BookStanceSource {
    fun resolve(book: Textbook): BookStanceResolution
}

/**
 * 阶段 1 测试实现：从应用数据目录读取 `book-stance-forced.json`（按书的内容哈希 `book.id` 索引）。
 * - 文件不进安装包；文件不存在或书不在文件里时返回 [BookStanceResolution.Undetermined]。
 * - 文件存在且包含该书哈希时返回 [BookStanceResolution.Determined]。
 */
internal class ForcedFileBookStanceSource(
    private val directory: Path,
) : BookStanceSource {
    companion object {
        const val FILE_NAME = "book-stance-forced.json"

        fun resolveConfigFile(directory: Path): Path? {
            val direct = directory.resolve(FILE_NAME)
            if (Files.isRegularFile(direct)) return direct
            if (directory.fileName?.toString() == "books") {
                val parent = directory.parent?.resolve(FILE_NAME)
                if (parent != null && Files.isRegularFile(parent)) return parent
            }
            return null
        }

        internal fun parseEntries(rawJson: String): Map<String, BookStanceResolution.Determined> {
            val root = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyMap()
            val out = LinkedHashMap<String, BookStanceResolution.Determined>()
            for ((rawKey, el) in root) {
                val key = rawKey.trim().lowercase()
                val obj = el as? JsonObject ?: continue
                val defaultRaw = (obj["defaultStance"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["stance"] as? JsonPrimitive)?.contentOrNull
                val defaultStance = BookStance.fromWire(defaultRaw) ?: continue
                val secondaryStance = BookStance.fromWire((obj["secondaryStance"] as? JsonPrimitive)?.contentOrNull)
                    ?.takeIf { it != defaultStance }
                val guards = (obj["guards"] as? JsonArray).orEmpty().mapNotNull { g ->
                    BookStanceGuard.fromWire((g as? JsonPrimitive)?.contentOrNull)
                }.toSet()
                val reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                out[key] = BookStanceResolution.Determined(
                    defaultStance = defaultStance,
                    secondaryStance = secondaryStance,
                    guards = guards,
                    source = "forced",
                    reason = reason,
                )
            }
            return out
        }
    }

    @Volatile private var cachedPath: Path? = null
    @Volatile private var cachedStamp: Pair<Long, Long>? = null
    @Volatile private var cachedEntries: Map<String, BookStanceResolution.Determined> = emptyMap()

    override fun resolve(book: Textbook): BookStanceResolution = resolveByBookId(book.id)

    fun resolveByBookId(bookId: String): BookStanceResolution {
        val key = bookId.trim().lowercase()
        if (key.isEmpty()) return BookStanceResolution.Undetermined
        val file = resolveConfigFile(directory) ?: return BookStanceResolution.Undetermined
        val stamp = runCatching {
            Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        }.getOrNull() ?: return BookStanceResolution.Undetermined
        val entries = if (cachedPath == file && cachedStamp == stamp) {
            cachedEntries
        } else {
            val parsed = runCatching { parseEntries(Files.readString(file)) }.getOrDefault(emptyMap())
            cachedPath = file
            cachedStamp = stamp
            cachedEntries = parsed
            parsed
        }
        return entries[key] ?: BookStanceResolution.Undetermined
    }
}

/**
 * 默认立场解析入口。阶段 1 使用 [ForcedFileBookStanceSource]；
 * 阶段 2（TON-1979）可在保留测试文件优先级的同时替换默认工厂。
 */
internal object BookStanceSources {
    fun forLibrary(library: TextbookLibrary): BookStanceSource =
        ForcedFileBookStanceSource(library.directory)
}
