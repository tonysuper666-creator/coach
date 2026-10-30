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
