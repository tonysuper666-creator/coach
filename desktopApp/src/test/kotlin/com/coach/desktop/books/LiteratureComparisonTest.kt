package com.coach.desktop.books

import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiteratureComparisonTest {
    private val pageOcr = LiteratureOcrVerifier { book, pdfPage -> book.page(pdfPage)?.text.orEmpty() }

    @Test fun threeSyntheticPdfsProduceGroundedTableAndOutline() {
        withPapers(3) { books ->
            val input = LiteratureComparisonInput(books)
            val request = input.request()
            assertEquals("literatureComparison", request["kind"]?.jsonPrimitive?.content)
            val outcome = input.verify(comparisonReply(request, outlineCount = 4), pageOcr)
            val table = requireNotNull(outcome.table)
            assertEquals(3, table.rows.size)
            assertEquals(12, table.audit().tableCells)
            assertEquals(4, table.audit().outlinePoints)
            assertEquals(0, table.audit().pendingCites)
            assertEquals(1.0, table.audit().passRate)
            val claim = table.rows.first().claim
            assertNotNull(claim.source)
            assertTrue(claim.text != claim.source.anchor.quoteAnchor)
            assertTrue(claim.source.anchor.quoteAnchor in table.toMarkdown())
            val edited = table.editClaim(books.first().id, "改成课堂讨论用的主张。")
            assertTrue("改成课堂讨论用的主张。" in edited.toMarkdown())
            assertNotNull(edited.rows.first().claim.source)
        }
    }

    @Test fun sixSyntheticPdfsKeepPendingCellInsteadOfInventing() {
        withPapers(6) { books ->
            val input = LiteratureComparisonInput(books)
            val request = input.request()
            val failed = books.last().id
            val outcome = input.verify(comparisonReply(request, failMethodOf = failed, outlineCount = 3), pageOcr)
            val table = requireNotNull(outcome.table)
            assertEquals(6, table.rows.size)
            assertEquals(24, table.audit().tableCells)
            assertEquals(3, table.audit().outlinePoints)
            val method = table.rows.last().method
            assertEquals(COMPARISON_PENDING, method.text)
            assertNull(method.source)
            assertTrue(method.pending)
            assertTrue(table.audit().pendingCites >= 1)
            assertTrue(table.audit().passRate < 1.0)
            assertTrue(COMPARISON_PENDING in table.toMarkdown())
            val copied = input.verify(comparisonReply(request, copyClaimOf = books.first().id, outlineCount = 3), pageOcr).table
            assertEquals(COMPARISON_PENDING, requireNotNull(copied).rows.first().claim.text)
        }
    }

    @Test fun mismatchedOcrMarksTheCellPending() {
        withPapers(3) { books ->
            val input = LiteratureComparisonInput(books)
            val blank = LiteratureOcrVerifier { _, _ -> "unrelated scan noise" }
            val table = requireNotNull(input.verify(comparisonReply(input.request()), blank).table)
            assertTrue(table.rows.all { it.claim.pending && it.claim.source == null })
            assertEquals(COMPARISON_PENDING, table.rows.first().claim.text)
        }
    }

    @Test fun conflictingRelationDirectionsAreNotShownAsVerified() {
        withPapers(2) { books ->
            val input = LiteratureComparisonInput(books)
            val request = input.request()
            val conflict = requireNotNull(
                input.verify(comparisonReply(request, outlineCount = 3) { index ->
                    if (index == 0) "support" else "refute"
                }, pageOcr).table,
            )
            assertTrue(conflict.rows.all { !it.claim.pending })
            assertTrue(conflict.rows.all { row -> row.relations.all { it.cell.pending && it.kind == null } })
            val agreed = requireNotNull(
                input.verify(comparisonReply(request, outlineCount = 3) { _ -> "support" }, pageOcr).table,
            )
            assertEquals(1.0, agreed.audit().passRate)
            assertTrue(agreed.rows.all { row ->
                val relation = row.relations.single()
                !relation.cell.pending && relation.kind == ComparisonRelationKind.SUPPORT
            })
        }
    }

    @Test fun aPaperWithOnlyNotesIsPendingAndNotQuoted() {
        withPapers(2) { books ->
            val notes = """
                12. The cataloguer copied a title from an earlier list and added a short remark about it.
                The note continues as a full sentence after the marker and must stay out of the pool.
                13. Another end note repeats a side remark without stating the paper claim.
                14. A third numbered remark sits in the note region and looks like a body sentence.
                15. A fourth numbered remark also looks like an ordinary sentence about the collection.
            """.trimIndent()
            val emptyPaper = books[1].copy(
                pages = listOf(BookPage(16, notes, guideRegions = listOf(GuideRegion(notes)))),
            )
            val input = LiteratureComparisonInput(listOf(books[0], emptyPaper))
            val blocks = input.request().getValue("blocks").jsonArray.map { it.jsonObject }
            assertTrue(blocks.isNotEmpty())
            assertTrue(blocks.none { it.getValue("bookId").jsonPrimitive.content == emptyPaper.id })
            assertTrue(blocks.none { "cataloguer copied" in it.getValue("text").jsonPrimitive.content })
            val table = requireNotNull(input.verify(comparisonReply(input.request(), outlineCount = 3), pageOcr).table)
            val row = table.rows.single { it.bookId == emptyPaper.id }
            assertEquals(COMPARISON_PENDING, row.claim.text)
            assertTrue(row.claim.pending && row.claim.source == null)
            assertTrue(row.method.pending)
            assertTrue(row.concepts.all { it.pending })
            assertTrue(row.relations.isNotEmpty())
            assertTrue(row.relations.all { it.noDialogue && it.cell.text == COMPARISON_NO_DIALOGUE && !it.cell.pending })
            assertTrue(!table.rows.single { it.bookId == books[0].id }.claim.pending)
        }
    }

    @Test fun omittedRelationSaysThePapersDoNotAddressEachOther() {
        withPapers(3) { books ->
            val input = LiteratureComparisonInput(books)
            val request = input.request()
            val table = requireNotNull(
                input.verify(comparisonReply(request, outlineCount = 3, writeRelation = { index -> index == 0 }), pageOcr).table,
            )
            assertEquals(0, table.audit().pendingCites)
            val cited = table.rows[0].relations.single { !it.noDialogue }
            assertEquals(books[1].id, cited.otherBookId)
            assertNotNull(cited.cell.source)
            assertEquals(ComparisonRelationKind.SUPPORT, cited.kind)
            val quiet = table.rows[0].relations.single { it.noDialogue }
            assertEquals(books[2].id, quiet.otherBookId)
            assertEquals(COMPARISON_NO_DIALOGUE, quiet.cell.text)
            assertTrue(table.rows.drop(1).all { row -> row.relations.all { it.noDialogue } })
            assertTrue(COMPARISON_NO_DIALOGUE in table.toMarkdown())
            assertTrue(COMPARISON_PENDING !in table.toMarkdown())
            val restored = LiteratureComparisonInput.restore(LiteratureComparisonInput.toStoredJson(table), books)
            val again = requireNotNull(restored).rows[1].relations
            assertTrue(again.all { it.noDialogue && !it.cell.pending && it.cell.text == COMPARISON_NO_DIALOGUE })
        }
    }

    @Test fun unverifiedRelationStaysPending() {
        withPapers(2) { books ->
            val input = LiteratureComparisonInput(books)
            val table = requireNotNull(
                input.verify(
                    comparisonReply(input.request(), outlineCount = 3, failRelationOf = books[0].id),
                    pageOcr,
                ).table,
            )
            val bad = table.rows[0].relations.single()
            assertTrue(bad.cell.pending)
            assertEquals(COMPARISON_PENDING, bad.cell.text)
            assertTrue(!bad.noDialogue)
            val kept = table.rows[1].relations.single()
            assertTrue(!kept.cell.pending && !kept.noDialogue)
        }
    }

    @Test fun virtuosoMistranslationIsRewrittenBeforeTheTableIsKept() {
        withPapers(2) { books ->
            val input = LiteratureComparisonInput(books)
            val table = requireNotNull(
                input.verify(
                    comparisonReply(input.request(), outlineCount = 3, badTermOf = books[0].id),
                    pageOcr,
                ).table,
            )
            val claim = table.rows[0].claim
            assertEquals("模型把这群人叫做鉴赏家。", claim.text)
            assertTrue(!claim.pending)
            assertTrue("虚拳客" !in table.toMarkdown())
        }
    }

    @Test fun onePaperDoesNotBuildATable() {
        withPapers(1) { books ->
            val error = assertFailsWith<IllegalArgumentException> { LiteratureComparisonInput(books) }
            assertEquals(comparisonRefusal(1), error.message)
        }
        assertTrue("没有出表" in comparisonRefusal(0))
    }

    @Test fun invalidReplyIsNotFilledIn() {
        withPapers(3) { books ->
            val outcome = LiteratureComparisonInput(books).verify(buildJsonObject { put("invalidComparison", true) }, pageOcr)
            assertNull(outcome.table)
            assertTrue("没有硬填" in outcome.notice)
        }
    }

    @Test fun editsRoundTripThroughTheLocalStore() {
        val root = Files.createTempDirectory("cmp-store-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val imported = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val books = imported.map { library.literatureBook(it.id) }
            val course = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "对比", CourseExamProfile(), books.map { it.id }).first
            }
            val table = requireNotNull(
                LiteratureComparisonInput(books).verify(comparisonReply(LiteratureComparisonInput(books).request(), outlineCount = 4), pageOcr).table,
            ).editClaim(books.first().id, "存下来的主张。")
            library.withCourseDatabase { LiteratureComparisonStore.save(it, course, books.map { it.id }, table) }
            val loaded = library.withCourseDatabase { LiteratureComparisonStore.load(it, course, LiteratureComparisonStore.digest(books.map { it.id })) }
            val restored = LiteratureComparisonInput.restore(requireNotNull(loaded?.stored), books)
            assertEquals("存下来的主张。", requireNotNull(restored).rows.first().claim.text)
            assertNotNull(restored.rows.first().claim.source)
            library.withCourseDatabase { LiteratureComparisonStore.forgetBook(it, books.first().id) }
            assertNull(library.withCourseDatabase { LiteratureComparisonStore.latest(it, course) })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun withPapers(count: Int, block: (List<Textbook>) -> Unit) {
        val root = Files.createTempDirectory("cmp-pdf-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val books = (1..count).map { index ->
                library.literatureBook(library.importBook(LiteratureGuideFixture.pdf(root, index)).id)
            }
            block(books)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

internal fun comparisonReply(
    request: JsonObject,
    failMethodOf: String? = null,
    copyClaimOf: String? = null,
    outlineCount: Int = 4,
    writeRelation: (Int) -> Boolean = { true },
    failRelationOf: String? = null,
    badTermOf: String? = null,
    relationKind: ((index: Int) -> String)? = null,
): JsonObject {
    val blocks = request.getValue("blocks").jsonArray.map { it.jsonObject }
    val byBook = blocks.groupBy { it.getValue("bookId").jsonPrimitive.content }
    val ids = request.getValue("papers").jsonArray.map { it.jsonObject.getValue("bookId").jsonPrimitive.content }
    val usable = ids.filter { byBook[it].orEmpty().isNotEmpty() }
    return buildJsonObject {
        put("rows", buildJsonArray {
            ids.forEachIndexed { index, id ->
                val refs = byBook[id].orEmpty()
                if (refs.isEmpty()) {
                    add(buildJsonObject {
                        put("bookId", id)
                        put("claim", buildJsonObject {
                            put("text", "模型仍给这一篇写了主张。")
                            put("ref", 0)
                        })
                        put("method", buildJsonObject {
                            put("text", "模型仍给这一篇写了方法。")
                            put("ref", 0)
                        })
                        put("concepts", buildJsonArray {
                            add(buildJsonObject {
                                put("text", "模型仍给这一篇写了概念。")
                                put("ref", 0)
                            })
                        })
                        put("relations", buildJsonArray {})
                    })
                    return@forEachIndexed
                }
                val claimRef = refs.first()
                val methodRef = refs.getOrElse(1) { claimRef }
                val other = ids[(index + 1) % ids.size]
                val claimText = when {
                    id == badTermOf -> "模型把这群人叫做虚拳客。"
                    id == copyClaimOf -> claimRef.getValue("text").jsonPrimitive.content
                    else -> "这篇用自己的话概括了主张，不复述原句。"
                }
                add(buildJsonObject {
                    put("bookId", id)
                    put("claim", buildJsonObject {
                        put("text", claimText)
                        put("ref", claimRef.getValue("ref"))
                    })
                    put("method", buildJsonObject {
                        put("text", "方法是对照样本并记录差异。")
                        put("ref", if (id == failMethodOf) JsonPrimitive(9999) else methodRef.getValue("ref"))
                    })
                    put("concepts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "遮阴下的生长")
                            put("ref", refs.last().getValue("ref"))
                        })
                    })
                    put("relations", buildJsonArray {
                        if (!writeRelation(index)) return@buildJsonArray
                        add(buildJsonObject {
                            put("otherBookId", other)
                            put("kind", relationKind?.invoke(index) ?: when (index % 3) {
                                0 -> "support"
                                1 -> "refute"
                                else -> "supplement"
                            })
                            put("text", "它和另一篇在证据上互相参照。")
                            put("ref", if (id == failRelationOf) JsonPrimitive(9999) else claimRef.getValue("ref"))
                        })
                    })
                })
            }
        })
        put("outline", buildJsonArray {
            if (usable.isEmpty()) return@buildJsonArray
            repeat(outlineCount) { n ->
                val left = usable[n % usable.size]
                val right = usable[(n + 1) % usable.size]
                add(buildJsonObject {
                    put("thesis", "论点 ${n + 1}：样本条件改变了生长速度。")
                    put("supports", buildJsonArray {
                        add(buildJsonObject {
                            put("bookId", left)
                            put("ref", byBook.getValue(left).first().getValue("ref"))
                        })
                    })
                    put("opposes", buildJsonArray {
                        add(buildJsonObject {
                            put("bookId", right)
                            put("ref", byBook.getValue(right).first().getValue("ref"))
                        })
                    })
                })
            }
        })
    }
}
