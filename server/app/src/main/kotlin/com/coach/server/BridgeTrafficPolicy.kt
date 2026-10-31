package com.coach.server

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** Only the managed production service may identify its calls as learner traffic. */
internal fun configureBridgeTraffic(env: Map<String,String>, managed: Boolean) {
    val traffic = env["COACH_TRAFFIC"] ?: if (managed) "user" else "test"
    val bridge = URI(env["COACH_BRIDGE_URL"] ?: "http://127.0.0.1:8791")
    require(traffic in setOf("user","test")) { "COACH_TRAFFIC must be user or test" }
    if (!managed || traffic == "test") {
        require(traffic == "test" && bridge.port in 1..65535 && bridge.port !in setOf(8790,8791)) {
            "Standalone/test servers require COACH_BRIDGE_URL on their own bridge port; production 8790/8791 are forbidden"
        }
        return
    }
    require(bridge.host in setOf("127.0.0.1","localhost","::1","[::1]") && bridge.port == 8791) {
        "Managed learner traffic must use the local production Coach bridge"
    }
    val home = Path.of(env["COACH_BRIDGE_HOME"] ?: Path.of(System.getProperty("user.home"),"Coach/bridge").toString())
    val key = Path.of(env["COACH_BRIDGE_PRODUCTION_KEY_FILE"] ?: home.resolve("state/production-key").toString())
    Files.createDirectories(key.parent)
    if (!Files.exists(key)) {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val text = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        try {
            Files.newByteChannel(key,setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),mode).use { channel ->
                val buffer=java.nio.ByteBuffer.wrap(text.toByteArray())
                while(buffer.hasRemaining()) channel.write(buffer)
            }
        } catch (_: java.nio.file.FileAlreadyExistsException) { /* bridge and server may start together */ }
    }
    require(Files.readString(key).trim().matches(Regex("[a-f0-9]{64}"))) { "Invalid local production bridge credential" }
    Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"))
}
