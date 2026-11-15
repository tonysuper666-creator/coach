package com.coach.desktop

import androidx.compose.runtime.Composable
import com.coach.ui.CoachApp

/** Unconfigured desktops open local books; configured courses retain their paired identity.
 * Keep the host argument for existing callers, but course availability decides this route. */
@Suppress("UNUSED_PARAMETER")
internal fun desktopUsesTextbooks(osName: String, environmentCourse: String?, packagedCourse: String?): Boolean =
    environmentCourse.isNullOrBlank() && packagedCourse.isNullOrBlank()

/** Shared by runCoach's paired window and the Windows entry behavior tests. */
@Composable
internal fun DesktopCourseContent(session: DesktopLearningSession, initialKp: String? = null,
    autoStart: Boolean = false, initialSource: Boolean = false) {
    CoachApp(session.controller, initialKp, autoStart, initialSource = initialSource,
        courseActions = { view -> NotesExportAction(view, session.controller, session.mathHttp) })
}
