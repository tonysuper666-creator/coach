package com.coach.domain

import com.coach.domain.grade.nfkc

enum class TutorIntent { NEXT_STEP, TEST_ME }
enum class TeachingMode(val label: String) {
    CURRENT("当前讲"), INTUITIVE("直觉讲"), EXAMPLE("换例子"), FORMAL("正式讲"), EXAM("考试写法")
}

/** Whole utterances only: a question containing “继续” is still a question. */
fun tutorIntent(text: String): TutorIntent? {
    val normalized = nfkc(text).trim().lowercase().trimEnd('。', '！', '!', '.', '？', '?', '～', '~').trim()
    return when (normalized) {
        "继续", "下一步", "出题", "快点", "下一题", "继续吧", "继续学", "请继续", "接着来", "next", "continue" -> TutorIntent.NEXT_STEP
        "考我", "考考我", "直接考我", "test me", "quiz me" -> TutorIntent.TEST_ME
        else -> null
    }
}

fun requireTutorGroupSize(size: Int): Int {
    require(size == 1 || size == 3) { "题组大小只支持 1 或 3" }
    return size
}
