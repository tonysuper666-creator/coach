plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}
kotlin {
    jvm()
    android {
        namespace = "com.coach.shared.protocol"
        compileSdk = 37
        minSdk = 34
    }
    jvmToolchain(17)
    sourceSets.commonMain.dependencies { api(project(":shared:domain")); api(libs.serialization.json) }
}
