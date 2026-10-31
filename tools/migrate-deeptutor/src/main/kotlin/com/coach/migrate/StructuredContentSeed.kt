package com.coach.migrate

import com.coach.domain.*
import com.coach.domain.Module
import com.coach.domain.TextbookPage as DomainTextbookPage
import com.coach.store.LedgerStore
import java.nio.channels.FileChannel
import java.nio.file.*
import java.sql.DriverManager
import kotlin.io.path.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private data class Book(val chapter: Int, val sourceName: String, val document: TextbookDocument)

/** Parse only explicit PDF page markers; never synthesize page numbers from text position. */
fun textbookPages(markdown: String): List<DomainTextbookPage> {
    val markers = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->").findAll(markdown).toList()
    val pages = markers.mapIndexed { i, m ->
        DomainTextbookPage(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull(),
            markdown.substring(m.range.first, markers.getOrNull(i + 1)?.range?.first ?: markdown.length))
    }
    require(pages.map { it.pdfPage }.distinct().size == pages.size && pages.all { it.pdfPage > 0 && it.text.isNotBlank() })
    return pages
}

/** Only course metadata, the selected outline and textbook text cross this boundary. */
fun contentSeed(snapshot: Path, weights: Path = snapshot.resolve("exam-weights.md")): ContentSeed {
    val root = snapshot.toRealPath()
    require(root.resolve("copy-stats.json").isRegularFile()) { "Use a captured staging directory" }
    fun staged(relative: String): Path = root.resolve(relative).toRealPath().also {
        require(it.startsWith(root)) { "Content source escapes staged directory" }
    }
    val courses = parseFile(staged("data/user/workspace/courses/courses.json")).jsonObject.array("courses").map { it.jsonObject }
    val course = courses.single { it.str("name").contains("ECNM10119") }
    val pathId = course.array("resources").map { it.jsonObject }.single { it.str("kind") == "mastery_path" }.str("ref_id")
    val dbPath = staged("data/user/workspace/learning/mastery/mastery.sqlite3")
    require(!dbPath.startsWith(resolvedDestination(Path.of(System.getProperty("user.home"), "DeepTutor")))) { "Never connect to DeepTutor" }
    val state = DriverManager.getConnection("jdbc:sqlite:file:${dbPath.toUri().rawPath}?mode=ro").use { db ->
        db.createStatement().use { it.execute("PRAGMA query_only=ON") }
        db.autoCommit = false
        db.createStatement().use { st -> st.executeQuery("PRAGMA integrity_check").use { rs ->
            require(rs.next() && rs.getString(1) == "ok" && !rs.next()) { "Snapshot integrity check failed" }
        } }
        db.prepareStatement("SELECT state_json FROM mastery_paths WHERE path_id=?").use { ps ->
            ps.setString(1, pathId); ps.executeQuery().use { rs ->
                require(rs.next()) { "Course mastery path missing from snapshot" }
                parseText(rs.getString(1)).jsonObject
            }
        }.also { db.rollback() }
    }
    val books = safeFiles(staged("library/ECNM10119-健康经济学/教材")).filter { it.extension == "md" }.mapNotNull { file ->
        val chapter = file.name.substringBefore('-').toIntOrNull() ?: return@mapNotNull null
        if(chapter !in 1..12) return@mapNotNull null // Approved midterm textbook scope, not later chapters or slides.
        require(file.toRealPath().startsWith(root))
        val markdown = file.readText()
        val pages = textbookPages(markdown)
        require(pages.isNotEmpty()) { "Textbook has no explicit page markers: ${file.name}" }
        val sourceName = Regex("来源：(.+?\\.pdf)").find(markdown)?.groupValues?.get(1).orEmpty()
        Book(chapter, sourceName, TextbookDocument("textbook:" + sha("${course.str("id")}:教材/${file.name}").take(24), file.name, markdown, pages))
    }
    val gateFile = root.resolve("tools/patch_backups/gate_types_before.json")
    val originalKinds = if(gateFile.isRegularFile()) parseFile(staged("tools/patch_backups/gate_types_before.json")).jsonObject else JsonObject(emptyMap())
    val rawModules = state.array("modules").map { it.jsonObject }
    // Preserve source array order; no sorting by IDs, names or old mastery state.
    val modules = rawModules.mapIndexed { i, m -> Module(m.str("id"), i, m.str("name")) }
    val evidence = state.obj("lesson_evidence")
    val points = rawModules.flatMap { m -> m.array("knowledge_points").map { it.jsonObject } }.mapIndexed { i, kp ->
        val title = kp.str("name")
        val explicitChapters = chapters(title)
        val terms = Regex("[A-Za-z][A-Za-z-]{3,}").findAll(title).map { it.value.lowercase() }
            .filterNot { it in setOf("slides", "essay", "chapter") }.toSet()
        fun score(page: DomainTextbookPage) = terms.count { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(page.text) }
        val explicitRefs = evidence.obj(kp.str("id")).array("sources").map { it.jsonObject.obj("ref") }
        val candidates = books.flatMap { book -> book.document.pages.mapNotNull { page ->
            val fromRef = explicitRefs.any { ref ->
                book.sourceName.isNotBlank() && ref.str("document_key").substringAfterLast('/') == book.sourceName &&
                    ref["page_index"]?.jsonPrimitive?.intOrNull?.plus(1) == page.pdfPage &&
                    (ref.str("page_label").toIntOrNull() == null || ref.str("page_label").toIntOrNull() == page.bookPage)
            }
            val hits = score(page)
            val basis = when {
                fromRef -> "SOURCE_REFERENCE"
                book.chapter in explicitChapters -> "CHAPTER_RANGE"
                explicitChapters.isEmpty() && hits >= 2 -> "TEXT_MATCH"
                else -> return@mapNotNull null
            }
            Triple(SourceAnchor(book.document.id, page.pdfPage, page.bookPage, basis=basis), fromRef, hits)
        } }.sortedWith(compareByDescending<Triple<SourceAnchor, Boolean, Int>> { it.second }.thenByDescending { it.third }
            .thenBy { it.first.docId }.thenBy { it.first.pdfPage })
        KnowledgePoint(kp.str("id"), kp.str("module_id"), i, title,
            kind=originalKinds.str(kp.str("id")).takeIf { it.isNotBlank() }?.uppercase(),
            examFormats=if("【Essay】" in title) setOf(Format.ESSAY) else emptySet(),
            prerequisites=kp.array("prerequisite_ids").map { it.jsonPrimitive.content },
            anchors=candidates.map { it.first }, legacyId="legacy:mastery.sqlite3:knowledge_points:${kp.str("id")}")
    }
    val groups = examWeightGroups(weights.readText(), modules, points)
    val allocations = points.associate { k -> k.id to groups.sumOf { it.allocations[k.id] ?: 0 } }
    // Source instructions contain historical progress and DeepTutor navigation. Do not carry them over.
    return ContentSeed(CourseContent(Course(course.str("id"), "ECNM10119", course.str("name"),
        examWeights=allocations, examWeightGroups=groups), modules, points), books.map { it.document })
}

