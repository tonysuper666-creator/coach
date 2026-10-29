package com.coach.domain

/** Synthetic benchmark inputs only. Included by JVM tests and Android instrumentation, never release builds. */
object ReplayBenchmark {
    fun events(): List<Event> {
        val result=ArrayList<Event>(50000)
        fun e(type: String, p: kotlinx.serialization.json.JsonObject, at: Long) {
            val n=result.size.toLong()+1
            result.add(Event(n,"benchmark-$n","synthetic",type,recordedAt=at+1000,occurredAt=at,deviceId="benchmark",actor="LEARNER",payload=p))
        }
        repeat(12500) { i ->
            val at=1793188800000L+(i/100)*86400000L
            e("question_posed",json("attemptId" to "$i","questionId" to "$i","fingerprint" to "$i","kpIds" to listOf("kp${i%100}"),"purpose" to "CLOSED_BOOK","policyVersion" to 1,"format" to "SHORT_ANSWER","keyState" to "VERIFIED"),at)
            e("answer_submitted",json("attemptId" to "$i","answer" to "synthetic"),at)
            e("attempt_graded",json("attemptId" to "$i","score" to 9,"max" to 10,"status" to "AI_UNREVIEWED"),at)
            e("grade_reviewed",json("attemptId" to "$i","decision" to "CONFIRM"),at)
        }
        return result
    }
    fun measure(): Pair<Long,Projection> {
        val events=events()
        val start=System.nanoTime()
        val state=project(events)
        return (System.nanoTime()-start)/1000000 to state
    }
}
