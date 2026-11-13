package com.coach.ui

import com.coach.client.CourseView
import com.coach.client.ReviewPracticeSession
import com.coach.client.restoreReviewPracticeSession

/** Allowed review-specific controller port; does not modify shared navigation or the mainline controller. */
fun CoachController.restoreReviewPractice(kpId: String, view: CourseView): ReviewPracticeSession =
    restoreReviewPracticeSession(sync, courseId, kpId, tutor.agentRunId, view, nowMillis())
