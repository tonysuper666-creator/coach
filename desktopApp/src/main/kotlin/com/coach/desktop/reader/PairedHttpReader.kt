package com.coach.desktop.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.DesktopPairing
import com.coach.desktop.DesktopPairingGate
import com.coach.desktop.requireDevelopmentServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses the existing pairing UI and credentials; never resolves an HTTP ID from a local book. */
@Composable
internal fun PairedHttpReader(
    settings: DesktopConnectionSettings,
    course: String,
    request: ReaderOpenRequest? = null,
) {
    // Keep the mounted editor after revocation. Putting it inside the gate would dispose it on 401.
    var paired by remember(settings, course) { mutableStateOf<DesktopIdentity?>(null) }
    val identity = paired
    if (identity == null) {
        DesktopPairingGate(settings) { ready ->
            LaunchedEffect(ready) { paired = ready }
        }
    } else {
        PairedHttpReader(settings, identity, course, request)
    }
}

/** Reusable paired-session outlet for a course entry once that file's serial lease is granted. */
@Composable
internal fun PairedHttpReader(
    settings: DesktopConnectionSettings,
    identity: DesktopIdentity,
    course: String,
    request: ReaderOpenRequest? = null,
) {
    val host = remember(settings, identity, course) { pairedReaderHost(settings, identity, course) }
    LaunchedEffect(host) {
        withContext(Dispatchers.IO) { DesktopPairing(settings).awaitRevocation(identity) }
        host.revoke()
    }
    ReaderHostContent(host, request)
}

@Composable
internal fun ReaderHostContent(host: ReaderHostController, request: ReaderOpenRequest? = null) {
    DisposableEffect(host) { onDispose { host.close() } }
    LaunchedEffect(host, request) { host.open(request) }
    Column(Modifier.fillMaxSize()) {
        if (!host.session.draftsKept()) Text("未保存的批注仅在此窗口保留，关闭前请复制。", Modifier.testTag("reader-persistence-notice").padding(8.dp))
        if (!host.authorized) Text("配对已失效。未保存的文字仍在此窗口，请先复制。", Modifier.testTag("reader-pairing-revoked").padding(8.dp))
        val failure = host.openingFailure
        if (failure != null) Text(failure.text, Modifier.padding(8.dp))
        else ReaderWindow(host.session)
    }
}

internal fun pairedReaderHost(
    settings: DesktopConnectionSettings,
    identity: DesktopIdentity,
    course: String,
): ReaderHostController {
    requireDevelopmentServer(settings, identity.serverUrl)
    require(course.isNotBlank())
    val credentials = identity.credentials
    val http = CourseHttp(identity.serverUrl, credentials.deviceId, credentials.token, course)
    val drafts = ReaderDraftScope.forPairedSession(settings, identity, course)
    return ReaderHostController(ReaderHttpSource(http.reader), drafts = drafts, closeTransport = http::close)
}
