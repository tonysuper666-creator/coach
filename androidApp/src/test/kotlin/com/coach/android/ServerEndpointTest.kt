package com.coach.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerEndpointTest {
    private val host = "coach.example"
    private val card = "com.coach.android.ton1173"
    private fun url(request: String? = null, pkg: String = card, debug: Boolean = true, port: Int = -1) =
        resolveCoachServerUrl(request, pkg, debug, host, 18791, port)

    @Test fun launcherNeedsExplicitSafePort() {
        assertEquals("http://$host:18873", url(port = 18873))
        for (port in listOf(-1, 0, 65536, 18791, 8791, 8790))
            assertThrows(IllegalStateException::class.java) { url(port = port) }
        assertThrows(IllegalStateException::class.java) { url(debug = false, port = 18873) }
    }
    @Test fun invalidIntentNeverFallsBackToLauncherPort() {
        for (value in listOf("", "http://$host:18791", "http://$host:8791", "http://$host:8790",
            "http://other:18873", "https://$host:18873", "http://user@$host:18873",
            "http://$host:18873/path", "http://$host:18873/?x=1"))
            assertThrows(IllegalStateException::class.java) { url(value, port = 18873) }
        assertEquals("http://$host:18874", url("http://$host:18874", port = 18873))
    }
    @Test fun normalPackageBehaviorIsUnchanged() {
        assertEquals("http://$host:18791", url(pkg = "com.coach.android", port = 18873))
        assertEquals("http://$host:18791", url("http://other:18873", "com.coach.android"))
        assertEquals("http://$host:18874", url("http://$host:18874", "com.coach.android"))
        assertEquals("http://$host:18791", url("http://$host:18874", "com.coach.android", false))
    }
}
