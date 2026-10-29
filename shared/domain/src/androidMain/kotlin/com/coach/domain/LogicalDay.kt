package com.coach.domain
import java.time.Instant
import java.time.ZoneId
actual fun logicalDay(epochMillis: Long, zone: String): Long {
    val local = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(zone))
    return (if (local.hour < 4) local.toLocalDate().minusDays(1) else local.toLocalDate()).toEpochDay()
}
