package com.coach.desktop

import androidx.compose.runtime.Composable
import com.coach.ui.CoachApp

/** A configured ledger course uses its paired identity on Windows too. Local books have no
 * implicit remote course/question mapping and keep their existing unconfigured entry. */
internal fun desktopUsesTextbooks(osName: String, environmentCourse: String?, packagedCourse: String?): Boolean =
    osName.startsWith("Windows") && environmentCourse.isNullOrBlank() && packagedCourse.isNullOrBlank()

/** Shared by runCoach's paired window and the Windows entry behavior tests. */
@Composable
internal fun DesktopCourseContent(session: DesktopLearningSession, initialKp: String? = null,
    autoStart: Boolean = false, initialSource: Boolean = false) {
    CoachApp(session.controller, initialKp, autoStart, initialSource = initialSource,
        courseActions = { view -> NotesExportAction(view, session.controller, session.mathHttp) })
}
