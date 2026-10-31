package com.coach.migrate

import com.coach.domain.Event
import com.coach.domain.LedgerJson
import com.coach.store.LedgerStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.*

private const val COURSE_SOURCE = "data/user/workspace/courses/courses.json"
private const val MASTERY_SOURCE = "data/user/workspace/learning/mastery/mastery.sqlite3"
private const val TEXTBOOK_DIR = "library/ECNM10119-健康经济学/教材"
private val pageMarker = Regex("<!-- PDF 第 (\\d+) 页 · 书第 (\\d+) 页 -->")

data class TextbookPage(val chapter: Int, val pdfPage: Int, val bookPage: Int, val text: String, val docId: String)
data class SeedAnchor(val docId: String, val pdfPage: Int, val bookPage: Int)
data class SeedModule(val id: String, val name: String, val ordinal: Int)
data class SeedKnowledgePoint(
    val id: String, val name: String, val moduleId: String, val ordinal: Int,
    val examFormats: List<String>, val prerequisites: List<String>, val anchor: SeedAnchor
)
data class SeedSourceDoc(val id: String, val title: String, val hash: String, val bytes: ByteArray, val pages: List<TextbookPage>)
data class SeedContent(
    val courseId: String, val courseName: String, val modules: List<SeedModule>,
    val knowledgePoints: List<SeedKnowledgePoint>, val weights: Map<String, Int>,
    val sourceDocs: List<SeedSourceDoc>, val manifest: JsonObject, val manifestBytes: ByteArray, val sha256: String
)
data class SeedWriteResult(val eventsAdded: Int, val idempotent: Boolean)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun canonicalSeed(value: JsonElement): String {
    fun sorted(v: JsonElement): JsonElement = when(v) {
        is JsonObject -> JsonObject(v.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(v.map(::sorted))
        else -> v
    }
    return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), sorted(value)) + "\n"
}
private fun JsonObject.strSeed(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.arraySeed(key: String) = (this[key] as? JsonArray)?.map { it.jsonObject }.orEmpty()

/** Builds a whitelist-only course seed. No mastery, answer, review, journal, or chat data enters the result. */
fun prepareSeed(snapshot: Path): SeedContent {
    val root=snapshot.toRealPath()
    require(root.resolve("copy-stats.json").isRegularFile()) { "Use a captured staging snapshot" }
    require(root.resolve("data-before.stat").readText()==root.resolve("data-after.stat").readText()) { "Snapshot data inventory changed during capture" }
    require(root.resolve("data-before.ls").readText()==root.resolve("data-after.ls").readText()) { "Snapshot ls evidence changed during capture" }
    val copyStats=parseFile(root.resolve("copy-stats.json")).jsonObject
    require(copyStats["data_unchanged"]==JsonPrimitive(true)) { "Snapshot did not prove source data unchanged" }

    val courses=parseFile(root.resolve(COURSE_SOURCE)).jsonObject.arraySeed("courses")
        .filter { it.strSeed("id").startsWith("course_") && it.strSeed("name").contains("ECNM10119") }
    require(courses.size==1) { "Expected exactly one ECNM10119 course in snapshot" }
    val course=courses.single()
    val courseId=course.strSeed("id")
    val mastery=readDatabase(root.resolve(MASTERY_SOURCE)).getValue("mastery_paths")
    val gates=parseFile(root.resolve("tools/patch_backups/gate_types_before.json")).jsonObject
    val ranked=mastery.map { row ->
        val state=parseText(row.str("state_json")).jsonObject
        row to state.arraySeed("modules").sumOf { it.arraySeed("knowledge_points").count { kp -> kp.strSeed("id") in gates } }
    }.sortedByDescending { it.second }
    require(ranked.isNotEmpty() && ranked.first().second>0 && ranked.count { it.second==ranked.first().second }==1) {
        "Cannot uniquely identify the 49-KP course path in this snapshot"
    }
    val state=parseText(ranked.first().first.str("state_json")).jsonObject
    val rawModules=state.arraySeed("modules")
    val modules=rawModules.mapIndexed { index,m -> SeedModule(m.strSeed("id"),m.strSeed("name"),index) }
    val rawKps=rawModules.flatMap { m -> m.arraySeed("knowledge_points").map { it to m.strSeed("id") } }
    require(rawKps.size==49) { "Expected 49 knowledge points; found ${rawKps.size}" }

    val textbookRoot=root.resolve(TEXTBOOK_DIR)
    require(textbookRoot.isDirectory()) { "Snapshot textbook pages are missing" }
    val sourceDocs=Files.list(textbookRoot).use { stream -> stream.filter { it.isRegularFile() && it.extension=="md" }.sorted().toList() }
        .map { path ->
            val bytes=Files.readAllBytes(path)
            val sourceHash=sha256(bytes)
            val stableDocId="textbook:${sha256(path.fileName.toString().toByteArray()).take(24)}"
            val text=bytes.toString(Charsets.UTF_8)
            val markers=pageMarker.findAll(text).toList()
            require(markers.isNotEmpty()) { "Textbook file has no page anchors: ${path.fileName}" }
            val chapter=path.fileName.toString().substringBefore('-').toIntOrNull()
                ?: error("Cannot read textbook chapter number: ${path.fileName}")
            val pages=markers.mapIndexed { index,match ->
                val end=markers.getOrNull(index+1)?.range?.first ?: text.length
                val pageText=text.substring(match.range.last+1,end).trim()
                TextbookPage(chapter,match.groupValues[1].toInt(),match.groupValues[2].toInt(),pageText,stableDocId)
            }
            SeedSourceDoc(stableDocId,path.fileName.toString(),sourceHash,bytes,pages)
        }
    val pages=sourceDocs.flatMap { it.pages }
    val evidence=state["lesson_evidence"] as? JsonObject ?: JsonObject(emptyMap())
    val knowledgePoints=rawKps.mapIndexed { index,(kp,moduleId) ->
        val id=kp.strSeed("id")
        val refs=evidence[id]?.jsonObject?.arraySeed("sources").orEmpty().mapNotNull { source ->
            val ref=source["ref"] as? JsonObject ?: return@mapNotNull null
            val key=ref.strSeed("document_key")
            if("Bhattacharya-Hyde-Tu-2014-Health-Economics.pdf" !in key) return@mapNotNull null
            val label=ref.strSeed("page_label").takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            val pdf=ref["page_index"]?.jsonPrimitive?.intOrNull
            val match=pages.firstOrNull { page -> label!=null && page.bookPage==label }
                ?: pages.firstOrNull { page -> pdf!=null && page.pdfPage in setOf(pdf,pdf+1) }
            match?.let { SeedAnchor(it.docId,it.pdfPage,it.bookPage) }
        }
        val chapter=Regex("Ch([0-9]+)").find(kp.strSeed("name"))?.groupValues?.get(1)?.toIntOrNull()
            ?: when {
                listOf("Coinsurance","copayment","deductible").any { it in kp.strSeed("name") } -> 7
                "hospital" in kp.strSeed("name").lowercase() -> 6
                "physician" in kp.strSeed("name").lowercase() || "doctor" in kp.strSeed("name").lowercase() -> 5
                else -> Regex("W([1-6])").find(kp.strSeed("name"))?.groupValues?.get(1)?.toIntOrNull()?.let { 2*it-1 }
            } ?: error("Knowledge point has no textbook chapter reference: ${kp.strSeed("name")}")
        val anchor=refs.firstOrNull() ?: pages.firstOrNull { it.chapter==chapter }?.let { SeedAnchor(it.docId,it.pdfPage,it.bookPage) }
            ?: error("No textbook page found for ${kp.strSeed("name")}")
        val prereqs=(kp["prerequisite_ids"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        SeedKnowledgePoint(id,kp.strSeed("name"),moduleId,index,if("【Essay】" in kp.strSeed("name")) listOf("ESSAY") else emptyList(),prereqs,anchor)
    }
    require(knowledgePoints.map { it.id }.toSet().size==49) { "Knowledge point IDs are not unique" }

    val byId=knowledgePoints.associateBy { it.id }
    fun one(label:String,predicate:(SeedKnowledgePoint)->Boolean):SeedKnowledgePoint {
        val found=knowledgePoints.filter(predicate)
        require(found.size==1) { "Exam weight source '$label' matched ${found.size} knowledge points" }
        return found.single()
    }
    val rs=knowledgePoints.filter { it.name.contains("【W5·Ch9】") || it.name.contains("【W5·slides】") && it.moduleId==modules[4].id }
    require(rs.size==6) { "The 30-point Rothschild–Stiglitz row must map to six KP; found ${rs.size}" }
    val weights=linkedMapOf<String,Int>()
    rs.forEach { weights[it.id]=5 }
    val rand=one("15-point RAND/Oregon essay") { it.name.contains("RAND HIE 设计") && it.name.contains("Essay") }
    val causal=one("15-point causal identification") { it.name.contains("为什么普通调查估不准需求曲线") }
    weights[rand.id]=(weights[rand.id] ?: 0)+8
    weights[causal.id]=(weights[causal.id] ?: 0)+7
    fun add(label:String,value:Int,predicate:(SeedKnowledgePoint)->Boolean) {
        val kp=one(label,predicate);weights[kp.id]=(weights[kp.id] ?: 0)+value
    }
    add("13-point local hospital structure",13) { it.name.contains("本地医院市场结构") }
    add("6-point US hospital trends",6) { it.name.contains("美国医院趋势") }
    add("6-point HHI",6) { it.name.contains("HHI=Σ") }
    add("6-point CRHIE design",6) { it.name.contains("CRHIE") }
    add("6-point NPV",6) { it.name.contains("NPV=Σ") }
    add("6-point asymmetric-information predictions",6) { it.name.contains("三个预测") }
    add("6-point allostatic load",6) { it.moduleId==modules[10].id && it.name.contains("allostatic load") && it.name.contains("productive time") }
    add("6-point moral-hazard benefits",6) { it.name.contains("两个“好处”") }
    require(weights.values.sum()==100) { "Exam weight mapping must total 100; found ${weights.values.sum()}" }
    require(weights.keys.all { it in byId })

    val manifest=buildJsonObject {
        put("schema_version",1);put("course",buildJsonObject { put("id",courseId);put("name",course.strSeed("name")) })
        put("modules",JsonArray(modules.map { m -> buildJsonObject { put("id",m.id);put("name",m.name);put("ordinal",m.ordinal) } }))
        put("knowledge_points",JsonArray(knowledgePoints.map { kp -> buildJsonObject {
            put("id",kp.id);put("name",kp.name);put("module_id",kp.moduleId);put("ordinal",kp.ordinal)
            put("exam_formats",JsonArray(kp.examFormats.map(::JsonPrimitive)));put("prerequisites",JsonArray(kp.prerequisites.map(::JsonPrimitive)))
            put("source_anchor",buildJsonObject { put("doc_id",kp.anchor.docId);put("pdf_page",kp.anchor.pdfPage);put("book_page",kp.anchor.bookPage) })
        } }))
        put("exam_weights",JsonObject(weights.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("source_docs",JsonArray(sourceDocs.map { d -> buildJsonObject {
            put("id",d.id);put("title",d.title);put("sha256",d.hash);put("pages",d.pages.size)
        } }))
    }
    val manifestBytes=canonicalSeed(manifest).toByteArray(Charsets.UTF_8)
    return SeedContent(courseId,course.strSeed("name"),modules,knowledgePoints,weights,sourceDocs,manifest,manifestBytes,sha256(manifestBytes))
}

private fun insertEvent(db:Connection, course:String, type:String, payload:JsonObject, idemKey:String, contentHash:String, now:Long):Boolean {
    db.prepareStatement("SELECT event_json FROM events WHERE course_id=? AND idem_key=?").use { ps ->
        ps.setString(1,course);ps.setString(2,idemKey);ps.executeQuery().use { rs ->
            if(rs.next()) {
                val old=LedgerJson.decodeFromString<Event>(rs.getString(1))
                require(old.type==type && canonicalSeed(old.payload)==canonicalSeed(payload)) { "Idempotency key already exists with different seed content" }
                return false
            }
        }
    }
    val seq=db.createStatement().use { st -> st.executeQuery("SELECT coalesce(max(seq),0)+1 FROM events").use { rs -> rs.next();rs.getLong(1) } }
    val eventId="seed:${contentHash.take(32)}:${type}"
    val event=Event(seq,eventId,course,type,recordedAt=now,occurredAt=now,deviceId="seed-content",actor="MIGRATION",idemKey=idemKey,payload=payload)
    db.prepareStatement("INSERT INTO events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,command_id,effect_index,idem_key,payload_sha256,thread_id,payload,event_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { ps ->
        ps.setLong(1,event.seq);ps.setString(2,event.id);ps.setString(3,event.courseId);ps.setString(4,event.type);ps.setInt(5,event.schema)
        ps.setLong(6,event.recordedAt);ps.setLong(7,event.occurredAt);ps.setString(8,event.deviceId);ps.setString(9,event.actor)
        ps.setString(10,null);ps.setInt(11,0);ps.setString(12,event.idemKey);ps.setString(13,contentHash);ps.setString(14,null)
        ps.setString(15,payload.toString());ps.setString(16,LedgerJson.encodeToString(event));ps.executeUpdate()
    }
    return true
}

private fun writeAtomic(path:Path,bytes:ByteArray) {
    Files.createDirectories(path.parent)
    if(path.exists()) { require(sha256(Files.readAllBytes(path))==sha256(bytes)) { "Existing content-addressed file is corrupt: ${path.fileName}" };return }
    val temp=Files.createTempFile(path.parent,".seed-",".tmp")
    try { Files.write(temp,bytes);try { Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE) } catch(_:AtomicMoveNotSupportedException) { Files.move(temp,path) } }
    finally { Files.deleteIfExists(temp) }
}
private fun replaceAtomic(path:Path,bytes:ByteArray) {
    Files.createDirectories(path.parent)
    val temp=Files.createTempFile(path.parent,".seed-",".tmp")
    try {
        Files.write(temp,bytes)
        try { Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch(_:AtomicMoveNotSupportedException) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

fun seedContent(seed:SeedContent,dataDir:Path,corpusDir:Path):SeedWriteResult {
    Files.createDirectories(dataDir);Files.createDirectories(corpusDir)
    val lockPath=dataDir.resolve("server.lock")
    FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE).use { channel ->
        val lock:FileLock=try { channel.tryLock() ?: error("Coach server owns this data directory; stop it before seeding") }
            catch(e:java.nio.channels.OverlappingFileLockException) { error("Coach server owns this data directory; stop it before seeding") }
        lock.use {
            // Let the canonical store create/migrate its schema, then perform one content transaction.
            LedgerStore(dataDir).use { }
            val dbPath=dataDir.resolve("coach.sqlite")
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { db ->
                db.createStatement().use { it.execute("PRAGMA busy_timeout=5000");it.execute("PRAGMA foreign_keys=ON") }
                db.autoCommit=false
                try {
                    val blobs=dataDir.resolve("blobs")
                    for(doc in seed.sourceDocs) {
                        writeAtomic(blobs.resolve(doc.hash),doc.bytes)
                        db.prepareStatement("INSERT INTO blobs(sha256,size,mime,path) VALUES(?,?,?,?) ON CONFLICT(sha256) DO UPDATE SET size=excluded.size,mime=excluded.mime,path=excluded.path").use { ps ->
                            ps.setString(1,doc.hash);ps.setLong(2,doc.bytes.size.toLong());ps.setString(3,"text/markdown; charset=utf-8");ps.setString(4,doc.hash);ps.executeUpdate()
                        }
                        db.prepareStatement("INSERT INTO source_docs(doc_id,course_id,kind,title,blob_sha256,book_page_offset,tutor_visible) VALUES(?,?,?,?,?,?,1) ON CONFLICT(doc_id) DO UPDATE SET course_id=excluded.course_id,kind=excluded.kind,title=excluded.title,blob_sha256=excluded.blob_sha256,tutor_visible=1").use { ps ->
                            ps.setString(1,doc.id);ps.setString(2,seed.courseId);ps.setString(3,"textbook");ps.setString(4,doc.title);ps.setString(5,doc.hash);ps.setInt(6,0);ps.executeUpdate()
                        }
                        db.prepareStatement("DELETE FROM source_pages WHERE doc_id=?").use { it.setString(1,doc.id);it.executeUpdate() }
                        for(page in doc.pages) {
                            val pageHash=sha256(page.text.toByteArray(Charsets.UTF_8))
                            db.prepareStatement("INSERT INTO source_pages(doc_id,pdf_page,book_page,text,text_sha256) VALUES(?,?,?,?,?) ON CONFLICT(doc_id,pdf_page) DO UPDATE SET book_page=excluded.book_page,text=excluded.text,text_sha256=excluded.text_sha256").use { ps ->
                                ps.setString(1,doc.id);ps.setInt(2,page.pdfPage);ps.setInt(3,page.bookPage);ps.setString(4,page.text);ps.setString(5,pageHash);ps.executeUpdate()
                            }
                        }
                        replaceAtomic(corpusDir.resolve("textbooks").resolve(seed.courseId).resolve(doc.title),doc.bytes)
                    }
                    val outlinePayload=buildJsonObject {
                        put("course",buildJsonObject { put("id",seed.courseId);put("name",seed.courseName) })
                        put("modules",JsonArray(seed.modules.map { m -> buildJsonObject { put("id",m.id);put("name",m.name);put("ordinal",m.ordinal) } }))
                        put("knowledgePoints",JsonArray(seed.knowledgePoints.map { kp -> buildJsonObject {
                            put("id",kp.id);put("name",kp.name);put("moduleId",kp.moduleId);put("ordinal",kp.ordinal)
                            put("examFormats",JsonArray(kp.examFormats.map(::JsonPrimitive)));put("prerequisiteIds",JsonArray(kp.prerequisites.map(::JsonPrimitive)))
                            put("sourceAnchor",buildJsonObject { put("docId",kp.anchor.docId);put("pdfPage",kp.anchor.pdfPage);put("bookPage",kp.anchor.bookPage) })
                        } }))
                        put("ops",JsonArray(seed.knowledgePoints.map { kp -> buildJsonObject { put("op","add");put("kpId",kp.id);put("title",kp.name);put("conceptIds",JsonArray(listOf(JsonPrimitive(kp.id))));put("prereqs",JsonArray(kp.prerequisites.map(::JsonPrimitive))) } }))
                    }
                    val outlineHash=sha256(canonicalSeed(outlinePayload).toByteArray())
                    var added=0
                    if(insertEvent(db,seed.courseId,"outline_revised",outlinePayload,"seed-content:${seed.courseId}:outline:$outlineHash",outlineHash,System.currentTimeMillis())) added++
                    val weightsPayload=buildJsonObject { put("weights",JsonObject(seed.weights.toSortedMap().mapValues { JsonPrimitive(it.value) })) }
                    val weightsHash=sha256(canonicalSeed(weightsPayload).toByteArray())
                    if(insertEvent(db,seed.courseId,"exam_weights_set",weightsPayload,"seed-content:${seed.courseId}:exam-weights:$weightsHash",weightsHash,System.currentTimeMillis())) added++
                    for(kp in seed.knowledgePoints) {
                        val anchors=JsonArray(listOf(buildJsonObject {
                            put("docId",kp.anchor.docId);put("pdfPage",kp.anchor.pdfPage);put("bookPage",kp.anchor.bookPage)
                        }))
                        db.prepareStatement("INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id) VALUES(?,?,?,?,?,?,?,?,?,0,?) ON CONFLICT(kp_id) DO UPDATE SET course_id=excluded.course_id,module_id=excluded.module_id,ord=excluded.ord,title=excluded.title,kind=excluded.kind,exam_formats=excluded.exam_formats,prereqs=excluded.prereqs,anchors=excluded.anchors,retired=0,legacy_id=excluded.legacy_id").use { ps ->
                            ps.setString(1,kp.id);ps.setString(2,seed.courseId);ps.setString(3,kp.moduleId);ps.setInt(4,kp.ordinal);ps.setString(5,kp.name)
                            ps.setString(6,"knowledge_point");ps.setString(7,canonicalSeed(JsonArray(kp.examFormats.map(::JsonPrimitive))))
                            ps.setString(8,canonicalSeed(JsonArray(kp.prerequisites.map(::JsonPrimitive))));ps.setString(9,canonicalSeed(anchors));ps.setString(10,"deeptutor:${kp.id}");ps.executeUpdate()
                        }
                    }
                    db.prepareStatement("DELETE FROM projection_cache WHERE course_id=?").use { it.setString(1,seed.courseId);it.executeUpdate() }
                    db.commit()
                    // Course-only teaching packs for both Android and desktop. Never copy old learning turns.
                    val packs=corpusDir.resolve("packs").resolve(seed.courseId)
                    for(kp in seed.knowledgePoints) {
                        val chapters=Regex("Ch(\\d+)(?:[–-](\\d+))?").findAll(kp.name).flatMap { match ->
                            val first=match.groupValues[1].toInt()
                            val last=match.groupValues[2].toIntOrNull() ?: first
                            (first..last).asSequence()
                        }.toSet()
                        val docs=seed.sourceDocs.filter { doc -> doc.pages.any { it.chapter in chapters } }
                            .ifEmpty { seed.sourceDocs.filter { it.id==kp.anchor.docId } }
                        val pack=buildString {
                            appendLine("Knowledge point: ${kp.name}")
                            appendLine("Reference: textbook book p.${kp.anchor.bookPage} (PDF p.${kp.anchor.pdfPage}).")
                            docs.forEach { doc ->
                                appendLine("Source: ${doc.title}")
                                doc.pages.forEach { page -> appendLine("[Book p.${page.bookPage}; PDF p.${page.pdfPage}]");appendLine(page.text) }
                            }
                        }
                        replaceAtomic(packs.resolve("${kp.id}.md"),pack.toByteArray(Charsets.UTF_8))
                    }
                    val manifestPath=corpusDir.resolve("seed-content-manifest.json")
                    replaceAtomic(manifestPath,seed.manifestBytes)
                    return SeedWriteResult(added,added==0)
                } catch(e:Throwable) { db.rollback();throw e } finally { db.autoCommit=true }
            }
        }
    }
}
