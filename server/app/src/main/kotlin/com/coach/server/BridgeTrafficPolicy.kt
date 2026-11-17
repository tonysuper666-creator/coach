package com.coach.server

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/** Managed previews own a separate loopback bridge; learner traffic keeps 8791. */
internal fun managedBridgePort(env: Map<String, String>): Int {
    val port = (env["BRIDGE_PORT"] ?: "8791").toInt()
    if (env["COACH_TRAFFIC"] != "test") {
        require(port == 8791) { "Managed learner traffic requires bridge port 8791" }
        return port
    }
    val bridge = URI(env.getValue("COACH_BRIDGE_URL"))
    require(port in 1024..65535 && port !in setOf(8790, 8791, 18791, 3790, 19540) &&
        env["COACH_PORT"]?.toIntOrNull() != port && env["COACH_PUBLIC_PORT"]?.toIntOrNull() != port &&
        bridge.scheme == "http" && bridge.host == "127.0.0.1" && bridge.port == port &&
        bridge.rawUserInfo == null && bridge.rawQuery == null && bridge.rawFragment == null &&
        bridge.rawPath in setOf("", "/")) {
        "Managed test traffic requires its own matching loopback bridge port"
    }
    return port
}

/** Only the managed production service may identify its calls as learner traffic. */
internal fun configureBridgeTraffic(env: Map<String,String>, managed: Boolean) {
    val traffic = env["COACH_TRAFFIC"] ?: if (managed) "user" else "test"
    val bridge = URI(env["COACH_BRIDGE_URL"] ?: "http://127.0.0.1:8791")
    require(traffic in setOf("user","test")) { "COACH_TRAFFIC must be user or test" }
    if (managed) managedBridgePort(env)
    if (!managed || traffic == "test") {
        require(traffic == "test" && bridge.port in 1..65535 && bridge.port !in setOf(8790,8791)) {
            "Standalone/test servers require COACH_BRIDGE_URL on their own bridge port; production 8790/8791 are forbidden"
        }
        return
    }
    val productionData = Path.of(System.getProperty("user.home"),"Coach/data").toAbsolutePath().normalize()
    val data = Path.of(env["COACH_DATA_DIR"] ?: productionData.toString()).toAbsolutePath().normalize()
    require((env["COACH_PORT"] ?: "18791") == "18791" && data == productionData) {
        "Only the managed production data directory and port may claim learner traffic; preview copies must use test traffic"
    }
    require(bridge.host in setOf("127.0.0.1","localhost","::1","[::1]") && bridge.port == 8791) {
        "Managed learner traffic must use the local production Coach bridge"
    }
    val home = Path.of(env["COACH_BRIDGE_HOME"] ?: Path.of(System.getProperty("user.home"),"Coach/bridge").toString())
    val key = Path.of(env["COACH_BRIDGE_PRODUCTION_KEY_FILE"] ?: home.resolve("state/production-key").toString())
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val text = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    privateFiles.writeNew(key, text.toByteArray())
    require(Files.readString(key).trim().matches(Regex("[a-f0-9]{64}"))) { "Invalid local production bridge credential" }
}
