package com.coach.android

/** A foreground surface for isolated instrumentation; no pairing or app data. */
class ReconnectSmokeActivity : android.app.Activity() {
    var resumed = false
        private set

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.TextView(this).apply {
            text = "Coach isolated reconnection check"
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }
}
