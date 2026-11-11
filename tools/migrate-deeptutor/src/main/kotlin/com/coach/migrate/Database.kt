package com.coach.migrate

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.*

fun readDatabase(file: Path): Map<String, List<JsonObject>> {
    val protected=Path.of(System.getProperty("user.home"),"DeepTutor").toAbsolutePath().normalize()
    require(!file.toRealPath().startsWith(protected)) { "Never open a DeepTutor SQLite connection" }
    // Only staged paths reach this function; ro still allows SQLite sidecars in staging.
    DriverManager.getConnection("jdbc:sqlite:file:${file.toUri().rawPath}?mode=ro").use { db ->
        db.createStatement().use { it.execute("PRAGMA query_only=ON") }
        db.autoCommit = false
        db.createStatement().use { st -> st.executeQuery("PRAGMA integrity_check").use { r ->
            check(r.next() && r.getString(1) == "ok" && !r.next()) { "Snapshot integrity_check failed" }
        } }
        val allowed = if(file.fileName.toString()=="mastery.sqlite3") setOf("mastery_paths","mastery_interactions","mastery_events","mastery_learning_evidence") else setOf("sessions","messages","assessment_attempts")
        val tables = db.createStatement().use { st -> st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { r ->
            buildList { while(r.next()) add(r.getString(1)) }
        } }.filter { it in allowed }
        return tables.associateWith { table -> rows(db, table) }.also { db.rollback() }
    }
}
fun rows(db: Connection, table: String): List<JsonObject> {
    val escaped = table.replace("\"", "\"\"")
    val primaryKeys = db.createStatement().use { st -> st.executeQuery("PRAGMA table_info(\"$escaped\")").use { r ->
        buildList { while(r.next()) if(r.getInt("pk") > 0) add(r.getInt("pk") to r.getString("name")) }.sortedBy { it.first }.map { it.second }
    } }
    require(primaryKeys.isNotEmpty()) { "Table $table has no primary key; define a stable mapping explicitly" }
    return db.createStatement().use { st -> st.executeQuery("SELECT * FROM \"$escaped\"").use { r ->
        buildList { while(r.next()) add(buildJsonObject {
            for(i in 1..r.metaData.columnCount) {
                val value = r.getObject(i)
                put(r.metaData.getColumnName(i), when(value) {
                    null -> JsonNull
                    is Number -> JsonPrimitive(value)
                    is ByteArray -> JsonPrimitive(java.util.Base64.getEncoder().encodeToString(value))
                    else -> JsonPrimitive(value.toString())
                })
            }
            put("_source_pk", primaryKeys.joinToString("|") { key ->
                java.net.URLEncoder.encode(r.getString(key), Charsets.UTF_8)
            })
        }) }
    } }.sortedBy { it.str("_source_pk") }
}
fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
fun parseFile(p: Path): JsonElement = Json.parseToJsonElement(java.nio.file.Files.readString(p))
fun parseText(value: String): JsonElement = if (value.isBlank()) JsonNull else Json.parseToJsonElement(value)
fun legacy(source: String, table: String, row: JsonObject): JsonObject = buildJsonObject {
    put("id", "legacy:$source:$table:${row.str("_source_pk")}")
    put("content_hash", sha(canonical(row)))
    put("source_record", JsonObject(row.filterKeys { it != "_source_pk" }))
}

/** SQLite mappings hash the encoded primary key as well as the retained source columns.
 * Keep that existing identity contract when checking the archive, which omits _source_pk.
 */
internal fun sourceContentHash(id:String, raw:JsonElement):String {
    val sqliteTables=mapOf("mastery.sqlite3" to setOf("mastery_interactions","mastery_events","mastery_learning_evidence"),
        "chat_history.db" to setOf("messages","sessions","assessment_attempts"))
    val parts=id.split(':',limit=4)
    val hashed=if(parts.size==4 && parts[2] in sqliteTables[parts[1]].orEmpty())
        JsonObject(raw.jsonObject+("_source_pk" to JsonPrimitive(parts[3]))) else raw
    return sha(canonical(hashed))
}