/** Resolve the published ECNM10119 hints explicitly; ambiguous or new rows stop before writing. */
fun examWeightGroups(markdown: String, modules: List<Module>, points: List<KnowledgePoint>): List<ExamWeightGroup> {
    fun select(module: String, term: String? = null): List<String> {
        val id = modules.single { it.title.startsWith("$module ") }.id
        val selected = points.filter { it.moduleId == id && (term == null || it.title.contains(term, ignoreCase=true)) }
        require(if(term == null) selected.isNotEmpty() else selected.size == 1) { "Ambiguous exam weight selector $module / $term" }
        return selected.map { it.id }
    }
    val rows = markdown.lines().filter { it.startsWith('|') }.map { it.split('|').drop(1).dropLast(1).map(String::trim) }
        .filter { it.firstOrNull()?.toIntOrNull() != null }
    require(rows.isNotEmpty()) { "No exam weight rows" }
    return rows.map { cells ->
        val n = cells[0].toInt(); val question = cells[1]; val hint = cells[2]
        val ids = when {
            hint.startsWith("05 RS") -> select("05")
            hint.startsWith("07·RAND/Oregon") && hint.contains("06·") -> select("07", "RAND HIE 设计") + select("06", "CRHIE")
            hint.startsWith("03·本地医院市场结构") -> select("03", "differentiated product oligopoly")
            hint.startsWith("03·美国医院趋势") -> select("03", "Hill-Burton")
            hint == "02·HHI" -> select("02", "HHI=")
            hint.startsWith("06·因果识别") -> select("06", "CRHIE")
            hint == "02·NPV/IRR" -> select("02", "NPV=")
            hint == "09·三个预测" -> select("09", "三个预测")
            hint == "11·假说对应 Grossman 参数" -> select("11", "假说对应 Grossman 参数")
            hint.startsWith("07·ex ante/ex post") -> select("07", "实证证据归类")
            else -> error("Unresolved exam weight row: $hint")
        }
        require(n >= 0 && ids.distinct().size == ids.size)
        val allocated = ids.mapIndexed { i, id -> id to n / ids.size + if(i < n % ids.size) 1 else 0 }.toMap()
        ExamWeightGroup(question, n, ids, allocated)
    }
}

