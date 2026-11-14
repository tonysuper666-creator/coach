package com.coach.desktop.reader

import java.nio.file.Files
import java.nio.file.Path

/** Mac-only synthetic server. An explicit private control directory owns its readiness/stop files. */
object CitationFixtureHost {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getProperty("os.name").startsWith("Mac")) { "Run the source sandbox on Mac; Windows is the HTTP client" }
        val control = Path.of(System.getProperty("coach.citation.control") ?: error("Explicit control directory required"))
        Files.createDirectories(control)
        check(!Files.exists(control.resolve("ready.properties"))) { "Use a fresh control directory" }
        SyntheticCitationServer(System.getProperty("coach.citation.bind", "127.0.0.1")).use { fixture ->
            val port = java.net.URI(fixture.base).port
            Files.writeString(control.resolve("ready.properties"), "port=$port\npdfSha256=${fixture.pdfHash}\n")
            println("SYNTHETIC_MAC_READY port=$port physicalPages=3")
            val deadline = System.nanoTime() + 900_000_000_000L
            while (!Files.exists(control.resolve("stop")) && System.nanoTime() < deadline) Thread.sleep(100)
            Files.writeString(control.resolve("server-receipt.txt"), "bridgeRequests=${fixture.bridgeRequests.get()}\nphysicalPages=3\nclosed=true\n")
            println("SYNTHETIC_MAC_STOP bridgeRequests=${fixture.bridgeRequests.get()}")
        }
    }
}
