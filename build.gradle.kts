plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp) apply false
    alias(libs.plugins.sqldelight) apply false
}

tasks.register("liveTest") {
    group = "verification"
    description = "Opt-in live streaming acceptance (requires local corpus and authenticated Claude CLI)"
    dependsOn(":server:app:liveTest")
}

// Only the packager supplies this UTF-8 file. Its contents never become a command-line argument.
project(":desktopApp") {
    plugins.withId("java") {
        val channelFile = providers.environmentVariable("COACH_UPDATE_CHANNEL_FILE")
        tasks.named<ProcessResources>("processResources") {
            if (channelFile.isPresent) {
                val source = file(channelFile.get())
                from(source) { rename { "windows-update-channel.txt" } }
                doFirst {
                    require(source.isFile) { "Missing Windows update channel file" }
                    val uri = runCatching { java.net.URI(source.readText().trim()) }.getOrNull()
                    require(uri != null && uri.scheme == "https" && uri.host != null &&
                        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                        uri.path.endsWith("/latest.json")) { "Invalid Windows update channel" }
                }
            }
        }
    }
}
