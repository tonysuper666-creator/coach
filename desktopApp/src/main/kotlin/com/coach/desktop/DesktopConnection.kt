package com.coach.desktop

import com.coach.client.PairedCredentials
import com.coach.client.validateCoachBaseUrl
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Properties

internal fun desktopServerUrl(address: String): String {
    val value = address.trim().trimEnd('/')
    val url = if ("://" in value) value else "http://$value"
    validateCoachBaseUrl(url)
    return java.net.URI(url).let {
        require(!it.host.isNullOrBlank() && it.port in -1..65535 && it.port != 0) { "Invalid Coach server address" }
        val port = if (it.port == -1) { if ("://" !in value) 18791 else if (it.scheme == "https") 443 else 80 } else it.port
        java.net.URI(it.scheme.lowercase(), null, it.host.lowercase(), port, null, null, null).toASCIIString()
    }
}

/** Runtime configuration: the installed Windows application needs no launcher environment. */
internal class DesktopConnectionSettings(
    val home: Path = Path.of(System.getProperty("user.home")),
    private val environment: (String) -> String? = System::getenv,
    private val mac: Boolean = System.getProperty("os.name").startsWith("Mac"),
) {
    private val configFile = home.resolve("Coach/config/local.properties")
    val clientDirectory: Path = environment("COACH_CLIENT_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)
        ?: home.resolve("Coach/client")
    private val localConnection = DesktopCredentials(clientDirectory)

    private fun properties() = Properties().apply {
        if (Files.exists(configFile)) Files.newInputStream(configFile).use { load(it) }
    }

    fun serverUrl(): String? {
        environment("COACH_URL")?.takeIf(String::isNotBlank)?.let { return desktopServerUrl(it) }
        localConnection.savedAddress()?.let { return it }
        val config = properties()
        config.getProperty("coach.desktopUrl")?.takeIf(String::isNotBlank)?.let { return desktopServerUrl(it) }
        if (mac) return desktopServerUrl("http://127.0.0.1:${config.getProperty("coach.port", "18791").trim()}")
        config.getProperty("coach.tailHost")?.takeIf(String::isNotBlank)?.let { host ->
            val port = config.getProperty("coach.port", "18791").trim()
            return desktopServerUrl("http://${host.trim()}:$port")
        }
        return null
    }

    fun saveAddress(url: String) {
        // Shared local.properties is read-only: other tools read its original UTF-8 lines.
        if (!environment("COACH_URL").isNullOrBlank()) return
        localConnection.saveAddress(url)
    }

    /** Only the same-Mac loopback client may bootstrap from the server's native desktop identity. */
    fun nativeCredentials(url: String): PairedCredentials? {
        val explicit = environment("COACH_TOKEN_FILE")?.takeIf(String::isNotBlank)
        if (explicit == null) {
            val target = java.net.URI(url)
            val nativePort = properties().getProperty("coach.port", "18791").trim().toInt()
            if (!(mac && target.scheme == "http" && target.port == nativePort &&
                    target.host in setOf("127.0.0.1", "localhost", "::1", "[::1]"))) return null
        }
        val path = explicit?.let(Path::of) ?: home.resolve("Coach/data/device-token")
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        val token = Files.readString(path).trim().takeIf(String::isNotEmpty) ?: return null
        return PairedCredentials(environment("COACH_DEVICE_ID")?.takeIf(String::isNotBlank) ?: "desktop", token)
    }
}
