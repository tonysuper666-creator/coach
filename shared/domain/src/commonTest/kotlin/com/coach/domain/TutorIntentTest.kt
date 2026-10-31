package com.coach.domain

import kotlin.test.*

class TutorIntentTest {
    @Test fun recognizesOnlyExplicitCommands() {
        for (text in listOf("继续","下一步。","出题！","快点","下一题","请继续","continue","ＮＥＸＴ"))
            assertEquals(TutorIntent.NEXT_STEP,tutorIntent(text),text)
        for (text in listOf("考我","直接考我","quiz me")) assertEquals(TutorIntent.TEST_ME,tutorIntent(text),text)
        for (text in listOf("继续是什么意思？","这次快点讲机会成本","下一题为什么选这个？","如果继续增加成本呢？","B",""))
            assertNull(tutorIntent(text),text)
    }
}
