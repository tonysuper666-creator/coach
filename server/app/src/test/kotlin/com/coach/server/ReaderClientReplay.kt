package com.coach.server

import com.coach.client.CourseHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in synthetic HTTP replay using the same CIO client as Android and Mac. Never loads user data. */
fun main(args: Array<String>): Unit = runBlocking {
    require(args.size == 2)
    val input = Json.parseToJsonElement(Files.readString(Path.of(args[0]))).jsonObject
    fun field(name: String) = input.getValue(name).jsonPrimitive.content
    val uri = URI(field("base"))
    require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 && uri.port !in setOf(8790, 8791))
    val client = CourseHttp(field("base"), "desktop", field("token"), field("course"))
    try {
        val reply = client.explainSelection(1, input.getValue("body").jsonObject)
        check(reply.error == null) { "Synthetic reader client did not receive a server answer" }
        Files.writeString(Path.of(args[1]), checkNotNull(reply.body).toString())
    } finally { client.close() }
}
