package com.coach.server

import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.*

class ServiceLogsTest {
    @Test fun continuousStreamMovesAtMidnightAndPrunesOnlyExpiredServiceLogs() {
        val directory = Files.createTempDirectory("coach-log-")
        var day = LocalDate.of(2026, 10, 30)
        try {
            Files.writeString(directory.resolve("bridge-2026-10-16.log"), "expired")
            Files.writeString(directory.resolve("bridge-2026-10-17.log"), "last retained day")
            Files.writeString(directory.resolve("bridge-manual.log"), "manual")
            Files.writeString(directory.resolve("other-2026-10-16.log"), "other owner")
            ServiceLog(directory, "bridge") { day }.use { stream ->
                stream.write("before midnight\n".toByteArray())
                assertFalse(Files.exists(directory.resolve("bridge-2026-10-16.log")))
                assertTrue(Files.exists(directory.resolve("bridge-2026-10-17.log")))
                day = day.plusDays(1)
                stream.write("after midnight\n".toByteArray())
            }
            assertEquals("before midnight\n", Files.readString(directory.resolve("bridge-2026-10-30.log")))
            assertEquals("after midnight\n", Files.readString(directory.resolve("bridge-2026-10-31.log")))
            assertFalse(Files.exists(directory.resolve("bridge-2026-10-17.log")))
            assertEquals("manual", Files.readString(directory.resolve("bridge-manual.log")))
            assertEquals("other owner", Files.readString(directory.resolve("other-2026-10-16.log")))
        } finally { directory.toFile().deleteRecursively() }
    }
}
