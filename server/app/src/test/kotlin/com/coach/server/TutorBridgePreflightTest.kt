package com.coach.server

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** The bridge explicitly reports JSON null when its daily cap is disabled. */
internal fun requireTutorLiveCapacity(health: JsonObject, required: Int) {
    val remaining = health.getValue("daily").jsonObject["remaining"]
    val unlimited = remaining === JsonNull
    val available = (remaining as? JsonPrimitive)?.longOrNull
    val paused = health.getValue("rate_limit").jsonObject.flag("all_paused")
    require(!paused && (unlimited || (available != null && available >= required))) { "测试额度不足或使用率暂停" }
}

class TutorBridgePreflightTest {
    private fun health(remaining: JsonElement?, paused: Boolean = false) = json(
        "daily" to JsonObject(if (remaining == null) emptyMap() else mapOf("remaining" to remaining)),
        "rate_limit" to json("all_paused" to paused),
    )

    @Test fun unlimitedBridgeAllowsTheTwoCallWalk() {
        requireTutorLiveCapacity(health(JsonNull),2)
    }

    @Test fun unlimitedBridgeStillHonorsPause() {
        assertFailsWith<IllegalArgumentException> { requireTutorLiveCapacity(health(JsonNull,paused=true),2) }
    }

    @Test fun finiteBudgetMustCoverTheRequestedCalls() {
        requireTutorLiveCapacity(health(JsonPrimitive(2)),2)
        for (remaining in listOf(0,1)) {
            assertFailsWith<IllegalArgumentException> { requireTutorLiveCapacity(health(JsonPrimitive(remaining)),2) }
        }
    }

    @Test fun missingOrMalformedBudgetIsNotAnExplicitUnlimitedBudget() {
        for (remaining in listOf(null,JsonPrimitive("unknown"))) {
            assertFailsWith<IllegalArgumentException> { requireTutorLiveCapacity(health(remaining),2) }
        }
    }
}
