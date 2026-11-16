package com.coach.server

import com.coach.ai.BridgeClient
import java.net.URI

/** Explicit isolated-reader selection; the bridge must still report the requested vendor honestly. */
internal object ReaderModel {
    fun environment(env: Map<String, String> = System.getenv()): String {
        val requested = env["COACH_READER_MODEL"] ?: return BridgeClient.CLAUDE
        require(env["COACH_TRAFFIC"] == "test" && env["COACH_MANAGE_SERVICES"] != "1") {
            "Reader model override requires an isolated test instance"
        }
        val bridge = URI(env["COACH_BRIDGE_URL"] ?: "")
        require(bridge.scheme == "http" && bridge.host in setOf("127.0.0.1", "localhost") &&
            bridge.port in 1024..65535 && bridge.port !in setOf(8790, 8791, 18791) &&
            bridge.userInfo == null && bridge.rawQuery == null && bridge.rawFragment == null &&
            bridge.path in setOf("", "/")) { "Reader model override requires a private loopback bridge" }
        require(requested in setOf(BridgeClient.CLAUDE, BridgeClient.CODEX)) { "Unsupported reader model" }
        return requested
    }
}
