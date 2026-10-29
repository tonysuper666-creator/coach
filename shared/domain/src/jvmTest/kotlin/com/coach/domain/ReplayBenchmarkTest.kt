package com.coach.domain
import kotlin.test.*
class ReplayBenchmarkTest {
    @Test fun fiftyThousandEventReplay() {
        val (ms,state)=ReplayBenchmark.measure()
        assertEquals(50000,state.lastSeq)
        assertTrue(state.mastery.values.all { it.passed && it.solid })
        println("JVM coldReplay events=50000 ms=$ms passed=${state.mastery.values.count { it.passed }}")
    }
}
