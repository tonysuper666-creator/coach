package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiteraturePaperMetadataAndNoiseTest {
    private val exactOcr = LiteratureOcrVerifier { book, pdfPage -> book.page(pdfPage)?.text.orEmpty() }

    @Test fun extractsAcademicFrontPageTitleAndMultiAuthorsWithoutInstitutionOrMethodHallucination() {
        // 1) Missing PDF metadata, multi-line title, multi-author with superscripts, chair line right above "Abstract."
        val chairPaperPage1 = """
            arXiv:2304.99999v1 [cs.CL] 13 Apr 2023

            How Useful are Classroom Questions Built
            by Synthetic Language Models?

            Alice Carter1,2, Elena Novak2,3,
            David Okafor2, and Hannah C.K. Patel1,4

            Northern University & Regional AI Institute
            Horizon Labs Inc.
            AI Research Center
            National Frontier AI Chair
            Abstract. Controllable question generation can assist teachers when items match classroom goals.
            Keywords: Question Generation · Classroom Evaluation · Prompting
        """.trimIndent()
        val m1 = LiteratureMetadata.extract(
            pdf = null,
            bookId = "p1",
            storedTitle = "qg-carter2023-useful-questions",
            sourceFilename = "qg-carter2023-useful-questions.pdf",
            firstPagesText = listOf(chairPaperPage1),
        )
        assertEquals("How Useful are Classroom Questions Built by Synthetic Language Models?", m1.title)
        assertEquals("Alice Carter, Elena Novak, David Okafor, Hannah C.K. Patel", m1.author)
        assertEquals("2023", m1.year)
        assertFalse("Chair" in m1.author)
        assertFalse("qg-carter" in m1.title)

        // 2) Missing PDF metadata, Springer-style 3-line title + 3 authors + affiliations + emails
        val springerPage1 = """
            arXiv:2408.88888v1 [cs.CL] 8 Aug 2024

            Automated Classroom Item Creation at
            Different Cognitive Skill Levels using
            Neural Models: Strategies and Review

            Priya Menon1 , Kavya Rao1,2 , Arjun Iyer1

            Department of Computing, National Science Institute, India
            School of Engineering, Coastal University, India
            {priya,arjun}@nsi.example.edu, kavya@example.com

            Abstract. Creating pedagogically sound items across skill levels requires structured prompting.
        """.trimIndent()
        val m2 = LiteratureMetadata.extract(
            pdf = null,
            bookId = "p2",
            storedTitle = "qg-menon2024-skill-levels",
            sourceFilename = "qg-menon2024-skill-levels.pdf",
            firstPagesText = listOf(springerPage1),
        )
        assertEquals("Automated Classroom Item Creation at Different Cognitive Skill Levels using Neural Models: Strategies and Review", m2.title)
        assertEquals("Priya Menon, Kavya Rao, Arjun Iyer", m2.author)
        assertEquals("2024", m2.year)
        assertFalse("qg-menon" in m2.title)

        // 3) Wrapped title ending with a method phrase ("Direct Preference Optimization") right above authors:
        //    must extract the real authors even without PDF metadata, never the method term.
        val methodTitlePage1 = """
            arXiv:2510.77777v2 [cs.CL] 23 Mar 2025

            Difficulty-Controllable Item Creation
            Using Neural Language Models and
            Direct Preference Optimization

            Kenji Sato and Haruto Mori

            Abstract—Adaptive reading practice depends on controlling question difficulty accurately.
        """.trimIndent()
        val m3 = LiteratureMetadata.extract(
            pdf = null,
            bookId = "p3",
            storedTitle = "qg-sato2025-difficulty-items",
            sourceFilename = "qg-sato2025-difficulty-items.pdf",
            firstPagesText = listOf(methodTitlePage1),
        )
        assertEquals("Kenji Sato, Haruto Mori", m3.author)
        assertFalse("Optimization" in m3.author)
        assertTrue("Direct Preference Optimization" in m3.title)

        // 4) 12-author embedded PDF metadata (> 80 chars) and PDF author precedence over heuristic lines.
        val root = Files.createTempDirectory("coach-paper-meta-12-")
        try {
            val twelveAuthors = listOf(
                "Liam Brooks", "Maya Chen", "Noah Alvarez", "Olivia Grant",
                "Ethan Ross", "Sofia Lind", "Lucas Weber", "Aria Singh",
                "Owen Murphy", "Chloe Bennett", "Mateo Silva", "Zoe Kim",
            ).joinToString("; ")
            val pdfPath = root.resolve("qg-brooks2025-field-study.pdf")
            PDDocument().use { pdf ->
                pdf.documentInformation.title = "Evaluating Classroom Item Quality: A Multi-Site Field Study"
                pdf.documentInformation.author = twelveAuthors
                val page = PDPage()
                pdf.addPage(page)
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                PDPageContentStream(pdf, page).use { stream ->
                    fun line(y: Float, text: String) {
                        stream.beginText(); stream.setFont(font, 11f); stream.newLineAtOffset(50f, y); stream.showText(text); stream.endText()
                    }
                    line(740f, "Evaluating Classroom Item Quality: A Multi-Site Field Study")
                    line(720f, "Direct Preference Optimization")
                    line(700f, "Abstract")
                    line(680f, "We evaluate item difficulty and discrimination across ninety college courses.")
                }
                pdf.save(pdfPath.toFile())
            }
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val imported = library.importBook(pdfPath)
            val lit = library.literatureBook(imported.id)
            val meta = assertNotNull(lit.literatureMeta)
            assertEquals("Evaluating Classroom Item Quality: A Multi-Site Field Study", meta.title)
            assertTrue(meta.author.startsWith("Liam Brooks, Maya Chen"), "Unexpected author: ${meta.author}")
            assertTrue("Zoe Kim" in meta.author, "All 12 authors should fit cleanly without dropping: ${meta.author}")
            assertFalse("Direct Preference Optimization" in meta.author)

            // Very long > 240 char author metadata truncates at whole-author boundary with ", et al."
            val twentyAuthors = (1..20).joinToString("; ") { "Researcher$it Surname$it" }
            val longPdfPath = root.resolve("qg-long2025-consortium.pdf")
            PDDocument().use { pdf ->
                pdf.documentInformation.title = "Consortium Benchmark on Classroom Quizzes"
                pdf.documentInformation.author = twentyAuthors
                val page = PDPage()
                pdf.addPage(page)
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText(); stream.setFont(font, 11f); stream.newLineAtOffset(50f, 720f)
                    stream.showText("Abstract. Consortium experiments measure quiz validity across schools.")
                    stream.endText()
                }
                pdf.save(longPdfPath.toFile())
            }
            val longImported = library.importBook(longPdfPath)
            val longAuthor = assertNotNull(library.literatureBook(longImported.id).literatureMeta).author
            assertTrue(longAuthor.length <= 240, "Expected <= 240 chars, got ${longAuthor.length}")
            assertTrue(longAuthor.endsWith(", et al."), "Expected clean et al. truncation: $longAuthor")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun filtersCaptionsSubfiguresAcknowledgmentsFundingAffiliationsAndReferencesFromGuideAndComparison() {
        val page1 = """
            arXiv:2503.12345v1 [cs.CL] 19 Jun 2025

            Evaluating Question Generation with Simulated Learners

            Alice Carter1,2, Elena Novak2,3,
            David Okafor2, and Hannah C.K. Patel1,4

            Northern University & Regional AI Institute
            National Frontier AI Chair
            Correspondence: acarter@northern.example.edu

            Abstract. Structured item analysis reveals quality gaps that surface metrics miss in classroom quizzes.
            Our experiments show that simulated learner profiles improve item discrimination estimates.
            Keywords: Question Generation · Item Analysis · Student Modeling.

            This is a preprint. The Version of Record of this contribution is published in Education Proceedings.
            This work was supported by JSPS KAKENHI Grant Numbers 23K00001, 24H00002.
        """.trimIndent()

        val page2 = """
            (a) Controlled prompt template. Due to space limits, a one-shot template is demonstrated here. In actuality, five examples with different passages are used.

            (b) Controlled prompting strategy examples. All six taxonomy levels are omitted here to save space.

            Figure 1: Controlled prompting strategy.

            Teachers reported higher usefulness when generated items targeted explicit cognitive goals.
            Figure 5 shows the score distribution across both cohorts in the classroom study.

            Fig. 2. Outline of reinforcement training for optimizing the accuracy of difficulty control.

            Table 5. Automated evaluation of classroom items: percentage of high-quality questions and rubric adherence given by the judge model.

            Figure 2: Distribution of predicted proportion of correct answers for each exam type.
            The dashed line indicates the observed proportion of correct answers for participants in our field study.

            Figure 5: Quiz-level quality metrics. The error bars represent 95% confidence intervals. * represents a significant difference at the α = 0.05 level.

            Table 2: Quiz-level quality metrics and annotator agreement.
            The structure and redundancy metrics are on ordinal scales from 1 to 3, and usefulness is on a scale from 1 to 4.

            Table 1 summarizes the inter-annotator agreement across all four evaluation criteria.
        """.trimIndent()

        val page3 = """
            Looking forward, adaptive question generation can reduce instructor workload while preserving exam reliability.

            Acknowledgments
            We thank the students who participated in our study and the instructors who facilitated the exams.
            Additionally, we thank WestEd for their assistance recruiting participants.
            This work was funded by Acme Research and by the Civic Science Fund at Northern University.
            This work was supported by NSF IIS-2119531, IIS-2137396, and ONR N00014-22-1-2507.

            8 Acknowledgements
            The authors would like to thank Mitacs for their grant for this project.
            Additional thanks are extended to the teachers and annotators for their feedback.

            Acknowledgements We'd like to thank Regional Labs for supporting this classroom trial.

            References
            Attali, Y. 2018. Automatic Item Generation. ISBN 978-3-319-93843-1.
            Baker, F. B. 2001. The Basics of Item Response Theory. ISBN 9781886047037.
            Bejar, I. I. 2002. Generative Testing. ISBN 978-1-4106-0214-5.
        """.trimIndent()

        val page4 = """
            Polson, N. G. 2012. On the Half-Cauchy Prior for a Global Scale Parameter. Bayesian Analysis, 7(4): 887-902.
            Publisher: International Society for Bayesian Analysis.
            Item Generation and Validation via Network-Integrated Evaluation.
        """.trimIndent()

        val pages = listOf(
            BookPage(1, page1, guideRegions = listOf(GuideRegion(page1))),
            BookPage(2, page2, guideRegions = listOf(GuideRegion(page2))),
            BookPage(3, page3, guideRegions = listOf(GuideRegion(page3))),
            BookPage(4, page4, guideRegions = listOf(GuideRegion(page4))),
        )
        val book = Textbook(
            id = "paper-clean",
            title = "Evaluating Question Generation with Simulated Learners",
            format = "pdf",
            pages = pages,
            literatureMeta = LiteraturePaperMeta(
                "paper-clean",
                "Evaluating Question Generation with Simulated Learners",
                "Alice Carter, Elena Novak, David Okafor, Hannah C.K. Patel",
                "2025",
            ),
        )
        val quotes = LiteraturePassages.extractCandidates(book).map { it.anchor.quoteAnchor }

        // Real body sentences MUST survive (including prose mentioning Figure 5 / Table 1 and Discussion before Acknowledgments).
        assertTrue(quotes.any { "Structured item analysis reveals quality gaps" in it }, "Abstract first sentence missing: $quotes")
        assertTrue(quotes.any { "simulated learner profiles improve item discrimination" in it }, "Abstract second sentence missing: $quotes")
        assertTrue(quotes.any { "Teachers reported higher usefulness" in it }, "Body sentence missing: $quotes")
        assertTrue(quotes.any { "Figure 5 shows the score distribution" in it }, "Body sentence citing Figure 5 must stay: $quotes")
        assertTrue(quotes.any { "Table 1 summarizes the inter-annotator agreement" in it }, "Body sentence citing Table 1 must stay: $quotes")
        assertTrue(quotes.any { "adaptive question generation can reduce instructor workload" in it }, "Discussion sentence before Acknowledgments must stay: $quotes")

        // Affiliations, authors, keywords, preprint, captions, subfigures, acknowledgments, funding, and references MUST stay out.
        val forbiddenFragments = listOf(
            "Alice Carter1,2",
            "National Frontier AI Chair",
            "Northern University",
            "acarter@",
            "Keywords:",
            "This is a preprint",
            "Version of Record",
            "JSPS KAKENHI",
            "Due to space limits",
            "five examples with different passages",
            "omitted here to save space",
            "Controlled prompting strategy",
            "Outline of reinforcement training",
            "Automated evaluation of classroom items",
            "dashed line indicates",
            "error bars represent",
            "significant difference at the",
            "ordinal scales from 1 to 3",
            "We thank the students",
            "WestEd",
            "funded by Acme Research",
            "Civic Science Fund",
            "NSF IIS-2119531",
            "Mitacs",
            "Additional thanks are extended",
            "Regional Labs",
            "Half-Cauchy Prior",
            "International Society for Bayesian Analysis",
            "Network-Integrated Evaluation",
        )
        for (forbidden in forbiddenFragments) {
            assertTrue(
                quotes.none { forbidden in it },
                "Forbidden noise '$forbidden' leaked into candidates: $quotes",
            )
        }

        // Direct bodySentence gate also rejects standalone funding/ack/caption-legend/affiliation sentences.
        assertFalse(LiteraturePassages.bodySentence("This work was supported by NSF IIS-2119531 and ONR N00014-22-1-2507."))
        assertFalse(LiteraturePassages.bodySentence("The dashed line indicates the observed proportion of correct answers for participants."))
        assertFalse(LiteraturePassages.bodySentence("The error bars represent 95% confidence intervals."))
        assertFalse(LiteraturePassages.bodySentence("National Frontier AI Chair Abstract."))
        assertFalse(LiteraturePassages.bodySentence("Alice Carter1,2, Elena Novak2,3, David Okafor2, and Hannah C.K."))
    }

    @Test fun narrowTwoColumnPdfWithFullWidthCaptionExtractsBodyWithoutLeakingCaptionIntoComparison() {
        val root = Files.createTempDirectory("coach-ieee-2col-")
        try {
            val pdfPath = root.resolve("qg-sato2025-difficulty-mcq.pdf")
            PDDocument().use { pdf ->
                pdf.documentInformation.title = "Difficulty-Controllable Question Creation Using Language Models and Direct Preference Optimization"
                pdf.documentInformation.author = "Kenji Sato; Haruto Mori"
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

                // Page 1: full-width title + author + abstract above two narrow-gutter (~12pt) columns.
                val p1 = PDPage(PDRectangle.LETTER)
                pdf.addPage(p1)
                PDPageContentStream(pdf, p1).use { s ->
                    fun draw(x: Float, y: Float, size: Float, text: String) {
                        s.beginText(); s.setFont(font, size); s.newLineAtOffset(x, y); s.showText(text); s.endText()
                    }
                    draw(60f, 740f, 13f, "Difficulty-Controllable Question Creation Using Language Models and")
                    draw(180f, 722f, 13f, "Direct Preference Optimization")
                    draw(220f, 700f, 10f, "Kenji Sato and Haruto Mori")
                    draw(50f, 675f, 9f, "Abstract-Controlling question difficulty improves adaptive reading practice across student levels.")
                    val leftLines = listOf(
                        "I. INTRODUCTION",
                        "Manual authoring of calibrated reading items",
                        "requires substantial effort from educators.",
                        "Automated systems can generate draft questions",
                        "when guided by item response theory targets.",
                        "Conventional methods struggle to align item",
                        "difficulty with actual learner performance.",
                        "Our proposed approach optimizes preference",
                        "pairs built directly from student test logs.",
                        "Empirical trials confirm lower difficulty error",
                        "across held-out comprehension passages.",
                        "These gains hold for both easy and hard bins.",
                    )
                    val rightLines = listOf(
                        "II. RELATED WORK",
                        "Early template systems depended on rigid",
                        "syntactic rules that limited item diversity.",
                        "Neural encoder models improved fluency but",
                        "rarely modeled psychometric difficulty directly.",
                        "Recent language models generate plausible",
                        "distractors when conditioned on passage cues.",
                        "However, supervised fine-tuning alone leaves",
                        "a noticeable gap on extreme difficulty targets.",
                        "Preference optimization resolves this gap by",
                        "penalizing poorly calibrated question candidates.",
                        "Section three details the training objective.",
                    )
                    leftLines.forEachIndexed { idx, line -> draw(50f, 635f - idx * 14f, 9f, line) }
                    // Left column ends around x=295, right column starts at x=307 (~12pt IEEE gutter).
                    rightLines.forEachIndexed { idx, line -> draw(307f, 635f - idx * 14f, 9f, line) }
                }

                // Page 2: full-width Fig. 2. caption above two-column body text.
                val p2 = PDPage(PDRectangle.LETTER)
                pdf.addPage(p2)
                PDPageContentStream(pdf, p2).use { s ->
                    fun draw(x: Float, y: Float, size: Float, text: String) {
                        s.beginText(); s.setFont(font, size); s.newLineAtOffset(x, y); s.showText(text); s.endText()
                    }
                    draw(100f, 720f, 9f, "Fig. 2. Outline of RLHF for optimizing the accuracy of difficulty control.")
                    val leftBody = listOf(
                        "In reinforcement training, a reward model",
                        "scores generated questions during updates.",
                        "Direct preference optimization derives an",
                        "equivalent objective without online sampling.",
                        "Each passage yields paired high-accuracy and",
                        "low-accuracy question examples for training.",
                        "Minimizing the pairwise loss raises alignment",
                        "between target and estimated difficulty scores.",
                        "Training converges stably within three epochs.",
                        "Validation error drops across every quartile.",
                    )
                    val rightBody = listOf(
                        "III. EXPERIMENTS",
                        "We evaluated ten pre-trained reader models",
                        "to estimate latent item difficulty parameters.",
                        "Each comprehension passage was paired with",
                        "multiple candidate items spanning all levels.",
                        "Calibration metrics show strong correlation",
                        "between requested and measured item difficulty.",
                        "Ablation tests confirm that preference pairs",
                        "outperform standard supervised fine-tuning.",
                        "Human reviewers also rated item fluency high.",
                    )
                    leftBody.forEachIndexed { idx, line -> draw(50f, 660f - idx * 14f, 9f, line) }
                    rightBody.forEachIndexed { idx, line -> draw(307f, 660f - idx * 14f, 9f, line) }
                }
                pdf.save(pdfPath.toFile())
            }

            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val imported = library.importBook(pdfPath)
            val p2Imported = library.importBook(LiteratureGuideFixture.pdf(root, 2))
            val lit1 = library.literatureBook(imported.id)
            val lit2 = library.literatureBook(p2Imported.id)
            val meta = assertNotNull(lit1.literatureMeta)
            assertEquals("Kenji Sato, Haruto Mori", meta.author)
            assertFalse("Direct Preference Optimization" in meta.author)

            val candidates = LiteraturePassages.extractCandidates(lit1)
            val quotes = candidates.map { it.anchor.quoteAnchor.replace('\n', ' ') }
            assertTrue(quotes.size >= 8, "Expected clean 2-column body candidates, got ${quotes.size}: $quotes")
            assertTrue(quotes.none { "Outline of RLHF" in it || "Fig. 2" in it }, "Fig. 2 caption leaked: $quotes")
            assertTrue(quotes.any { "equivalent objective without online sampling" in it }, "Left column body missing: $quotes")
            assertTrue(quotes.any { "outperform standard supervised fine-tuning" in it }, "Right column body missing: $quotes")

            // Comparison table & outline only cite clean body sentences and carry the real author.
            val cmpInput = LiteratureComparisonInput(listOf(lit1, lit2))
            val cmpReq = cmpInput.request()
            val blocksForLit1 = cmpReq.getValue("blocks").jsonArray.map { it.jsonObject }
                .filter { it.getValue("bookId").jsonPrimitive.content == lit1.id }
            assertTrue(blocksForLit1.isNotEmpty())
            assertTrue(blocksForLit1.none { "Outline of RLHF" in it.getValue("text").jsonPrimitive.content })
            val outcome = cmpInput.verify(comparisonReply(cmpReq, outlineCount = 3) { "support" }, exactOcr)
            val table = assertNotNull(outcome.table)
            assertEquals("Kenji Sato, Haruto Mori", table.rows.first().author)
            assertEquals(0, outcome.audit.pendingCites)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun realSixPapersCorpusMetadataAndCleanGuideComparison() {
        val envDir = System.getProperty("coach.papers.dir") ?: System.getenv("COACH_PAPERS_DIR")
        val home = System.getenv("HOME") ?: System.getProperty("user.home")
        val papersDir = if (envDir != null) Path.of(envDir) else Path.of(home, "Coach/materials/public-corpus/papers")
        val slugs = listOf(
            "qg-elkins2023-useful-questions",
            "qg-elkins2024-teachers-bloom",
            "qg-scaria2024-bloom-levels",
            "qg-nguyen2025-qg-sms",
            "qg-isley2025-ai-exams-field-study",
            "qg-tomikawa2025-difficulty-mcq",
        )
        assumeTrue(
            "Requires local public-corpus/papers PDFs",
            slugs.all { Files.isRegularFile(papersDir.resolve(it).resolve("$it.pdf")) },
        )

        val root = Files.createTempDirectory("coach-ton1970-real6-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val litBooks = mutableListOf<Textbook>()
            val forbiddenCandidateNoise = Regex(
                """(?i)\b(?:canada\s+cifar|korbit\s+technologies|mbzuai|nsf\s+iis-|onr\s+n00014|jsps\s+kakenhi|william\s+f\.\s+milton|funded\s+by\s+microsoft|thank\s+mitacs|thank\s+wested|we\s+thank\s+the\s+students|outline\s+of\s+rlhf|conceptual\s+diagram\s+of|visualizations\s+of\s+the\s+usefulness|error\s+bars\s+represent|dashed\s+line\s+indicates|one-shot\s+template\s+is\s+demonstrated|ordinal\s+scales\s+from\s+1\s+to\s+3|automated\s+evaluation\s+of\s+aeqg\s+questions:|this\s+is\s+a\s+preprint|version\s+of\s+record)\b|^\s*(?:fig\.|figure|table)\s+\d+\s*[.:]|^keywords\s*:|^correspondence\s*:|@""",
            )
            for (slug in slugs) {
                val dir = papersDir.resolve(slug)
                val expectedMeta = Json.parseToJsonElement(Files.readString(dir.resolve("meta.json"))).jsonObject
                val expectedTitle = expectedMeta.getValue("title").jsonPrimitive.content
                val expectedAuthors = expectedMeta.getValue("author").jsonPrimitive.content
                    .split(',').map(String::trim).filter(String::isNotEmpty)
                val expectedYear = expectedMeta.getValue("year").jsonPrimitive.content

                val imported = library.importBook(dir.resolve("$slug.pdf"))
                val preMeta = library.literaturePaperMeta(imported.id)
                val lit = library.literatureBook(imported.id)
                val litMeta = assertNotNull(lit.literatureMeta)
                litBooks += lit

                // Neither title nor author may degenerate to filename, institution, or method phrase.
                for (meta in listOf(preMeta, litMeta)) {
                    assertEquals(
                        LiteratureTextNorm.normalize(expectedTitle).lowercase(),
                        LiteratureTextNorm.normalize(meta.title).lowercase(),
                        "Title mismatch for $slug: ${meta.title}",
                    )
                    assertEquals(expectedYear, meta.year, "Year mismatch for $slug")
                    assertTrue(meta.author.isNotBlank(), "Author empty for $slug")
                    assertFalse("CIFAR" in meta.author || "Chair" in meta.author, "Institution leaked into author for $slug: ${meta.author}")
                    assertFalse("Optimization" in meta.author, "Method phrase leaked into author for $slug: ${meta.author}")
                    for (expectedAuthor in expectedAuthors) {
                        val surname = expectedAuthor.substringAfterLast(' ')
                        assertTrue(surname in meta.author, "Expected author surname '$surname' in $slug author='${meta.author}'")
                    }
                }
                assertFalse(imported.title.equals(slug, true), "Imported title degenerated to filename for $slug")

                // Candidates must be rich, offset-exact, and completely free of captions/acks/funding/affiliations/references.
                val candidates = LiteraturePassages.extractCandidates(lit)
                assertTrue(candidates.size >= 50, "Expected >= 50 clean body candidates for $slug, got ${candidates.size}")
                for (c in candidates) {
                    val pageText = assertNotNull(lit.page(c.anchor.pdfPage)).text
                    assertEquals(c.anchor.quoteAnchor, pageText.substring(c.anchor.startUtf16, c.anchor.endUtf16))
                    val flat = c.anchor.quoteAnchor.replace('\n', ' ')
                    assertFalse(
                        forbiddenCandidateNoise.containsMatchIn(flat),
                        "Noise leaked in $slug p${c.anchor.pdfPage}: $flat",
                    )
                }
            }

            // Re-run one-page guide (2 groups of 3) and 6-paper comparison table + review outline.
            val g1 = LiteratureInput(litBooks.subList(0, 3), listOf("How do LLMs generate educational questions?"))
            val g1Guide = assertNotNull(g1.verify(LiteratureGuideFixture.reply(g1.request()), exactOcr).toComplete(litBooks.subList(0, 3), g1.questions))
            assertEquals(3, g1Guide.papers.size)
            assertTrue(g1Guide.papers.all { it.author.isNotBlank() && "CIFAR" !in it.author && "Optimization" !in it.author })

            val g2 = LiteratureInput(litBooks.subList(3, 6), listOf("How are AI exams evaluated and difficulty-controlled?"))
            val g2Guide = assertNotNull(g2.verify(LiteratureGuideFixture.reply(g2.request()), exactOcr).toComplete(litBooks.subList(3, 6), g2.questions))
            assertEquals(3, g2Guide.papers.size)
            assertTrue(g2Guide.papers.all { it.author.isNotBlank() && "CIFAR" !in it.author && "Optimization" !in it.author })

            val cmpInput = LiteratureComparisonInput(litBooks)
            val cmpReq = cmpInput.request()
            val cmpOutcome = cmpInput.verify(comparisonReply(cmpReq, outlineCount = 6), exactOcr)
            val cmpTable = assertNotNull(cmpOutcome.table)
            assertEquals(6, cmpTable.rows.size)
            assertEquals(24, cmpOutcome.audit.tableCells)
            assertEquals(6, cmpOutcome.audit.outlinePoints)
            assertEquals(0, cmpOutcome.audit.pendingCites)
            assertTrue(cmpTable.rows.all { it.author.isNotBlank() && "CIFAR" !in it.author && "Optimization" !in it.author })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
