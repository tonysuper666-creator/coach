package com.coach.server

import com.coach.domain.Event
import com.coach.domain.str
import kotlinx.serialization.json.JsonObject

/** Shared with the learning mainline: private material uses the first word of the
 * course name (the course code), while sessions and events keep the ledger ID. */
internal fun courseNames(events: List<Event>, course: String): Set<String> {
    val name = events.asReversed().firstNotNullOfOrNull {
        (it.payload["course"] as? JsonObject)?.str("name")?.takeIf(String::isNotBlank)
    }.orEmpty()
    return setOfNotNull(course.takeIf(String::isNotBlank), name.trim().substringBefore(' ').takeIf(String::isNotBlank))
}
