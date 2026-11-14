package com.coach.desktop.reader

import com.coach.client.CourseHttp
import com.coach.desktop.desktopServerUrl
import com.coach.desktop.developmentLaunch
import com.coach.desktop.refuseProductionPort
import java.nio.file.Files
import java.nio.file.Path

/** Shared connection contract for the native reader host and its native-window replay. */
internal fun explanationConnection(
    item: String,
    environment: (String) -> String? = System::getenv,
    property: (String) -> String? = System::getProperty,
): Pair<CourseHttp, DesktopExplanationSession> {
    val week = (property("coach.reader.week") ?: environment("COACH_READER_WEEK"))?.toIntOrNull()
        ?: error("请明确指定题目周次")
    val address = environment("COACH_URL") ?: property("coach.url") ?: error("请配置讲解服务地址")
    if (developmentLaunch(environment, property)) refuseProductionPort(address)?.let { error(it) }
    val url = desktopServerUrl(address)
    val course = environment("COACH_COURSE") ?: property("coach.course") ?: error("请明确指定课程")
    val token = environment("COACH_TOKEN_FILE")?.let { Files.readString(Path.of(it)).trim() } ?: environment("COACH_TOKEN")
        ?: error("请配置隔离账号凭据")
    require(token.isNotBlank()) { "账号凭据为空" }
    val http = CourseHttp(url, environment("COACH_DEVICE_ID") ?: "desktop", token, course)
    return http to DesktopExplanationSession(ExplanationOwner(course, week, item), CourseExplanationTransport(http), ReaderHttpSource(http.reader))
}