fun seedReport(seed: ContentSeed): String = buildString {
    val c = seed.content
    val missing = c.knowledgePoints.filter { it.anchors.isEmpty() }
    append("Course content only; learning records imported: 0\n")
    append("Modules: ${c.modules.size}; KP: ${c.knowledgePoints.size}; documents: ${seed.documents.size}; pages: ${seed.documents.sumOf { it.pages.size }}\n")
    append("Missing textbook anchors: ${missing.size}\n")
    missing.forEach { append("- ${it.id}: ${it.title}\n") }
    append("Original KP kind unavailable: ${c.knowledgePoints.count { it.kind == null }} (left null, not inferred)\n")
    append("Anchors: SOURCE_REFERENCE = existing textbook locator; CHAPTER_RANGE = declared chapter context; TEXT_MATCH = at least two title terms on the actual page. These are not claims of paragraph-level verification.\n")
    append("Exam weights are estimates. Each source row is allocated equally across its listed KPs; remaining integer points follow listed order. Row totals are preserved; total ${c.course.examWeights.values.sum()}.\n")
    c.course.examWeightGroups.forEach { append("- ${it.question}: ${it.points} = ${it.allocations.values.joinToString(" + ")} (${it.kpIds.joinToString()})\n") }
}

fun seedContentCommand(args: Array<String>) {
    val allowed = setOf("--source", "--staging", "--snapshot", "--out", "--weights", "--database")
    require(args.size % 2 == 0) { "seed-content options require values" }
    val pairs = args.toList().chunked(2)
    require(pairs.all { it[0] in allowed } && pairs.map { it[0] }.distinct().size == pairs.size) { "Unknown or repeated seed-content option" }
    val options = pairs.associate { it[0] to it[1] }
    val home = Path.of(System.getProperty("user.home"))
    fun option(key: String, default: Path) = resolvedDestination(options[key]?.let(Path::of) ?: default)
    val source = option("--source", home.resolve("DeepTutor"))
    val protected = listOf(source, resolvedDestination(home.resolve("DeepTutor")))
    val staging = option("--staging", home.resolve("Coach/migrate-staging"))
    val out = option("--out", home.resolve("Coach/seed-content-out"))
    val database = option("--database", home.resolve("Coach/data"))
    for(p in listOf(staging, out, database)) requirePrivateDestination(p, protected)
    val snap = options["--snapshot"]?.let { resolvedDestination(Path.of(it)) } ?: snapshot(source, staging,
        option("--weights", home.resolve("dev/coach/docs/background/期中分值覆盖表.md")))
    requirePrivateDestination(snap, protected)
    require(!database.startsWith(snap) && !out.startsWith(snap)) { "Snapshot must remain read-only" }
    val seed = contentSeed(snap, options["--weights"]?.let(Path::of) ?: snap.resolve("exam-weights.md"))
    val encoded = canonical(LedgerJson.encodeToJsonElement(ContentSeed.serializer(), seed))
    privateDir(out)
    out.resolve("content.json").writeText(encoded)
    out.resolve("report.md").writeText(seedReport(seed))
    privateDir(database)
    // Share Main.kt's lock: never race the running server's single writer.
    FileChannel.open(database.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        val lock = channel.tryLock() ?: error("Coach server is running; seed an offline directory or stop it first")
        lock.use {
            LedgerStore(database).use { store ->
                val changed = store.seedContent(seed)
                require(store.courseContent(seed.content.course.id) == seed.content)
                println("seed-content ${if(changed) "installed" else "unchanged"}: modules=${seed.content.modules.size} kp=${seed.content.knowledgePoints.size} missingAnchors=${seed.content.knowledgePoints.count { it.anchors.isEmpty() }} weights=${seed.content.course.examWeights.values.sum()} learningRecords=0 sha256=${sha(encoded)}")
            }
        }
    }
}
