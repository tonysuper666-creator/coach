package com.coach.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CourseConfigTest {
    @Test fun packagedCourseIsUsedWithoutEnvironmentOverride() {
        assertEquals("course_packaged", resolveCourse(null, "course_packaged"))
    }

    @Test fun environmentOverridesPackagedCourse() {
        assertEquals("course_override", resolveCourse(" course_override ", "course_packaged"))
        assertEquals("course_packaged", resolveCourse("  ", "course_packaged"))
    }

    @Test fun missingCourseFailsClearly() {
        val failure = assertFailsWith<IllegalStateException> { resolveCourse(null, " ") }
        assertTrue(failure.message.orEmpty().contains("coach.course"))
    }
}
