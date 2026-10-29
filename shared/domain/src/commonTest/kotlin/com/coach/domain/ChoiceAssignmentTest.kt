package com.coach.domain

import kotlin.random.Random
import kotlin.test.*

class ChoiceAssignmentTest {
    private fun choice(i: Int) = ChoiceKey((0..3).map { Option("$i:$it","Option $it") }, "$i:${i % 4}")
    @Test fun groupsAndBoundariesHoldAcrossRandomOrders() {
        repeat(100) { seed ->
            val rng = Random(seed)
            var previous: String? = null
            val counts = CHOICE_LETTERS.associateWith { 0 }.toMutableMap()
            (0 until 1000).chunked(3).forEach { ids ->
                val questions = ids.map { choice(it).let { q -> q.copy(options=q.options.shuffled(rng)) } }
                val maps = assignLetters(questions, previous, rng)
                val correct = maps.zip(questions) { map,q ->
                    assertTrue(validLetterMap(q.options,map))
                    map.getValue(q.correctOptionId)
                }
                assertEquals(correct.size,correct.distinct().size)
                assertNotEquals(previous,correct.first())
                previous = correct.last()
                correct.forEach { counts[it]=counts.getValue(it)+1 }
            }
            // Each letter's share stays within five percentage points of the 25% mean.
            assertTrue(counts.values.all { it in 200..300 }, "seed=$seed counts=$counts")
            if (seed == 0) println("ASSIGNMENT questions=1000 distribution=$counts groupsDistinct=true boundariesDifferent=true")
        }
    }
    @Test fun fingerprintIgnoresIdsOrderAndFormattingButDetectsContentChanges() {
        val original=choice(0).options
        val equivalent=original.reversed().map { Option("new-${it.optionId}","  ${it.text.uppercase()}  ") }
        assertEquals(questionFingerprint(" Ｓｔｅｍ  here ",original),questionFingerprint("stem here",equivalent))
        assertNotEquals(questionFingerprint("stem",original),questionFingerprint("stem",original.dropLast(1)+Option("x","changed")))
    }
    @Test fun sameSeedReplaysAndDifferentSeedsCanChangeLetters() {
        val group=(0..2).map(::choice)
        assertEquals(assignLetters(group,"A",Random(7)),assignLetters(group,"A",Random(7)))
        assertTrue((0..20).map { assignLetters(group,"A",Random(it)) }.distinct().size > 1)
        assertFailsWith<IllegalArgumentException> { assignLetters((0..4).map(::choice),null,Random(1)) }
    }
}

class FiveOptionAssignmentTest {
    @Test fun legacyFiveOptionsAndMixedGroupsKeepEveryOptionAndUseAllEligibleLetters() {
        val four=ChoiceKey((0..3).map { Option("four:$it","Text $it") },"four:2")
        val five=ChoiceKey((0..4).map { Option("five:$it","Text $it") },"five:4")
        val rng=Random(1076)
        var previous: String?=null
        val seen=mutableSetOf<String>()
        repeat(1000) {
            val group=listOf(four,five,five)
            val maps=assignLetters(group,previous,rng)
            val correct=maps.zip(group) { map,key ->
                assertTrue(validLetterMap(key.options,map));map.getValue(key.correctOptionId)
            }
            assertEquals(3,correct.distinct().size);assertNotEquals(previous,correct.first())
            previous=correct.last();seen.add(maps[1].getValue(five.correctOptionId))
        }
        assertEquals(optionLetters(5).toSet(),seen)
        assertEquals(5,assignLetters(List(5) { five },previous,rng).map { it.getValue(five.correctOptionId) }.distinct().size)
        val counts=optionLetters(5).associateWith { 0 }.toMutableMap()
        previous=null
        (0 until 1000).chunked(3).forEach { ids ->
            val maps=assignLetters(ids.map { five },previous,rng)
            val correct=maps.map { it.getValue(five.correctOptionId) }
            assertEquals(correct.size,correct.distinct().size);assertNotEquals(previous,correct.first())
            correct.forEach { counts[it]=counts.getValue(it)+1 };previous=correct.last()
        }
        assertTrue(counts.values.all { it in 150..250 })
        println("FIVE_OPTIONS questions=1000 distribution=$counts groupsDistinct=true boundariesDifferent=true")
    }
}
