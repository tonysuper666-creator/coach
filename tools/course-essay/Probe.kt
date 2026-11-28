package com.coach.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun main() {
    val cases = Json.parseToJsonElement(System.`in`.bufferedReader(Charsets.UTF_8).readText()).jsonArray
    check(cases.size == 4)
    var verified = 0
    for (sample in cases) {
        val data = sample.jsonObject
        val label = data.getValue("case").jsonPrimitive.content
        val pages = data.getValue("pages").jsonObject.map { (number, text) ->
            CourseEssayFramework.Page("econ", "Economics", "CORE_TEXTBOOK", number.toInt(), text.jsonPrimitive.content, 1)
        }
        val points = CourseEssayFramework.extract(data.getValue("topic").jsonPrimitive.content, pages)
        check(points.isNotEmpty()) { "$label produced no rubric points" }
        val rows = CourseEssayFramework.judge(points, data.getValue("answer").jsonPrimitive.content)
        check(rows.size == points.size)
        for (row in rows) {
            val point = row.point
            val source = pages.single { it.bookId == point.bookId && it.page == point.page }
            check(point.quoteAnchor.isNotBlank() && source.text.contains(point.quoteAnchor)) {
                "$label: missing source anchor at PDF page ${point.page}"
            }
            check(point.statement == point.quoteAnchor.replace(Regex("""\s+"""), " "))
            check(point.statement.endsWith('.') || point.statement.endsWith('。'))
            verified++
            println("$label ${point.id}: PDF page ${point.page}, local usage ${row.usage}")
        }
    }
    println("${cases.size} answers, $verified source anchors verified")
}
