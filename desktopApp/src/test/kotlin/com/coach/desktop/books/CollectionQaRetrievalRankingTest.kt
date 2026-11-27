package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TON-1966: Regression tests for library Q&A retrieval, cross-lingual query expansion,
 * BM25 + concept-coverage + heading/definitional reranking, and out-of-book refusal.
 */
class CollectionQaRetrievalRankingTest {

    @Test
    fun namedPapersOverrideCurrentBookAndComparisonsKeepBoth() {
        val root = Files.createTempDirectory("coach-qa-paper-scope-")
        try {
            val lib = TextbookLibrary(root.resolve("lib"))
            fun importPaper(name: String): Textbook {
                val file = root.resolve("$name.md")
                Files.writeString(file, "# $name\nThe teachers compared hand-written quizzes with generated quizzes.")
                return lib.importBook(file).also { lib.saveReadSpot(it.id, 1, 0) }
            }
            val older = importPaper("Rivers2023-classroom")
            val newer = importPaper("Rivers2024-teachers")
            val survey = importPaper("Survey-of-quizzes")
            fun books(q: String) = CollectionQaRetriever.retrieve(
                lib, CollectionQaVirtualCollections.READ_LIBRARY_ID, q, newer.id,
            ).blocks.map { it.bookId }.toSet()
            assertEquals(setOf(older.id), books("What did Rivers (2023) say about quizzes?"))
            assertEquals(setOf(newer.id), books("How did teachers compare generated and hand-written quizzes?"))
            assertEquals(setOf(older.id, newer.id), books("Compare quizzes in Rivers 2023 and Rivers 2024."))
            assertEquals(setOf(older.id, newer.id), books("Compare this paper with Rivers et al. (2023) on quizzes."))
            assertEquals(setOf(older.id, newer.id, survey.id), books("Compare quizzes across the papers."))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun mixedPublicCorpusRetrievesNamedPaperAndOpeningScenesWithoutRefusalLinks() = runBlocking {
        val nonfiction = System.getenv("COACH_CORPUS_DIR") ?: return@runBlocking assumeTrue("Requires public corpus", false)
        val corpus = Path.of(nonfiction).parent
        val questions = mapOf(
            "qg-elkins2023-useful-questions" to listOf("Which two taxonomies did teachers evaluate in the useful questions study?", "What did teachers say about classroom usefulness?"),
            "qg-elkins2024-teachers-bloom" to listOf("Which automatically generated quizzes did teachers prefer to write?", "How did quiz quality compare with handwritten quizzes?"),
            "qg-scaria2024-bloom-levels" to listOf("Which cognitive levels did Scaria target using Bloom taxonomy?", "Who evaluated the linguistic and pedagogical quality of the questions?"),
            "qg-nguyen2025-qg-sms" to listOf("What dimensions of test item quality does QG-SMS compare?", "Why does QG-SMS simulate student profiles?"),
            "qg-isley2025-ai-exams-field-study" to listOf("How does the field study compare AI-generated and human-written exam items?", "What does item discrimination measure in the field study?"),
            "qg-tomikawa2025-difficulty-mcq" to listOf("Why use direct preference optimization for difficulty control?", "How does item response theory represent item difficulty?"),
            "pride-and-prejudice" to listOf("Why does Mrs. Bennet want her husband to visit Mr. Bingley?", "What impression does Darcy make on Elizabeth at the assembly?"),
            "moby-dick" to listOf("Why does Ishmael choose to go to sea as a sailor?", "What sort of place does Ishmael find at the Spouter-Inn?"),
            "sherlock-holmes-adventures" to listOf("How does Watson describe Holmes’s regard for Irene Adler?", "What does Holmes ask to keep as a reward from the King of Bohemia?"),
        )
        val scenePages = mapOf(
            "pride-and-prejudice" to listOf(10..11, 12..13),
            "moby-dick" to listOf(11..13, 15..17),
            "sherlock-holmes-adventures" to listOf(3..3, 14..15),
        )
        val root = Files.createTempDirectory("coach-qa-mixed-shelf-")
        try {
            val lib = TextbookLibrary(root.resolve("lib"), lazyPdfExtras = true)
            val books = questions.keys.associateWith { name ->
                val file = if (name in scenePages) "fiction/$name/$name.epub" else "papers/$name/$name.pdf"
                lib.importBook(corpus.resolve(file))
            }
            val distractor = lib.importBook(corpus.resolve("chinese/tianyanlun/tianyanlun.txt"))
            for (book in books.values + distractor) {
                lib.save(book.copy(readingPage = book.pages.size))
                lib.saveReadSpot(book.id, book.pages.size, 0)
            }
            val collection = CollectionQaVirtualCollections.READ_LIBRARY_ID
            val missedScenes = mutableListOf<String>()
            for ((name, book) in books) {
                questions.getValue(name).forEachIndexed { index, question ->
                    val blocks = CollectionQaRetriever.retrieve(lib, collection, question, book.id).blocks
                    assertEquals(setOf(book.id), blocks.map { it.bookId }.toSet(), "$name question $index")
                    scenePages[name]?.get(index)?.let { expected ->
                        if (blocks.none { it.page in expected }) missedScenes += "$name question $index retrieved ${blocks.map { it.page }}"
                    }
                }
                val result = CollectionQaEngine.ask(
                    lib, collection, "這本書記載 2043 年虛構的 ZYXQ-7741 電池實測容量是多少毫安時？", book.id,
                    remoteAsk = { CourseServerCallResult(buildJsonObject { put("insufficientEvidence", true) }, null) },
                )
                result.fold(
                    onSuccess = { assertTrue(it.sentences.isEmpty() && it.conflictCards.isEmpty() && it.fallbackMessage != null) },
                    onFailure = { assertTrue(it is CollectionQaException && it.fallbackPages.isEmpty()) },
                )
            }
            assertTrue(missedScenes.isEmpty(), missedScenes.joinToString("\n"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun syntheticClassicsDirectEvidenceBeatsTangentialMentionsAndOutOfBookRefuses() = runBlocking {
        val root = Files.createTempDirectory("coach-ton1966-synth-")
        try {
            val lib = TextbookLibrary(root.resolve("lib"))

            // 1. The Prince pattern: earlier tangential chapter mentions "ministers" & "prince";
            // later chapter defines how to form an opinion of a prince by his choice of servants/secretaries.
            val princePages = (1..20).map { p ->
                when (p) {
                    5 -> "# CHAPTER IV. KINGDOM OF DARIUS\nA prince governs with servants who assist him as ministers by his favour and permission; ministers have no authority of their own."
                    16 -> "# CHAPTER XXII. CONCERNING THE SECRETARIES OF PRINCES\nThe choice of servants is of no little importance to a prince, and they are good or not according to the discrimination of the prince. The first opinion which one forms of a prince and his understanding is by observing the men he has around him; when capable and faithful he may be considered wise."
                    19 -> "*** END OF THE PROJECT GUTENBERG EBOOK THE PRINCE ***\nFull Project Gutenberg License: any contract or agreement regarding electronic works."
                    else -> "# CHAPTER $p\nBackground historical narrative on principalities, armies, and provinces (page $p)."
                }
            }
            val princeBook = lib.importBook(
                root.resolve("The-Prince.md").also {
                    Files.writeString(it, princePages.joinToString("\n\u000c\n"))
                },
            )

            // 2. Leviathan pattern: earlier page explicitly defines CONTRACT vs PACT/COVENANT;
            // later page only mentions Covenant incidentally alongside Representer/Sureties.
            val leviathanPages = (1..20).map { p ->
                when (p) {
                    8 -> "# CHAPTER XIV. OF LAWES OF NATURE\nContract What: The mutuall transferring of Right is that which men call CONTRACT. There is difference between transferring of Right to the Thing and delivery. Covenant What: One of the Contractors may deliver and trust the other; then the Contract on his part is called PACT, or COVENANT."
                    15 -> "# CHAPTER XVI. OF PERSONS AND AUTHORS\nA conditional author owneth an Action or Covenant of another conditionally; these are called Sureties."
                    else -> "# CHAPTER $p\nGeneral political discourse on commonwealths and laws (page $p)."
                }
            }
            val leviathanBook = lib.importBook(
                root.resolve("Leviathan.md").also {
                    Files.writeString(it, leviathanPages.joinToString("\n\u000c\n"))
                },
            )

            // 3. Republic pattern: earlier page mentions injustice + pilot/physician metaphor + work;
            // later page directly defines justice in the State as doing one's own business/work.
            val republicPages = (1..20).map { p ->
                when (p) {
                    6 -> "Injustice is profitable when hidden. Let the unjust be like a skilful pilot or physician who recovers if he fails in the work of his life."
                    14 -> "You remember the principle at the foundation of the State: now justice is this principle, that one man should practise one thing only. We affirmed that justice was doing one's own business, and not being a busybody; every one doing his own work in the State is justice."
                    else -> "Dialogue between Socrates and Glaucon on education and music (page $p)."
                }
            }
            val republicBook = lib.importBook(
                root.resolve("The-Republic.md").also {
                    Files.writeString(it, republicPages.joinToString("\n\u000c\n"))
                },
            )

            for (b in listOf(princeBook, leviathanBook, republicBook)) {
                lib.save(b.copy(readingPage = b.pages.size))
            }

            // Case 1: Prince (both English and Chinese queries land on p.16, not p.5)
            for (q in listOf("How to judge a prince from his choice of ministers?", "如何从选任大臣判断君主")) {
                val bundle = CollectionQaRetriever.retrieve(lib, CollectionQaVirtualCollections.READ_LIBRARY_ID, q)
                assertTrue(bundle.blocks.isNotEmpty(), "Expected blocks for '$q'")
                assertEquals(princeBook.id, bundle.blocks.first().bookId, "Top block must be The Prince for '$q'")
                assertEquals(16, bundle.blocks.first().page, "Expected p.16 for '$q', got p.${bundle.blocks.first().page}")
                assertFalse(bundle.blocks.any { it.bookId == princeBook.id && it.page == 19 }, "License boilerplate must not displace content")
            }

            // Case 2: Leviathan (both English and Chinese queries land on p.8, not p.15)
            for (q in listOf("What is the difference between covenant and contract?", "契约与合同的区别")) {
                val bundle = CollectionQaRetriever.retrieve(lib, CollectionQaVirtualCollections.READ_LIBRARY_ID, q)
                assertTrue(bundle.blocks.isNotEmpty(), "Expected blocks for '$q'")
                assertEquals(leviathanBook.id, bundle.blocks.first().bookId, "Top block must be Leviathan for '$q'")
                assertEquals(8, bundle.blocks.first().page, "Expected p.8 for '$q', got p.${bundle.blocks.first().page}")
            }

            // Case 3: Republic (both English and Chinese queries land on p.14, not p.6)
            for (q in listOf("justice in the city and each doing their own work", "城邦正义与各司其职")) {
                val bundle = CollectionQaRetriever.retrieve(lib, CollectionQaVirtualCollections.READ_LIBRARY_ID, q)
                assertTrue(bundle.blocks.isNotEmpty(), "Expected blocks for '$q'")
                assertEquals(republicBook.id, bundle.blocks.first().bookId, "Top block must be The Republic for '$q'")
                assertEquals(14, bundle.blocks.first().page, "Expected p.14 for '$q', got p.${bundle.blocks.first().page}")
            }

            // Case 4: Out-of-book queries (even containing stopwords like "and", "the", "what") must NOT retrieve random pages
            for (q in listOf("What is quantum entanglement and blockchain consensus?", "量子纠缠与区块链共识是什么")) {
                val bundle = CollectionQaRetriever.retrieve(lib, CollectionQaVirtualCollections.READ_LIBRARY_ID, q)
                assertTrue(bundle.blocks.isEmpty(), "Out-of-book query '$q' should retrieve 0 blocks, got ${bundle.blocks.size}")
                val turn = CollectionQaEngine.ask(
                    library = lib,
                    collectionId = CollectionQaVirtualCollections.READ_LIBRARY_ID,
                    question = q,
                    remoteAsk = { error("Should not call server when nothing retrieved") },
                ).getOrThrow()
                assertTrue(turn.sentences.isEmpty())
                assertTrue(turn.fallbackMessage?.contains("没有检索到") == true)
            }

            // Case 5: Engine end-to-end accepts grounded citation on p.16 when server answers from B1
            val askTurn = CollectionQaEngine.ask(
                library = lib,
                collectionId = CollectionQaVirtualCollections.READ_LIBRARY_ID,
                question = "如何从选任大臣判断君主",
                remoteAsk = { _ ->
                    CourseServerCallResult(
                        body = buildJsonObject {
                            put("sentences", buildJsonArray {
                                add(buildJsonObject {
                                    put("claim", "君主选任仆从与大臣的眼光，直接反映其本人是否明智。")
                                    put("blockId", "B1")
                                    put("quoteAnchor", "choice of servants is of no little importance to a prince")
                                })
                            })
                            put("hasMaterialConflict", false)
                            put("conflictSummaryZh", "")
                        },
                        error = null,
                    )
                },
            ).getOrThrow()
            assertEquals(1, askTurn.sentences.size)
            assertEquals(16, askTurn.sentences.first().citation.page)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun realCorpusThreeClassicsHitDirectEvidencePages() {
        val envDir = System.getProperty("coach.corpus.dir") ?: System.getenv("COACH_CORPUS_DIR")
        val corpusRoot = if (envDir != null) {
            Path.of(envDir)
        } else {
            Path.of(System.getProperty("user.home"), "Coach/materials/public-corpus/nonfiction")
        }
        assumeTrue("Requires local public-corpus nonfiction EPUBs", Files.isDirectory(corpusRoot))
        val root = Files.createTempDirectory("coach-ton1966-real-")
        try {
            val lib = TextbookLibrary(root.resolve("lib"), ocr = BookOcr { _, _ -> "" }, lazyPdfExtras = true)
            val prince = lib.importBook(corpusRoot.resolve("the-prince/the-prince.epub"))
            val leviathan = lib.importBook(corpusRoot.resolve("leviathan/leviathan.epub"))
            val republic = lib.importBook(corpusRoot.resolve("plato-republic/plato-republic.epub"))
            val liberty = lib.importBook(corpusRoot.resolve("on-liberty/on-liberty.epub"))

            for (b in listOf(prince, leviathan, republic, liberty)) {
                lib.save(b.copy(readingPage = b.pages.size))
            }

            fun topHit(q: String): CollectionQaBlock {
                val bundle = CollectionQaRetriever.retrieve(lib, CollectionQaVirtualCollections.READ_LIBRARY_ID, q)
                assertTrue(bundle.blocks.isNotEmpty(), "Expected blocks for '$q'")
                return bundle.blocks.first()
            }

            // 1. The Prince: p.64 (not p.20)
            for (q in listOf("How to judge a prince from his choice of ministers?", "如何从选任大臣判断君主")) {
                val top = topHit(q)
                assertEquals(prince.id, top.bookId, "Top book for '$q' was ${top.shortLabel} p.${top.page}")
                assertEquals(64, top.page, "Expected The Prince p.64 for '$q', got p.${top.page}")
            }

            // 2. Leviathan: p.79 (not p.95)
            for (q in listOf("What is the difference between covenant and contract?", "契约与合同的区别")) {
                val top = topHit(q)
                assertEquals(leviathan.id, top.bookId, "Top book for '$q' was ${top.shortLabel} p.${top.page}")
                assertEquals(79, top.page, "Expected Leviathan p.79 for '$q', got p.${top.page}")
            }

            // 3. Plato's Republic: p.208 (not p.165)
            for (q in listOf("justice in the city and each doing their own work", "城邦正义与各司其职")) {
                val top = topHit(q)
                assertEquals(republic.id, top.bookId, "Top book for '$q' was ${top.shortLabel} p.${top.page}")
                assertEquals(208, top.page, "Expected Plato's Republic p.208 for '$q', got p.${top.page}")
            }

            // 4. Out-of-book Chinese refusal
            val outZh = CollectionQaRetriever.retrieve(lib, CollectionQaVirtualCollections.READ_LIBRARY_ID, "量子纠缠与区块链共识")
            assertTrue(outZh.blocks.isEmpty(), "Expected 0 blocks for out-of-book Chinese query")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
